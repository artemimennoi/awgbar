import AWGShared
import Foundation

/// An AmneziaWG config reduced to known keys, re-rendered in canonical form before awg-quick sees it.
///
/// awg-quick runs PreUp/PostUp/PreDown/PostDown through `eval` as root, so anything that reaches it
/// must be parsed exactly the way we think it is. Rather than filtering out dangerous keys (and
/// risking a parser mismatch with bash, e.g. a form feed before "PostUp"), only allowlisted keys
/// with validated values survive, written back as plain "Key = value" lines.
struct TunnelConfig {
    typealias Entry = (key: String, value: String)

    var title: String?
    var interface: [Entry] = []
    var peers: [[Entry]] = []

    /// Display name stored as a comment, which awg-quick and awg both ignore.
    static let titlePrefix = "# awgbar-name = "

    /// [Interface] keys awg-quick consumes itself. Table, SaveConfig and the hooks are left out on purpose.
    private static let quickInterfaceKeys = ["Address", "DNS", "MTU"]
    /// Keys `awg setconf` accepts, from amneziawg-tools src/config.c.
    private static let awgInterfaceKeys = [
        "PrivateKey", "ListenPort", "FwMark", "Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4",
        "H1", "H2", "H3", "H4", "I1", "I2", "I3", "I4", "I5", "HeaderProtectionKey",
        "ContentPaddingAddition", "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime",
        "KeepaliveTimeout", "MaxHandshakeAttempts", "RandomTrailers", "DisableCookies",
    ]
    private static let peerKeys = ["PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive", "AdvancedSecurity"]

    private static func canonical(_ keys: [String]) -> [String: String] {
        Dictionary(uniqueKeysWithValues: keys.map { ($0.lowercased(), $0) })
    }

    private static let interfaceKeyMap = canonical(quickInterfaceKeys + awgInterfaceKeys)
    private static let peerKeyMap = canonical(peerKeys)

    /// Keys that were present but not allowed; reported, never written.
    private(set) var dropped: [String] = []

    init(parsing text: String) throws {
        enum Section { case none, interface, peer }
        var section = Section.none
        var sawInterface = false

        // Split on every line terminator bash or awg might honour, not just "\n".
        for raw in text.components(separatedBy: CharacterSet.newlines) {
            if raw.hasPrefix(Self.titlePrefix) {
                title = Self.cleanTitle(String(raw.dropFirst(Self.titlePrefix.count)))
                continue
            }
            let line = (raw.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? "")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }

            if line.hasPrefix("[") {
                switch line.lowercased() {
                case "[interface]":
                    guard !sawInterface else { throw HelperError("More than one [Interface] section") }
                    sawInterface = true
                    section = .interface
                case "[peer]":
                    peers.append([])
                    section = .peer
                default:
                    throw HelperError("Unknown section \(line)")
                }
                continue
            }

            let parts = line.split(separator: "=", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            guard parts.count == 2, !parts[0].isEmpty else { throw HelperError("Not a key = value line: \(line)") }
            let value = parts[1]
            guard !value.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else {
                throw HelperError("Control characters in \(parts[0])")
            }

            switch section {
            case .none:
                throw HelperError("\(parts[0]) outside of a section")
            case .interface:
                guard let key = Self.interfaceKeyMap[parts[0].lowercased()] else { dropped.append(parts[0]); continue }
                try Self.validate(key, value)
                interface.append((key, value))
            case .peer:
                guard let key = Self.peerKeyMap[parts[0].lowercased()] else { dropped.append(parts[0]); continue }
                try Self.validate(key, value)
                peers[peers.count - 1].append((key, value))
            }
        }
        guard sawInterface, !peers.isEmpty else {
            throw HelperError("Not an AmneziaWG config: [Interface] or [Peer] section is missing")
        }
        guard interface.contains(where: { $0.key == "PrivateKey" }) else { throw HelperError("PrivateKey is missing") }
    }

    /// awg-quick splits Address and DNS on commas and expands them unquoted, so their items
    /// must not contain anything bash would glob or split differently.
    private static func validate(_ key: String, _ value: String) throws {
        func items() -> [String] { value.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } }
        let ok: Bool
        switch key {
        case "Address", "AllowedIPs":
            ok = items().allSatisfy { !$0.contains("*") && CIDR($0) != nil }
        case "DNS":
            ok = items().allSatisfy { $0.range(of: "^[A-Za-z0-9.:_-]+$", options: .regularExpression) != nil }
        case "MTU", "ListenPort":
            ok = value.range(of: "^[0-9]{1,5}$", options: .regularExpression) != nil
        case "PersistentKeepalive":
            // AmneziaWG randomizes keepalive within "lo-hi" (u16_range_from_string in amneziawg-tools).
            ok = value.range(of: "^[0-9]{1,5}(-[0-9]{1,5})?$", options: .regularExpression) != nil
        case "Endpoint":
            ok = value.range(of: "^[A-Za-z0-9.:\\[\\]_-]+$", options: .regularExpression) != nil
        default:
            ok = true
        }
        guard ok else { throw HelperError("Invalid \(key): \(value)") }
    }

    /// One line, no control characters, sane length: it is written into the config file.
    static func cleanTitle(_ raw: String?) -> String? {
        let title = String((raw ?? "").unicodeScalars.map { CharacterSet.controlCharacters.contains($0) ? " " : Character($0) })
            .trimmingCharacters(in: .whitespaces)
        return title.isEmpty ? nil : String(title.prefix(64))
    }

    /// Same client key talking to the same peers is the same tunnel, whatever its title or endpoint.
    var identity: String {
        let privateKey = interface.first { $0.key == "PrivateKey" }?.value ?? ""
        let peerKeys = peers.compactMap { $0.first { $0.key == "PublicKey" }?.value }.sorted()
        return ([privateKey] + peerKeys).joined(separator: "|")
    }

    var endpoint: String? {
        peers.lazy.compactMap { $0.first { $0.key == "Endpoint" }?.value }.first
    }

    private var allowedIPs: [CIDR] {
        peers.flatMap { $0.filter { $0.key == "AllowedIPs" } }
            .flatMap { $0.value.split(separator: ",") }
            .compactMap { CIDR(String($0)) }
    }

    /// The config routes all IPv6 into the tunnel but gives it no IPv6 address, so awg-quick
    /// cannot add the IPv6 routes and that traffic would silently bypass the VPN.
    var leaksIPv6: Bool {
        let fullV6 = allowedIPs.contains { $0.isV6 && $0.prefix == 0 }
        let hasV6Address = interface.filter { $0.key == "Address" }
            .flatMap { $0.value.split(separator: ",") }
            .contains { CIDR(String($0))?.isV6 == true }
        return fullV6 && !hasV6Address
    }

    func render(includeTitle: Bool) -> String {
        var lines: [String] = []
        if includeTitle, let title { lines.append(Self.titlePrefix + title) }
        lines.append("[Interface]")
        lines += interface.map { "\($0.key) = \($0.value)" }
        for peer in peers {
            lines.append("")
            lines.append("[Peer]")
            lines += peer.map { "\($0.key) = \($0.value)" }
        }
        return lines.joined(separator: "\n") + "\n"
    }
}
