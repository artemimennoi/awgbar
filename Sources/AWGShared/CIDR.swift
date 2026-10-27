import Darwin
import Foundation

/// An IPv4 or IPv6 network: split-tunnel exclusions and config address validation.
public struct CIDR: Hashable, CustomStringConvertible, Sendable {
    public private(set) var bytes: [UInt8]
    public let prefix: Int

    public var isV6: Bool { bytes.count == 16 }
    private var bitCount: Int { bytes.count * 8 }

    public init?(bytes: [UInt8], prefix: Int) {
        guard bytes.count == 4 || bytes.count == 16, (0...bytes.count * 8).contains(prefix) else { return nil }
        self.bytes = bytes
        self.prefix = prefix
        for i in prefix..<bitCount { setBit(i, false) }
    }

    /// Accepts "10.0.0.0/8", "10.1.2.3" (/32), "10.*" or "192.168.*.*" (octet wildcards), "fd00::/8", "fd00::1".
    public init?(_ text: String) {
        let s = text.trimmingCharacters(in: .whitespaces)
        if s.contains("*") {
            let parts = s.split(separator: ".", omittingEmptySubsequences: false)
            guard parts.count <= 4 else { return nil }
            var octets: [UInt8] = []
            for part in parts {
                if part == "*" { break }
                guard let octet = UInt8(part) else { return nil }
                octets.append(octet)
            }
            // Everything after the first "*" must be "*" too: "10.*.3" is not a network.
            guard parts.dropFirst(octets.count).allSatisfy({ $0 == "*" }) else { return nil }
            self.init(bytes: octets + Array(repeating: 0, count: 4 - octets.count), prefix: octets.count * 8)
            return
        }

        let pieces = s.split(separator: "/", maxSplits: 1).map(String.init)
        guard let address = pieces.first, let bytes = CIDR.parseAddress(address) else { return nil }
        var prefix = bytes.count * 8
        if pieces.count == 2 {
            guard let p = Int(pieces[1]) else { return nil }
            prefix = p
        }
        self.init(bytes: bytes, prefix: prefix)
    }

    public static func parseAddress(_ s: String) -> [UInt8]? {
        var v4 = in_addr()
        if inet_pton(AF_INET, s, &v4) == 1 {
            return withUnsafeBytes(of: &v4) { Array($0) }
        }
        var v6 = in6_addr()
        if inet_pton(AF_INET6, s, &v6) == 1 {
            return withUnsafeBytes(of: &v6) { Array($0) }
        }
        return nil
    }

    public var description: String {
        var buf = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
        bytes.withUnsafeBytes { raw in
            _ = inet_ntop(isV6 ? AF_INET6 : AF_INET, raw.baseAddress, &buf, socklen_t(buf.count))
        }
        return "\(String(cString: buf))/\(prefix)"
    }

    private mutating func setBit(_ i: Int, _ on: Bool) {
        let mask: UInt8 = 0x80 >> UInt8(i % 8)
        if on { bytes[i / 8] |= mask } else { bytes[i / 8] &= ~mask }
    }
}

/// Splits user input like "10.*, 192.168.0.0/16 fd00::/8" into networks; returns the entries that failed to parse.
public func parseNetworkList(_ text: String) -> (networks: [CIDR], invalid: [String]) {
    let entries = text.split(whereSeparator: { ",; \n\t".contains($0) }).map(String.init)
    var networks: [CIDR] = []
    var invalid: [String] = []
    for entry in entries {
        if let net = CIDR(entry) {
            if !networks.contains(net) { networks.append(net) }
        } else {
            invalid.append(entry)
        }
    }
    return (networks, invalid)
}
