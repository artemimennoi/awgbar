import AppKit
import AWGShared
import Foundation
import ServiceManagement

struct HelperFailure: LocalizedError {
    let message: String
    let log: String?
    var code: String?
    var errorDescription: String? { message }
}

@MainActor
final class TunnelStore: ObservableObject {
    enum HelperState: Equatable {
        case checking, missing, outdated, ready
        case unreachable(String)

        var usable: Bool { self == .ready || self == .outdated }
    }

    @Published private(set) var helper: HelperState = .checking
    @Published private(set) var tunnels: [TunnelInfo] = []
    @Published private(set) var stats: TunnelStats?
    /// Networks routed outside the tunnel, as stored by the helper.
    @Published private(set) var excluded: [String] = []
    /// What the user is typing; overwritten only when the helper's list actually changes.
    @Published var excludedDraft = ""
    @Published private(set) var editingExcluded = false
    /// Mirrors the helper's setting; the log itself is root-only, at /var/log/awgbar-helper.log.
    @Published private(set) var debugLog = false
    @Published var language = L10n.language {
        didSet { L10n.language = language }
    }
    @Published private(set) var busy = false
    @Published var error: String? {
        didSet { scheduleErrorDismissal() }
    }
    @Published var errorLog: String?
    private var errorDismissal: Task<Void, Never>?

    /// Errors fade out like a notification banner; a new error restarts the clock.
    private func scheduleErrorDismissal() {
        errorDismissal?.cancel()
        guard let shown = error else { return }
        errorDismissal = Task { [weak self] in
            try? await Task.sleep(for: .seconds(6))
            guard !Task.isCancelled, let self, self.error == shown else { return }
            self.error = nil
            self.errorLog = nil
        }
    }
    @Published var selected: String? {
        didSet { UserDefaults.standard.set(selected, forKey: "selected") }
    }

    var active: TunnelInfo? { tunnels.first { $0.active } }
    var isConnected: Bool { active != nil }

    private var timer: Timer?
    private var refreshing = false
    private var versionCheckPending = false

    init() {
        selected = UserDefaults.standard.string(forKey: "selected")
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            Task { @MainActor in await self?.refresh() }
        }
        Task { await refresh() }
    }

    // MARK: - Helper calls

    private func send(_ request: Request, timeout: Int = 60) async throws -> Response {
        let response = try await Task.detached { try HelperClient.send(request, timeout: timeout) }.value
        guard response.ok else {
            throw HelperFailure(message: response.error ?? L("Helper error"), log: response.log, code: response.code)
        }
        return response
    }

    func refresh() async {
        guard !refreshing, !busy else { return }
        refreshing = true
        defer { refreshing = false }
        do {
            if !helper.usable || versionCheckPending {
                let version = try await send(Request(.version)).version ?? 0
                helper = version < HelperPaths.version ? .outdated : .ready
                versionCheckPending = false
            }
            apply(try await send(Request(.list)))
            if let active {
                stats = try await send(Request(.stats, name: active.name)).stats
            } else {
                stats = nil
            }
        } catch let failure as HelperFailure {
            error = failure.message
        } catch {
            helper = FileManager.default.fileExists(atPath: HelperPaths.helperBinary)
                ? .unreachable(error.localizedDescription) : .missing
            tunnels = []
            stats = nil
        }
    }

    private func apply(_ response: Response) {
        // A v1 helper doesn't report exclusions; keep whatever we had.
        if let excluded = response.excluded, excluded != self.excluded {
            self.excluded = excluded
            excludedDraft = excluded.joined(separator: ", ")
        }
        if let debugLog = response.debugLog { self.debugLog = debugLog }
        if let list = response.tunnels { apply(list) }
    }

    private func apply(_ list: [TunnelInfo]) {
        tunnels = list
        if let active {
            selected = active.name
        } else if selected.map({ name in !list.contains { $0.name == name } }) ?? true {
            selected = list.first?.name
        }
    }

    /// Runs a mutating call with the spinner on and surfaces its error in the window.
    private func perform(_ operation: @escaping () async throws -> Response?) async {
        busy = true
        error = nil
        errorLog = nil
        do {
            if let response = try await operation() { apply(response) }
        } catch let failure as HelperFailure {
            error = failure.message
            errorLog = failure.log
        } catch {
            self.error = error.localizedDescription
        }
        busy = false
        await refresh()
    }

    // MARK: - Actions

    func toggle() {
        Task {
            await perform { [self] in
                if let active { return try await send(Request(.down, name: active.name)) }
                guard let selected else { return nil }
                return try await send(Request(.up, name: selected))
            }
        }
    }

    /// Asks the helper for the current list rather than trusting the last poll, which may be 3 s old.
    /// Best effort with a short timeout: quitting must not hang on a stuck helper.
    func disconnectAll() async {
        busy = true
        let current = (try? await send(Request(.list), timeout: 5).tunnels) ?? tunnels
        for tunnel in current where tunnel.active {
            _ = try? await send(Request(.down, name: tunnel.name), timeout: 15)
        }
        busy = false
    }

    func select(_ name: String) {
        selected = name
        // Picking another tunnel while connected switches to it; the helper drops the old one.
        if let active, active.name != name {
            Task { await perform { [self] in try await send(Request(.up, name: name)) } }
        }
    }

    func delete(_ tunnel: TunnelInfo) {
        let name = tunnel.name
        let alert = NSAlert()
        alert.messageText = L("Delete tunnel %@?", tunnel.displayName)
        alert.informativeText = L("The config will be deleted permanently.")
        alert.addButton(withTitle: L("Delete")).hasDestructiveAction = true
        alert.addButton(withTitle: L("Cancel"))
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        Task { await perform { [self] in try await send(Request(.delete, name: name)) } }
    }

    func beginExcludedEdit() {
        excludedDraft = excluded.joined(separator: ", ")
        editingExcluded = true
    }

    func cancelExcludedEdit() {
        editingExcluded = false
        excludedDraft = excluded.joined(separator: ", ")
    }

    /// Validates locally for an instant error; the helper normalizes and reconnects an active tunnel.
    func saveExcluded() {
        let parsed = parseNetworkList(excludedDraft)
        guard parsed.invalid.isEmpty else {
            error = L("Not a subnet: %@", parsed.invalid.joined(separator: ", "))
            return
        }
        editingExcluded = false
        // Saving an unchanged list would needlessly reconnect the active tunnel.
        guard parsed.networks.map(\.description) != excluded else { return }
        Task {
            await perform { [self] in
                try await send(Request(.setExcluded, excluded: parsed.networks.map(\.description)))
            }
        }
    }

    func rename(_ tunnel: TunnelInfo) {
        let alert = NSAlert()
        alert.messageText = L("Rename Tunnel")
        let field = NSTextField(string: tunnel.displayName)
        field.frame = NSRect(x: 0, y: 0, width: 260, height: 24)
        alert.accessoryView = field
        alert.addButton(withTitle: L("Rename"))
        alert.addButton(withTitle: L("Cancel"))
        alert.window.initialFirstResponder = field
        NSApp.activate(ignoringOtherApps: true)
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        let title = field.stringValue.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty, title != tunnel.displayName else { return }
        Task { await perform { [self] in try await send(Request(.rename, name: tunnel.name, title: title)) } }
    }

    func importFromClipboard() {
        let text = NSPasteboard.general.string(forType: .string) ?? ""
        Task { await importText(text, suggestedName: "awg") }
    }

    func importFromFile() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.message = L("Choose an AmneziaWG .conf or a file with a vpn:// key")
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        Task {
            for url in panel.urls {
                guard let text = try? String(contentsOf: url, encoding: .utf8) else {
                    error = L("Cannot read %@", url.lastPathComponent)
                    continue
                }
                await importText(text, suggestedName: url.deletingPathExtension().lastPathComponent)
            }
        }
    }

    private func importText(_ text: String, suggestedName: String) async {
        await perform { [self] in
            var taken = Set(tunnels.map(\.name))
            var last: Response?
            var duplicate: String?
            // A backup can hold several servers: skip the ones we already have and import the rest.
            for item in try KeyImport.parse(text, suggestedName: suggestedName) {
                let name = KeyImport.tunnelName(from: item.name, taken: taken)
                taken.insert(name)
                do {
                    last = try await send(Request(.importConfig, name: name, config: item.config, title: item.name))
                    selected = name
                } catch let failure as HelperFailure where failure.code?.hasPrefix("duplicate:") == true {
                    duplicate = duplicate ?? String(failure.code!.dropFirst("duplicate:".count))
                }
            }
            if last == nil, let duplicate {
                // Nothing new: point at the copy we already have.
                selected = duplicate
                let title = tunnels.first { $0.name == duplicate }?.displayName ?? duplicate
                throw HelperFailure(message: L("Already added as %@", title), log: nil)
            }
            return last
        }
    }

    func installHelper() {
        Task {
            do {
                guard try await HelperInstaller.install() else { return }
                error = nil
                // Re-check the version without flipping to .checking: swapping the whole
                // window content for a spinner resizes the menu bar window.
                versionCheckPending = true
                // launchd needs a moment to start the daemon and create the socket.
                try? await Task.sleep(for: .seconds(1))
            } catch {
                self.error = error.localizedDescription
            }
            await refresh()
        }
    }

    func setDebugLog(_ enabled: Bool) {
        Task { await perform { [self] in try await send(Request(.setDebugLog, enabled: enabled)) } }
    }

    func copyErrorLog() {
        let text = [error, errorLog].compactMap { $0 }.joined(separator: "\n\n")
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(text, forType: .string)
    }

    // MARK: - Login item

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                self.error = L("Launch at login: %@", error.localizedDescription)
            }
            objectWillChange.send()
        }
    }
}
