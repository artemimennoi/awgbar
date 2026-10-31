# Building and releasing AWG Bar

To just use AWG Bar, download a release (see the [README](../README.md)). This page is for
building it from source, working on it and making releases. How the pieces fit is in
[how-it-works.md](how-it-works.md).

## Layout

| Path | What |
|---|---|
| `Sources/`, `Resources/`, `Makefile`, `Package.swift` | macOS: the menu bar app, the root helper (Swift) and what goes into the bundle |
| `linux/` | Linux: `helper/` (Go root service), `app/` (C++/Qt tray), `packaging/` (deb, systemd, polkit) |
| `android/` | Android: `app/` (Kotlin, Compose), `tunnel/` (amneziawg-android's userspace backend, Apache-2.0) |
| `VERSION` | the one version of all three clients |
| `scripts/sign.swift` | signs a release's `SHA256SUMS` |
| `.github/workflows/` | CI (`ci.yml`) and release builds (`release.yml`) |

## macOS

Needs macOS 26, the Xcode Command Line Tools (Swift), Go, git and curl.

```bash
make install
```

builds `amneziawg-go`, `awg`, `awg-quick` and a self-contained `bash` from pinned sources into
`.deps`, assembles `build.noindex/AWG Bar.app` and copies it to `/Applications`. Open it and press
**Install Helper**. `make app` only builds the bundle; `make uninstall` removes the helper and the
app (tunnels stay unless you run `Resources/uninstall-helper.sh --purge`). Bump
`HelperPaths.version` in `Sources/AWGShared/Protocol.swift` whenever the helper or the bundled tools
change, so the app offers to reinstall it.

The helper runs unprivileged for tests with `AWGBAR_SUPPORT` (a scratch directory with `tunnels/`
and `run/`) and `AWGBAR_SOCKET`; `--print-config <tunnel>` then shows what awg-quick would get.

The app icon is an Icon Composer file, `Resources/AppIcon.icon`, with layers from
`Resources/make-icon.py`. Its compiled form is committed, so the build doesn't need Xcode; after
changing the icon run `make icon` with Xcode installed.

## Linux

Needs Go and Docker: each package's app is built in a container of the oldest distribution it
targets (Ubuntu 22.04 for qt5, Debian 13 for qt6).

```bash
make -C linux deb              # linux/build/dist/awgbar_<version>_qt{5,6}_amd64.deb
make -C linux deb ARCH=arm64   # for ARM machines and VMs; releases have amd64 only
make -C linux test             # helper unit tests in a container
```

The helper also builds alone with `GOOS=linux go build ./helper` (in `linux/`), the app with CMake
(`-DAWGBAR_QT=5` or `6`) against Qt Widgets and Network. The kill switch, routing and DNS need a
real Linux machine or VM to try: install the package there and drive the helper as root over
`/run/awgbar/helper.sock` (one JSON request per line).

## Android

Needs JDK 17, the Android SDK (platform 36, build-tools 36), NDK 27.2.12479018 and CMake 3.22.1.
The Go toolchain for the tunnel library (with WireGuard's boottime patch) is downloaded into
`~/.gradle/caches/golang` on the first build.

```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.2.12479018" "cmake;3.22.1"
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:testDebugUnitTest assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`, for 64-bit ARM only (`abiFilters` in
`app/build.gradle.kts`; the Android emulator on an Apple Silicon Mac is 64-bit ARM too). The debug
build has its own id (`…awgbar.debug`) and installs next to the release one.

Release builds are signed with your own key. Gradle reads `android/keystore.properties` or
`~/.android/awgbar-release.properties` (neither is in git):

```
storeFile=/Users/you/.android/awgbar-release.jks
storePassword=…
keyAlias=awgbar
keyPassword=…
```

Android installs an update only over a build signed with the same key: back up the keystore with
its properties file.

## CI

`.github/workflows/ci.yml` runs on every pull request and push to `main`: gofmt, vet and tests of
the Linux helper, both Linux packages (uploaded as artifacts), the macOS app with its pinned tools,
and the Android unit tests and debug build.

`.github/workflows/release.yml` builds the files of a release when its tag `v<VERSION>` is pushed
(a tag that doesn't match `VERSION` fails), by hand (`gh workflow run release.yml --ref <branch>`)
and on pull requests that change it: the DMG and the third-party sources on macOS, the two Linux
packages for amd64 and the APK, in parallel and without caches, about 5 minutes. They come out
unsigned as one artifact, `awgbar-<version>`, kept for 14 days. The workflow has no secrets and
can't write to the repository: the signing keys stay on the Mac, and `make release` signs there.

## Releasing

All clients share `VERSION` and one GitHub release `v<VERSION>`; the macOS bundle version and
Android's `versionCode` derive from it (x.y.z → x·10000 + y·100 + z).

1. Bump `VERSION` (and `HelperPaths.version` if the macOS helper changed), commit, merge to `main`.
2. Tag the merged commit and push the tag; `release.yml` builds the files:

   ```bash
   git pull && git tag "v$(cat VERSION)" && git push origin "v$(cat VERSION)"
   ```

3. Once that run has passed, `make release` (on the Mac with the keys) downloads its artifact,
   signs the APK with the keystore from `~/.android/awgbar-release.properties` (`zipalign`, then
   `apksigner` from build-tools 36, checked against the certificate every release has) and puts
   it with the rest into `build.noindex/release`, named `awgbar-<version>-<platform>-…`. It
   writes `SHA256SUMS` over all of them, signs it into `SHA256SUMS.sig` and checks that
   signature against the public key in `Updater.swift`. It also writes
   `build.noindex/release-notes.md`: the download table with direct links
   (`scripts/release-download.md`). Add what changed below it. `make release RUN=<id>` takes the
   files of another run of the workflow, for example to try the signing on a branch.
4. Publish them as a pre-release, with the command `make release` prints:

   ```bash
   gh release create "v$(cat VERSION)" build.noindex/release/* --verify-tag --prerelease --title "AWG Bar $(cat VERSION) beta" --notes-file build.noindex/release-notes.md
   ```

   The DMG's name has to end in `-macos-arm64.dmg`: that is how installed apps find it.

The macOS updater installs nothing without a valid `SHA256SUMS.sig`. It is made with the Ed25519
key in `~/.config/awgbar/release-ed25519.key` (`swift scripts/sign.swift keygen <file>` makes one;
its public half is `Updater.signingKey` in `Sources/AWGBar/Updater.swift`). Keep a backup: without
the key no update can be signed, and a new key means an app release with the new public key that
has to be installed by hand.

## Translations

The UI is in English and Russian. macOS: strings go through `L("English text")`, Russian in
`Resources/ru.lproj/AWGBar.strings`. Linux: `L()` with the table in `linux/app/src/l10n.cpp`.
Android: `res/values` and `res/values-ru`.
