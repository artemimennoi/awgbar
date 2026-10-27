// Root LaunchDaemon: owns the tunnel configs and runs awg-quick on behalf of the menu bar app.
// It accepts connections only from root and from the uid passed with --uid at install time.
// Configs are reduced to an allowlist of keys (see TunnelConfig) every time before awg-quick
// sees them, so no config can make it run commands as root.
import AWGShared
import Darwin
import Foundation

signal(SIGPIPE, SIG_IGN)
setvbuf(stdout, nil, _IOLBF, 0)

let fm = FileManager.default
let runDir = "/var/run/amneziawg"
let logPath = "/var/log/awgbar-helper.log"

let allowedUID: uid_t = {
    let args = CommandLine.arguments
    if let i = args.firstIndex(of: "--uid"), i + 1 < args.count, let uid = UInt32(args[i + 1]) { return uid }
    return getuid()
}()

struct HelperError: LocalizedError {
    let message: String
    var log: String?
    /// Machine-readable reason for errors the app shows in its own language (see Response.code).
    var code: String?
    init(_ message: String, log: String? = nil, code: String? = nil) {
        self.message = message
        self.log = log
        self.code = code
    }
    var errorDescription: String? { message }
}

// MARK: - Debug log

/// Off unless the user turns it on in the app: the log would record servers, routes and DNS.
var debugLog: Bool { fm.fileExists(atPath: HelperPaths.debugLogFlag) }

func log(_ message: String) {
    guard debugLog else { return }
    if !fm.fileExists(atPath: logPath) {
        fm.createFile(atPath: logPath, contents: nil, attributes: [.posixPermissions: 0o600])
    }
    guard let handle = FileHandle(forWritingAtPath: logPath) else { return }
    defer { try? handle.close() }
    handle.seekToEndOfFile()
    handle.write(Data("[\(Date())] \(message)\n".utf8))
}

func setDebugLog(_ enabled: Bool) {
    if enabled {
        fm.createFile(atPath: HelperPaths.debugLogFlag, contents: nil, attributes: [.posixPermissions: 0o600])
        log("debug log enabled")
    } else {
        try? fm.removeItem(atPath: HelperPaths.debugLogFlag)
        try? fm.removeItem(atPath: logPath)
    }
}

// MARK: - Processes

/// Output goes to a file rather than a pipe: awg-quick leaves a background route monitor
/// and amneziawg-go running, and either could keep a pipe open forever.
func run(_ executable: String, _ args: [String], environment: [String: String] = [:], timeout: TimeInterval = 30) -> (status: Int32, output: String) {
    let outputURL = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("awgbar-\(UUID().uuidString).out")
    fm.createFile(atPath: outputURL.path, contents: nil, attributes: [.posixPermissions: 0o600])
    defer { try? fm.removeItem(at: outputURL) }
    guard let handle = try? FileHandle(forWritingTo: outputURL) else { return (-1, "cannot create output file") }

    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = args
    process.environment = ["PATH": "\(HelperPaths.bin):/usr/bin:/bin:/usr/sbin:/sbin", "LC_ALL": "C"]
        .merging(environment) { $1 }
    process.standardInput = FileHandle.nullDevice
    process.standardOutput = handle
    process.standardError = handle

    let done = DispatchSemaphore(value: 0)
    process.terminationHandler = { _ in done.signal() }
    do {
        try process.run()
    } catch {
        return (-1, "\(error)")
    }
    if done.wait(timeout: .now() + timeout) == .timedOut {
        process.terminate()
        done.wait()
    }
    try? handle.close()
    let output = (try? String(contentsOf: outputURL, encoding: .utf8)) ?? ""
    return (process.terminationStatus, output)
}

func awgQuick(_ action: String, _ path: String) -> (status: Int32, output: String) {
    // Exclusions ride awg-quick's endpoint routes (see Resources/awg-quick-darwin.patch);
    // the route monitor inherits this variable and keeps them on the current gateway.
    let excluded = loadExcluded().map(\.description).joined(separator: " ")
    return run("\(HelperPaths.bin)/bash", ["\(HelperPaths.bin)/awg-quick", action, path],
               environment: ["AWGBAR_EXCLUDED": excluded])
}

func wgShow(_ iface: String, _ field: String) -> [[String]] {
    let (status, output) = run("\(HelperPaths.bin)/wg", ["show", iface, field], timeout: 5)
    guard status == 0 else { return [] }
    return output.split(separator: "\n").map { $0.split(separator: "\t").map(String.init) }
}

/// awg-quick's route monitor is a forked subshell with the same command line as `awg-quick up <path>`.
func monitorPIDs(_ path: String) -> [pid_t] {
    let pattern = NSRegularExpression.escapedPattern(for: "awg-quick up \(path)")
    let (status, output) = run("/usr/bin/pgrep", ["-f", pattern], timeout: 5)
    guard status == 0 else { return [] }
    return output.split(separator: "\n").compactMap { pid_t($0.trimmingCharacters(in: .whitespaces)) }
}

/// After `down` the monitor notices the interface is gone and only then deletes the endpoint and
/// exclusion routes and restores DNS. Waiting for it keeps a quick reconnect from racing that
/// cleanup: the new tunnel would otherwise save the VPN's DNS as the "original" one.
func waitForMonitorExit(_ path: String) {
    for attempt in 0..<40 {
        let pids = monitorPIDs(path)
        if pids.isEmpty { return }
        // The monitor's TERM trap runs the same cleanup, so nudge it if it hasn't noticed by now.
        if attempt == 20 { pids.forEach { kill($0, SIGTERM) } }
        usleep(100_000)
    }
    log("route monitor for \(path) did not exit")
}

// MARK: - Tunnels

func confPath(_ name: String) -> String { "\(HelperPaths.tunnels)/\(name).conf" }
/// What awg-quick actually gets; same file name, because awg-quick derives the interface name from it.
func runtimePath(_ name: String) -> String { "\(HelperPaths.runtime)/\(name).conf" }
func ipv6BlockMarker(_ name: String) -> String { "\(HelperPaths.runtime)/\(name).v6block" }

/// The utun interface awg-quick created for this tunnel, if it is still up.
func realInterface(_ name: String) -> String? {
    guard let raw = try? String(contentsOfFile: "\(runDir)/\(name).name", encoding: .utf8) else { return nil }
    let utun = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !utun.isEmpty, fm.fileExists(atPath: "\(runDir)/\(utun).sock"), if_nametoindex(utun) != 0 else { return nil }
    return utun
}

func loadConfig(_ name: String) throws -> TunnelConfig {
    try TunnelConfig(parsing: String(contentsOfFile: confPath(name), encoding: .utf8))
}

func writeSecret(_ path: String, _ text: String) throws {
    guard fm.createFile(atPath: path, contents: Data(text.utf8), attributes: [.posixPermissions: 0o600]) else {
        throw HelperError("Cannot write \(path)")
    }
}

func listTunnels() -> [TunnelInfo] {
    let files = (try? fm.contentsOfDirectory(atPath: HelperPaths.tunnels)) ?? []
    return files
        .filter { $0.hasSuffix(".conf") }
        .map { String($0.dropLast(5)) }
        .filter(isValidTunnelName)
        .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
        .map { name in
            let config = try? loadConfig(name)
            return TunnelInfo(name: name, active: realInterface(name) != nil, endpoint: config?.endpoint, title: config?.title)
        }
}

// MARK: - Split tunnel

func loadExcluded() -> [CIDR] {
    let text = (try? String(contentsOfFile: HelperPaths.excluded, encoding: .utf8)) ?? ""
    return parseNetworkList(text).networks
}

// MARK: - IPv6 leak protection

let ipv6Halves = ["::/1", "8000::/1"]

/// Blackholes IPv6 while a tunnel that should carry it can't (no IPv6 address on the interface).
/// Excluded IPv6 networks still work: their routes via the gateway are more specific.
func blockIPv6(_ name: String) {
    for net in ipv6Halves {
        let (status, output) = run("/sbin/route", ["-q", "-n", "add", "-inet6", net, "::1", "-blackhole"], timeout: 5)
        log("block IPv6 \(net): \(status) \(output)")
    }
    fm.createFile(atPath: ipv6BlockMarker(name), contents: nil, attributes: [.posixPermissions: 0o600])
}

func unblockIPv6(_ name: String) {
    guard fm.fileExists(atPath: ipv6BlockMarker(name)) else { return }
    for net in ipv6Halves {
        _ = run("/sbin/route", ["-q", "-n", "delete", "-inet6", net, "::1"], timeout: 5)
    }
    try? fm.removeItem(atPath: ipv6BlockMarker(name))
}

// MARK: - Up / down

func up(_ name: String) throws {
    let config = try loadConfig(name)
    if !config.dropped.isEmpty { log("\(name): ignored keys \(config.dropped.joined(separator: ", "))") }
    let path = runtimePath(name)
    try writeSecret(path, config.render(includeTitle: false))
    // A monitor left over from a previous session with this tunnel would undo our routes on exit.
    waitForMonitorExit(path)

    let (status, output) = awgQuick("up", path)
    log("up \(name): \(status)\n\(output)")
    guard status == 0 else {
        try? fm.removeItem(atPath: path)
        throw HelperError("awg-quick up failed (\(status))", log: output)
    }
    if config.leaksIPv6 { blockIPv6(name) }
}

func down(_ name: String) throws {
    let runtime = runtimePath(name)
    // Tunnels brought up by helper v3 and older ran straight from the stored config.
    let path = fm.fileExists(atPath: runtime) ? runtime : confPath(name)
    let (status, output) = awgQuick("down", path)
    log("down \(name): \(status)\n\(output)")
    waitForMonitorExit(path)
    unblockIPv6(name)
    try? fm.removeItem(atPath: runtime)
    guard status == 0 else { throw HelperError("awg-quick down failed (\(status))", log: output) }
}

/// After a reboot or a crash nothing is up, but markers and runtime configs may be left behind.
func cleanUpStaleState() {
    let files = (try? fm.contentsOfDirectory(atPath: HelperPaths.runtime)) ?? []
    for file in files {
        let name = (file as NSString).deletingPathExtension
        guard realInterface(name) == nil else { continue }
        unblockIPv6(name)
        try? fm.removeItem(atPath: "\(HelperPaths.runtime)/\(file)")
    }
}

// MARK: - Requests

func listResponse() -> Response {
    Response(tunnels: listTunnels(), excluded: loadExcluded().map(\.description), debugLog: debugLog)
}

func requireName(_ request: Request, mustExist: Bool = true) throws -> String {
    guard let name = request.name, isValidTunnelName(name) else { throw HelperError("Invalid tunnel name") }
    if mustExist, !fm.fileExists(atPath: confPath(name)) { throw HelperError("Tunnel \(name) not found") }
    return name
}

func stats(_ name: String) -> TunnelStats? {
    guard let iface = realInterface(name) else { return nil }
    let endpoint = wgShow(iface, "endpoints").first.flatMap { $0.count > 1 && $0[1] != "(none)" ? $0[1] : nil }
    let handshake = wgShow(iface, "latest-handshakes").compactMap { $0.count > 1 ? TimeInterval($0[1]) : nil }.max()
    var rx: Int64 = 0
    var tx: Int64 = 0
    for row in wgShow(iface, "transfer") where row.count > 2 {
        rx += Int64(row[1]) ?? 0
        tx += Int64(row[2]) ?? 0
    }
    return TunnelStats(endpoint: endpoint, lastHandshake: handshake.flatMap { $0 > 0 ? $0 : nil }, rxBytes: rx, txBytes: tx)
}

func handle(_ request: Request) throws -> Response {
    switch request.command {
    case .version:
        return Response(version: HelperPaths.version)

    case .list:
        return listResponse()

    case .stats:
        return Response(stats: stats(try requireName(request)))

    case .importConfig:
        let name = try requireName(request, mustExist: false)
        var config = try TunnelConfig(parsing: request.config ?? "")
        if let existing = listTunnels().first(where: { $0.name != name && (try? loadConfig($0.name))?.identity == config.identity }) {
            throw HelperError("Already added as \(existing.displayName)", code: "duplicate:\(existing.name)")
        }
        config.title = TunnelConfig.cleanTitle(request.title)
        try writeSecret(confPath(name), config.render(includeTitle: true))
        log("imported \(name)" + (config.dropped.isEmpty ? "" : ", ignored keys \(config.dropped.joined(separator: ", "))"))
        return listResponse()

    case .rename:
        let name = try requireName(request)
        guard let title = TunnelConfig.cleanTitle(request.title) else { throw HelperError("Empty name") }
        var config = try loadConfig(name)
        config.title = title
        try writeSecret(confPath(name), config.render(includeTitle: true))
        log("renamed \(name)")
        return listResponse()

    case .delete:
        let name = try requireName(request)
        if realInterface(name) != nil { try? down(name) }
        try fm.removeItem(atPath: confPath(name))
        log("deleted \(name)")
        return listResponse()

    case .up:
        let name = try requireName(request)
        // One tunnel at a time: both would fight over the default route and DNS.
        for other in listTunnels() where other.active && other.name != name {
            try? down(other.name)
        }
        if realInterface(name) == nil { try up(name) }
        return listResponse()

    case .down:
        let name = try requireName(request)
        if realInterface(name) != nil { try down(name) }
        return listResponse()

    case .setExcluded:
        let (networks, invalid) = parseNetworkList((request.excluded ?? []).joined(separator: "\n"))
        guard invalid.isEmpty else { throw HelperError("Not a network: \(invalid.joined(separator: ", "))") }
        let text = networks.map(\.description).joined(separator: "\n")
        try (text.isEmpty ? "" : text + "\n").write(toFile: HelperPaths.excluded, atomically: true, encoding: .utf8)
        log("excluded: \(networks.map(\.description).joined(separator: ", "))")
        // The route monitor captured the old list at up time, so an active tunnel has to reconnect.
        for tunnel in listTunnels() where tunnel.active {
            try down(tunnel.name)
            try up(tunnel.name)
        }
        return listResponse()

    case .setDebugLog:
        setDebugLog(request.enabled ?? false)
        return listResponse()
    }
}

// MARK: - Server

func serveConnection(_ client: Int32) {
    UnixSocket.configure(client, timeout: 10)
    var uid: uid_t = 0
    var gid: gid_t = 0
    let response: Response
    if getpeereid(client, &uid, &gid) != 0 || (uid != 0 && uid != allowedUID) {
        response = .failure("Permission denied for uid \(uid)")
    } else {
        do {
            let request = try JSONDecoder().decode(Request.self, from: UnixSocket.readAll(client))
            response = try handle(request)
        } catch let error as HelperError {
            var failure = Response.failure(error.message, log: error.log)
            failure.code = error.code
            response = failure
        } catch {
            response = .failure(error.localizedDescription)
        }
    }
    UnixSocket.writeAll(client, (try? JSONEncoder().encode(response)) ?? Data())
}

func serve() -> Never {
    for dir in [HelperPaths.tunnels, HelperPaths.runtime] {
        try? fm.createDirectory(atPath: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
    }
    cleanUpStaleState()

    let path = HelperPaths.socket
    unlink(path)
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0, UnixSocket.withAddress(path, { bind(fd, $0, $1) }) == 0, listen(fd, 16) == 0 else {
        log("cannot listen on \(path): \(String(cString: strerror(errno)))")
        exit(1)
    }
    // Access control is the peer uid check above; the socket itself must be connectable by the user.
    chmod(path, 0o666)
    log("listening on \(path), allowed uid \(allowedUID)")

    // Requests are handled one by one on purpose: up/down/delete must not interleave.
    while true {
        let client = accept(fd, nil, nil)
        if client < 0 { continue }
        serveConnection(client)
        close(client)
    }
}

// Debugging aid: print the canonical config awg-quick gets for a tunnel.
if let i = CommandLine.arguments.firstIndex(of: "--print-config"), i + 1 < CommandLine.arguments.count {
    do {
        let config = try loadConfig(CommandLine.arguments[i + 1])
        print(config.render(includeTitle: false), terminator: "")
        if !config.dropped.isEmpty { print("# ignored: \(config.dropped.joined(separator: ", "))") }
        if config.leaksIPv6 { print("# IPv6 will be blocked: ::/0 without an IPv6 address") }
        let excluded = loadExcluded()
        if !excluded.isEmpty { print("# routed outside the tunnel: \(excluded.map(\.description).joined(separator: " "))") }
        exit(0)
    } catch {
        print("error: \(error.localizedDescription)")
        exit(1)
    }
}

serve()
