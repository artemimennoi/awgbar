import AWGShared
import Foundation

struct ImportedTunnel {
    var name: String
    var config: String
}

struct AppError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// Accepts an AmneziaVPN key (vpn://...) or a plain AmneziaWG .conf text.
enum KeyImport {
    static func parse(_ text: String, suggestedName: String) throws -> [ImportedTunnel] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("vpn://") {
            return try decodeVpnKey(trimmed)
        }
        if trimmed.range(of: "[Interface]", options: .caseInsensitive) != nil {
            return [ImportedTunnel(name: suggestedName, config: trimmed + "\n")]
        }
        throw AppError(L("Expected a vpn://… key or an AmneziaWG config"))
    }

    /// vpn:// is base64url of Qt's qCompress output: a 4-byte big-endian length, then a zlib stream.
    static func decodeVpnKey(_ key: String) throws -> [ImportedTunnel] {
        var b64 = String(key.dropFirst("vpn://".count)).filter { !$0.isWhitespace }
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        b64 += String(repeating: "=", count: (4 - b64.count % 4) % 4)
        guard let raw = Data(base64Encoded: b64) else { throw AppError(L("Broken key: not base64")) }

        let json: Data
        if raw.count > 6, raw[raw.startIndex + 4] == 0x78,
           // NSData's .zlib is raw deflate, so skip the length and the 2-byte zlib header.
           let inflated = try? (Data(raw.dropFirst(6)) as NSData).decompressed(using: .zlib) as Data {
            json = inflated
        } else {
            json = raw
        }
        guard let doc = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            throw AppError(L("Broken key: cannot unpack it"))
        }
        if doc["api_config"] != nil || doc["auth_data"] != nil {
            throw AppError(L("This is an Amnezia Premium/Free key: its config is issued by Amnezia's server at connect time and cannot be imported"))
        }

        let title = (doc["description"] as? String) ?? (doc["hostName"] as? String) ?? "awg"
        var result: [ImportedTunnel] = []
        for container in doc["containers"] as? [[String: Any]] ?? [] {
            for proto in ["awg", "wireguard"] {
                guard let block = container[proto] as? [String: Any], let last = block["last_config"] else { continue }
                let lastConfig = (last as? String).flatMap {
                    try? JSONSerialization.jsonObject(with: Data($0.utf8)) as? [String: Any]
                } ?? (last as? [String: Any])
                if let config = lastConfig?["config"] as? String {
                    result.append(ImportedTunnel(name: title, config: config.trimmingCharacters(in: .whitespacesAndNewlines) + "\n"))
                }
            }
        }
        guard !result.isEmpty else {
            throw AppError(L("The key has no ready AmneziaWG config. If it is an admin key, connect once in AmneziaVPN and share the connection in AmneziaWG format"))
        }
        return result
    }

    /// awg-quick uses the file name as the interface name: [a-zA-Z0-9_=+.-]{1,15}.
    static func tunnelName(from title: String, taken: Set<String>) -> String {
        let latin = title.applyingTransform(.toLatin, reverse: false)?
            .applyingTransform(.stripDiacritics, reverse: false) ?? title
        // Anything outside the interface charset becomes one "-": "Rezerv | #202 · NUE" → "Rezerv-202-NUE".
        var base = ""
        for ch in latin {
            if ch.isASCII && (ch.isLetter || ch.isNumber || "_=+.".contains(ch)) {
                base.append(ch)
            } else if !base.isEmpty, base.last != "-" {
                base.append("-")
            }
        }
        while base.last == "-" { base.removeLast() }
        base = String(base.prefix(15))
        while base.last == "-" { base.removeLast() }
        if base.isEmpty { base = "awg" }

        var name = base
        var n = 2
        while taken.contains(name) {
            let suffix = "-\(n)"
            name = String(base.prefix(15 - suffix.count)) + suffix
            n += 1
        }
        precondition(isValidTunnelName(name))
        return name
    }
}
