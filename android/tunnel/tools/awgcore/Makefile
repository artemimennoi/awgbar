# The tunnel library from awgcore, our own AmneziaWG core in Rust (artemimennoi/awgcore, private:
# fetching it needs GitHub credentials that can read it). It exports the JNI functions
# org.amnezia.awg.GoBackend calls and is installed under the name the Go library had, so the
# Java side loads either. CMake runs this with the NDK's toolchain (../CMakeLists.txt).
#
# The revision is pinned here, as libwg-go's go.mod pins amneziawg-go. AWGCORE_SRC builds a
# local checkout instead, for development: ./gradlew -PawgcoreSrc=$HOME/projects/awgcore …

AWGCORE_URL ?= https://github.com/artemimennoi/awgcore
AWGCORE_REV ?= 690430c43cf064dda2cbee3ea7705404d71d649f

ifeq ($(GRADLE_USER_HOME),)
$(error GRADLE_USER_HOME is not set: run this through Gradle)
endif

DESTDIR ?= $(CURDIR)/out
CARGO ?= $(shell command -v cargo 2>/dev/null || echo $(HOME)/.cargo/bin/cargo)
RUSTUP ?= $(shell command -v rustup 2>/dev/null || echo $(HOME)/.cargo/bin/rustup)

RUST_TARGET_arm64 := aarch64-linux-android
RUST_TARGET := $(RUST_TARGET_$(ANDROID_ARCH_NAME))
RUST_TARGET_ENV := $(shell echo $(RUST_TARGET) | tr a-z- A-Z_)
# The NDK's clang for this ABI and API level links the library.
LINKER := $(ANDROID_TOOLCHAIN_ROOT)/bin/$(RUST_TARGET)$(ANDROID_PLATFORM_LEVEL)-clang

ifeq ($(AWGCORE_SRC),)
SRC := $(GRADLE_USER_HOME)/caches/awgcore/$(AWGCORE_REV)
REV := $(shell printf %.7s $(AWGCORE_REV))
FETCHED := $(SRC)/.fetched
else
SRC := $(AWGCORE_SRC)
REV := $(shell git -C "$(AWGCORE_SRC)" rev-parse --short=7 HEAD 2>/dev/null)-local
FETCHED :=
endif

default: $(DESTDIR)/libwg-go.so

# One shallow fetch of the pinned commit. Repeating it over a checkout an interrupted fetch left
# behind is harmless, so nothing is ever deleted.
$(SRC)/.fetched:
	mkdir -p "$(SRC)"
	git -C "$(SRC)" init -q
	git -C "$(SRC)" fetch -q --depth 1 "$(AWGCORE_URL)" "$(AWGCORE_REV)"
	git -C "$(SRC)" checkout -q -f FETCH_HEAD
	test "$$(git -C "$(SRC)" rev-parse HEAD)" = "$(AWGCORE_REV)"
	touch "$@"

# Vendored crates and the toolchain awgcore's rust-toolchain.toml pins: cargo builds offline from
# the checkout alone, and decides itself whether anything changed. RUSTFLAGS stays unset (it would
# replace the workspace's own flags); the link flags apply to the library alone, as the Go
# library's do: its soname, 16 KiB pages, no build id. The revision goes into awgVersion().
.PHONY: $(DESTDIR)/libwg-go.so
$(DESTDIR)/libwg-go.so: $(FETCHED)
	@test -n "$(RUST_TARGET)" || { echo "awgcore: no Rust target for ABI $(ANDROID_ARCH_NAME)"; exit 1; }
	@cd "$(SRC)" && "$(RUSTUP)" target list --installed | grep -qx "$(RUST_TARGET)" || { \
		echo "awgcore: the Rust standard library for $(RUST_TARGET) is missing: in $(SRC) run rustup target add $(RUST_TARGET)"; exit 1; }
	cd "$(SRC)" && env -u RUSTFLAGS AWGCORE_REV=$(REV) \
		CARGO_TARGET_$(RUST_TARGET_ENV)_LINKER="$(LINKER)" \
		"$(CARGO)" rustc -p awgcore-ffi --lib --release --locked --offline \
		--target $(RUST_TARGET) --crate-type cdylib -- \
		-C link-arg=-Wl,-soname,libwg-go.so -C link-arg=-Wl,-z,max-page-size=16384 \
		-C link-arg=-Wl,--build-id=none
	mkdir -p "$(DESTDIR)"
	cp "$(SRC)/target/$(RUST_TARGET)/release/libawgcore_ffi.so" "$@"

.DELETE_ON_ERROR:
