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

/// Accepts an AmneziaVPN key (vpn://...), AmneziaVPN JSON (a decoded key, a connection export
/// or a settings backup) or a plain AmneziaWG .conf text.
enum KeyImport {
    static func parse(_ text: String, suggestedName: String) throws -> [ImportedTunnel] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("vpn://") {
            return try configs(in: decodeVpnKey(trimmed), suggestedName: suggestedName)
        }
        let looksLikeJSON = trimmed.hasPrefix("{") || trimmed.hasPrefix("[")
        // A .conf starts with "[Interface]" too, so only a successful parse makes it JSON.
        if looksLikeJSON, let doc = try? JSONSerialization.jsonObject(with: Data(trimmed.utf8)) {
            return try configs(in: doc, suggestedName: suggestedName)
        }
        if trimmed.range(of: "[Interface]", options: .caseInsensitive) != nil {
            return [ImportedTunnel(name: suggestedName, config: trimmed + "\n")]
        }
        if looksLikeJSON { throw AppError(L("The file is not valid JSON")) }
        throw AppError(L("Expected a vpn://… key, AmneziaVPN JSON or an AmneziaWG config"))
    }

    /// AmneziaVPN keeps the ready wg-quick text in `containers[].awg.last_config.config`, where
    /// `last_config` is itself JSON encoded as a string. Keys, exports and backups nest that at
    /// different depths, so walk the whole document, unwrap JSON found in strings, and take every
    /// string that is a config. The title is the nearest server `description`.
    static func configs(in doc: Any, suggestedName: String) throws -> [ImportedTunnel] {
        var result: [ImportedTunnel] = []
        var seen = Set<String>()
        var sawAPIConfig = false

        func walk(_ node: Any, title: String?) {
            switch node {
            case let dict as [String: Any]:
                if dict["api_config"] != nil || dict["auth_data"] != nil { sawAPIConfig = true }
                let description = (dict["description"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                let title = description ?? title ?? (dict["hostName"] as? String)
                for key in dict.keys.sorted() { walk(dict[key]!, title: title) }
            case let array as [Any]:
                array.forEach { walk($0, title: title) }
            case let string as String:
                let text = string.trimmingCharacters(in: .whitespacesAndNewlines)
                // JSON first: an encoded server list contains "[Interface]" too. A config itself
                // starts with "[Interface]" and never parses as JSON.
                if text.hasPrefix("{") || text.hasPrefix("["),
                   let nested = try? JSONSerialization.jsonObject(with: Data(text.utf8)) {
                    walk(nested, title: title)
                } else if text.range(of: "[Interface]", options: .caseInsensitive) != nil,
                          text.range(of: "[Peer]", options: .caseInsensitive) != nil {
                    if seen.insert(text).inserted {
                        result.append(ImportedTunnel(name: title ?? suggestedName, config: text + "\n"))
                    }
                }
            default:
                break
            }
        }

        walk(doc, title: nil)
        guard !result.isEmpty else {
            if sawAPIConfig {
                throw AppError(L("This is an Amnezia Premium/Free key: its config is issued by Amnezia's server at connect time and cannot be imported"))
            }
            throw AppError(L("No ready AmneziaWG config inside. If this is an admin key or a backup, connect once in AmneziaVPN and share the connection in AmneziaWG format"))
        }
        return result
    }

    /// vpn:// is base64url of Qt's qCompress output: a 4-byte big-endian length, then a zlib stream.
    static func decodeVpnKey(_ key: String) throws -> Any {
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
        guard let doc = try? JSONSerialization.jsonObject(with: json) else {
            throw AppError(L("Broken key: cannot unpack it"))
        }
        return doc
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
