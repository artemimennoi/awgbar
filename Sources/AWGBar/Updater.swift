import AppKit
import CryptoKit
import Foundation

/// Updates the app in place from GitHub releases, pre-releases included: download the DMG, check
/// it against the release's SHA256SUMS, swap the bundle and relaunch. Only on request, never in
/// the background. Authenticity rests on HTTPS to GitHub and the repository owner's account;
/// the checksum only catches a broken download.
@MainActor
final class Updater: ObservableObject {
    struct Release: Equatable {
        let version: String
        let dmg: URL
        let checksums: URL
    }

    @Published private(set) var available: Release?
    @Published private(set) var busy = false
    /// Set right before quitting to relaunch, so the app doesn't disconnect the VPN on the way out.
    private(set) var relaunching = false

    weak var store: TunnelStore?

    static let repository = "artemimennoi/awgbar"
    let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"

    // MARK: - Check

    func checkNow() {
        Task {
            busy = true
            defer { busy = false }
            do {
                if try await check() == nil { store?.notice = L("AWG Bar %@ is up to date", current) }
            } catch {
                store?.error = error.localizedDescription
            }
        }
    }

    func check() async throws -> Release? {
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/\(Self.repository)/releases?per_page=20")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("awgbar/\(current)", forHTTPHeaderField: "User-Agent")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let releases = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            throw AppError(L("Cannot reach GitHub to check for updates"))
        }
        let newest = releases.compactMap(Self.release)
            .filter { Self.isNewer($0.version, than: current) }
            .max { Self.isNewer($1.version, than: $0.version) }
        available = newest
        return newest
    }

    private static func release(_ json: [String: Any]) -> Release? {
        guard json["draft"] as? Bool != true,
              let tag = json["tag_name"] as? String,
              let assets = json["assets"] as? [[String: Any]] else { return nil }
        func asset(_ matches: (String) -> Bool) -> URL? {
            assets.first { ($0["name"] as? String).map(matches) ?? false }
                .flatMap { $0["browser_download_url"] as? String }
                .flatMap(URL.init(string:))
        }
        guard let dmg = asset({ $0.hasSuffix("-macos-arm64.dmg") }), let sums = asset({ $0 == "SHA256SUMS" }) else { return nil }
        return Release(version: String(tag.drop { $0 == "v" }), dmg: dmg, checksums: sums)
    }

    /// "0.0.10" > "0.0.9"; anything after "-" is ignored.
    static func isNewer(_ a: String, than b: String) -> Bool {
        func parts(_ v: String) -> [Int] {
            (v.split(separator: "-").first ?? "").split(separator: ".").map { Int($0) ?? 0 }
        }
        let x = parts(a), y = parts(b)
        for i in 0..<max(x.count, y.count) {
            let l = i < x.count ? x[i] : 0, r = i < y.count ? y[i] : 0
            if l != r { return l > r }
        }
        return false
    }

    // MARK: - Install

    func installAvailable() {
        guard let release = available else { return }
        Task {
            busy = true
            defer { busy = false }
            do {
                relaunch(try await install(release, replacing: Bundle.main.bundleURL))
            } catch {
                store?.error = error.localizedDescription
            }
        }
    }

    /// Replaces `target` with the app from the release's DMG and returns where the app now is.
    /// Separate from relaunching so it can be tested.
    func install(_ release: Release, replacing target: URL) async throws -> URL {
        let fm = FileManager.default
        let parent = target.deletingLastPathComponent()
        guard target.pathExtension == "app", !target.path.hasPrefix("/Volumes/"), fm.isWritableFile(atPath: parent.path) else {
            throw AppError(L("Move AWG Bar to Applications to update it"))
        }
        let work = fm.temporaryDirectory.appendingPathComponent("awgbar-update-\(UUID().uuidString)")
        try fm.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: work) }

        let dmg = try await download(release.dmg, to: work.appendingPathComponent(release.dmg.lastPathComponent))
        let sums = try String(contentsOf: try await download(release.checksums, to: work.appendingPathComponent("SHA256SUMS")), encoding: .utf8)
        let expected = sums.split(separator: "\n")
            .first { $0.hasSuffix(" " + release.dmg.lastPathComponent) }?
            .split(separator: " ").first.map(String.init)
        let actual = try await Task.detached {
            SHA256.hash(data: try Data(contentsOf: dmg, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
        }.value
        guard let expected, expected == actual else { throw AppError(L("The update failed its checksum check")) }

        let mount = work.appendingPathComponent("mnt")
        try await Self.run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path, dmg.path])
        defer { _ = try? Self.runSync("/usr/bin/hdiutil", ["detach", "-force", mount.path]) }

        // Found by identifier, not by file name: the bundle's file name can change between releases.
        let identifier = Bundle.main.bundleIdentifier ?? "local.awgbar"
        guard let newApp = try fm.contentsOfDirectory(at: mount, includingPropertiesForKeys: nil)
            .first(where: { $0.pathExtension == "app" && Bundle(url: $0)?.bundleIdentifier == identifier }) else {
            throw AppError(L("The update does not contain AWG Bar"))
        }
        try await Self.run("/usr/bin/codesign", ["--verify", "--deep", "--strict", newApp.path])

        // Stage next to the target so the swap is a same-volume rename.
        let staged = parent.appendingPathComponent(".AWGBar-update-\(UUID().uuidString).app")
        try await Self.run("/usr/bin/ditto", [newApp.path, staged.path])
        do {
            _ = try fm.replaceItemAt(target, withItemAt: staged)
        } catch {
            try? fm.removeItem(at: staged)
            throw error
        }
        // A release that renamed the app: give the installed copy the new name too. If that
        // fails, the updated app still runs under the old one.
        let renamed = parent.appendingPathComponent(newApp.lastPathComponent)
        guard renamed.lastPathComponent != target.lastPathComponent, !fm.fileExists(atPath: renamed.path),
              (try? fm.moveItem(at: target, to: renamed)) != nil else { return target }
        return renamed
    }

    /// Opens the new copy once this process is gone; the tunnel stays up across the restart.
    private func relaunch(_ app: URL) {
        relaunching = true
        let waiter = Process()
        waiter.executableURL = URL(fileURLWithPath: "/bin/sh")
        waiter.arguments = ["-c", "while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done; /usr/bin/open \"$0\"", app.path]
        try? waiter.run()
        NSApp.terminate(nil)
    }

    private func download(_ url: URL, to destination: URL) async throws -> URL {
        var request = URLRequest(url: url)
        request.setValue("awgbar/\(current)", forHTTPHeaderField: "User-Agent")
        let (file, response) = try await URLSession.shared.download(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw AppError(L("Cannot download the update"))
        }
        try FileManager.default.moveItem(at: file, to: destination)
        return destination
    }

    private static func run(_ tool: String, _ args: [String]) async throws {
        try await Task.detached { try runSync(tool, args) }.value
    }

    private nonisolated static func runSync(_ tool: String, _ args: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = args
        let stderr = Pipe()
        process.standardError = stderr
        process.standardOutput = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            let message = String(data: stderr.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
            throw AppError("\((tool as NSString).lastPathComponent): \(message.trimmingCharacters(in: .whitespacesAndNewlines))")
        }
    }
}
