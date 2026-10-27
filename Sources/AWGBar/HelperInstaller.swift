import AWGShared
import Foundation

/// Runs Resources/install-helper.sh as root via the standard macOS admin password prompt.
enum HelperInstaller {
    /// Returns false if the user cancelled the password prompt.
    /// The prompt runs in osascript rather than NSAppleScript: an in-process modal blocks the main
    /// thread while the menu bar window is animating closed and leaves its glass background stuck on screen.
    static func install() async throws -> Bool {
        guard let resources = Bundle.main.resourcePath,
              FileManager.default.fileExists(atPath: resources + "/install-helper.sh") else {
            throw AppError(L("install-helper.sh is missing from the app; build it with make"))
        }
        let command = "/bin/bash \(shellQuote(resources + "/install-helper.sh")) \(shellQuote(resources)) \(getuid())"
        let source = """
        do shell script "\(appleScriptEscape(command))" \
        with prompt "\(appleScriptEscape(L("awgbar is installing a helper that brings up the VPN tunnel.")))" \
        with administrator privileges
        """

        return try await withCheckedThrowingContinuation { continuation in
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/osascript")
            process.arguments = ["-e", source]
            let stderr = Pipe()
            process.standardError = stderr
            process.standardOutput = FileHandle.nullDevice
            process.terminationHandler = { process in
                let message = String(data: stderr.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if process.terminationStatus == 0 {
                    continuation.resume(returning: true)
                } else if message.contains("-128") {
                    continuation.resume(returning: false)
                } else {
                    continuation.resume(throwing: AppError(message.isEmpty ? L("Helper installation failed") : message))
                }
            }
            do {
                try process.run()
            } catch {
                continuation.resume(throwing: error)
            }
        }
    }

    private static func shellQuote(_ s: String) -> String {
        "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'"
    }

    private static func appleScriptEscape(_ s: String) -> String {
        s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    }
}
