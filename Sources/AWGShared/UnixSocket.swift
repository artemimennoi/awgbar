import Darwin
import Foundation

public struct SocketError: LocalizedError {
    public let operation: String
    public let code: Int32

    public init(_ operation: String, _ code: Int32 = errno) {
        self.operation = operation
        self.code = code
    }

    public var errorDescription: String? { "\(operation): \(String(cString: strerror(code)))" }
}

/// One request per connection: the client writes JSON and half-closes, the server answers with JSON and closes.
public enum UnixSocket {
    public static let maxMessage = 1 << 20

    /// Returns -1 with errno = ENAMETOOLONG instead of silently truncating a long path.
    public static func withAddress(_ path: String, _ body: (UnsafePointer<sockaddr>, socklen_t) -> Int32) -> Int32 {
        var addr = sockaddr_un()
        addr.sun_family = sa_family_t(AF_UNIX)
        let bytes = Array(path.utf8)
        guard bytes.count < MemoryLayout.size(ofValue: addr.sun_path) else {
            errno = ENAMETOOLONG
            return -1
        }
        withUnsafeMutableBytes(of: &addr.sun_path) { buf in
            for (i, b) in bytes.enumerated() { buf[i] = b }
        }
        addr.sun_len = UInt8(MemoryLayout<sockaddr_un>.size)
        return withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { body($0, socklen_t(MemoryLayout<sockaddr_un>.size)) }
        }
    }

    public static func configure(_ fd: Int32, timeout: Int) {
        var on: Int32 = 1
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &on, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: timeout, tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
    }

    public static func readAll(_ fd: Int32) -> Data {
        var data = Data()
        var buf = [UInt8](repeating: 0, count: 64 * 1024)
        while data.count < maxMessage {
            let n = read(fd, &buf, buf.count)
            if n <= 0 { break }
            data.append(buf, count: n)
        }
        return data
    }

    @discardableResult
    public static func writeAll(_ fd: Int32, _ data: Data) -> Bool {
        data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return true }
            var offset = 0
            while offset < raw.count {
                let n = write(fd, base + offset, raw.count - offset)
                if n <= 0 { return false }
                offset += n
            }
            return true
        }
    }
}

public enum HelperClient {
    /// Blocking; call off the main thread. `up` can take a few seconds while awg-quick sets routes and DNS.
    public static func send(_ request: Request, timeout: Int = 60) throws -> Response {
        let fd = socket(AF_UNIX, SOCK_STREAM, 0)
        guard fd >= 0 else { throw SocketError("socket") }
        defer { close(fd) }
        UnixSocket.configure(fd, timeout: timeout)

        let rc = UnixSocket.withAddress(HelperPaths.socket) { connect(fd, $0, $1) }
        guard rc == 0 else { throw SocketError("connect") }
        guard UnixSocket.writeAll(fd, try JSONEncoder().encode(request)) else { throw SocketError("write") }
        shutdown(fd, SHUT_WR)

        let data = UnixSocket.readAll(fd)
        guard !data.isEmpty else { throw SocketError("read", ECONNRESET) }
        return try JSONDecoder().decode(Response.self, from: data)
    }
}
