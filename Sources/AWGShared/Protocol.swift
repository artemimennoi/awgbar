import Foundation

/// Locations shared by the app, the root helper and the install script.
/// AWGBAR_SOCKET / AWGBAR_SUPPORT override them for running the helper unprivileged in tests.
public enum HelperPaths {
    private static let env = ProcessInfo.processInfo.environment
    public static let label = "local.awgbar.helper"
    public static let socket = env["AWGBAR_SOCKET"] ?? "/var/run/awgbar.sock"
    public static let support = env["AWGBAR_SUPPORT"] ?? "/Library/Application Support/AWGBar"
    public static var tunnels: String { support + "/tunnels" }
    public static var bin: String { support + "/bin" }
    /// Split-tunnel exclusions, one network per line, shared by all tunnels.
    public static var excluded: String { support + "/excluded.txt" }
    /// Sanitized configs as actually handed to awg-quick, plus per-tunnel state markers.
    public static var runtime: String { support + "/run" }
    /// Present only while the user has turned the debug log on.
    public static var debugLogFlag: String { support + "/debug-log" }
    public static let helperBinary = "/Library/PrivilegedHelperTools/\(label)"
    /// Bump when the helper or the bundled tools change, so the app offers to reinstall.
    public static let version = 6
}

public struct Request: Codable, Sendable {
    public enum Command: String, Codable, Sendable {
        case version, list, stats, importConfig, delete, rename, up, down, setExcluded, setDebugLog
    }

    public var command: Command
    public var name: String?
    public var config: String?
    public var excluded: [String]?
    /// Display name for importConfig/rename; the file name stays an interface-safe id.
    public var title: String?
    public var enabled: Bool?

    public init(_ command: Command, name: String? = nil, config: String? = nil, excluded: [String]? = nil,
                title: String? = nil, enabled: Bool? = nil) {
        self.command = command
        self.name = name
        self.config = config
        self.excluded = excluded
        self.title = title
        self.enabled = enabled
    }
}

public struct TunnelInfo: Codable, Hashable, Identifiable, Sendable {
    public var name: String
    public var active: Bool
    /// Endpoint from the config ("host:port"), shown while disconnected.
    public var endpoint: String?
    /// Human name as imported or renamed ("Резерв | #202 · NUE"); `name` is the awg-quick interface id.
    public var title: String?
    public var id: String { name }
    public var displayName: String { title ?? name }

    public init(name: String, active: Bool, endpoint: String? = nil, title: String? = nil) {
        self.name = name
        self.active = active
        self.endpoint = endpoint
        self.title = title
    }
}

public struct TunnelStats: Codable, Hashable, Sendable {
    public var endpoint: String?
    /// Unix time of the latest handshake, nil if none yet.
    public var lastHandshake: TimeInterval?
    public var rxBytes: Int64
    public var txBytes: Int64

    public init(endpoint: String?, lastHandshake: TimeInterval?, rxBytes: Int64, txBytes: Int64) {
        self.endpoint = endpoint
        self.lastHandshake = lastHandshake
        self.rxBytes = rxBytes
        self.txBytes = txBytes
    }
}

public struct Response: Codable, Sendable {
    public var ok: Bool
    public var error: String?
    public var version: Int?
    public var tunnels: [TunnelInfo]?
    public var excluded: [String]?
    public var debugLog: Bool?
    public var stats: TunnelStats?
    public var log: String?
    /// Set on some failures so the app can word them itself: "duplicate:<tunnel name>".
    public var code: String?

    public init(ok: Bool = true, error: String? = nil, version: Int? = nil, tunnels: [TunnelInfo]? = nil,
                excluded: [String]? = nil, debugLog: Bool? = nil, stats: TunnelStats? = nil, log: String? = nil) {
        self.ok = ok
        self.error = error
        self.version = version
        self.tunnels = tunnels
        self.excluded = excluded
        self.debugLog = debugLog
        self.stats = stats
        self.log = log
    }

    public static func failure(_ message: String, log: String? = nil) -> Response {
        Response(ok: false, error: message, log: log)
    }
}

/// awg-quick derives the interface name from the file name and accepts only this pattern.
public func isValidTunnelName(_ name: String) -> Bool {
    name.range(of: "^[a-zA-Z0-9_=+.-]{1,15}$", options: .regularExpression) != nil
}
