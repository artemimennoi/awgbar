import AWGShared
import SwiftUI

// All user-facing strings go through L(): a bare literal in Text/Button would be looked up by
// SwiftUI in the system language instead of the language chosen in the app.
struct MenuView: View {
    @EnvironmentObject private var store: TunnelStore
    @FocusState private var excludedFieldFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            switch store.helper {
            case .checking:
                ProgressView().frame(maxWidth: .infinity)
            case .missing, .unreachable:
                helperCard
            case .ready, .outdated:
                if store.tunnels.isEmpty {
                    emptyState
                } else {
                    powerButton
                    tunnelList
                    splitTunnel
                }
            }
            infoRow
            Divider()
            footer
        }
        .padding(16)
        .frame(width: 300)
        .onDisappear { store.cancelExcludedEdit() }
    }

    // MARK: - Sections

    private var header: some View {
        HStack {
            Text(verbatim: "awgbar").font(.headline)
            Spacer()
            HStack(spacing: 6) {
                Circle().fill(statusColor).frame(width: 8, height: 8)
                Text(statusText).font(.subheadline).foregroundStyle(.secondary)
            }
        }
    }

    private var statusText: String {
        if store.busy { return L("Please wait…") }
        if let active = store.active { return L("Connected · %@", active.displayName) }
        return L("Disconnected")
    }

    private var statusColor: Color {
        store.busy ? .orange : store.isConnected ? .green : .secondary.opacity(0.5)
    }

    private var powerButton: some View {
        Button(action: store.toggle) {
            ZStack {
                Circle()
                    .fill(store.isConnected ? Color.green : Color.secondary.opacity(0.15))
                    .frame(width: 88, height: 88)
                if store.busy {
                    ProgressView().controlSize(.large)
                } else {
                    Image(systemName: "power")
                        .font(.system(size: 34, weight: .semibold))
                        .foregroundStyle(store.isConnected ? .white : .primary)
                }
            }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(store.busy || store.selected == nil)
        .frame(maxWidth: .infinity)
        .help(store.isConnected ? L("Disconnect") : L("Connect"))
    }

    private var tunnelList: some View {
        VStack(spacing: 2) {
            ForEach(store.tunnels) { tunnel in
                TunnelRow(tunnel: tunnel, selected: tunnel.name == store.selected)
                    .onTapGesture { store.select(tunnel.name) }
                    .contextMenu {
                        Button(L("Rename…")) { store.rename(tunnel) }
                        Button(L("Delete…"), role: .destructive) { store.delete(tunnel) }
                    }
            }
        }
        .disabled(store.busy)
    }

    // Everything below keeps a constant height: the menu bar window on recent macOS leaves its old
    // glass background on screen when its content grows or shrinks, so nothing appears or collapses.

    /// Shows the current list; the text field exists only while editing, so it can't take
    /// keyboard focus every time the window opens.
    private var splitTunnel: some View {
        HStack(spacing: 8) {
            Text(L("Bypass VPN")).font(.subheadline)
            if store.editingExcluded {
                TextField(text: $store.excludedDraft, prompt: Text(verbatim: "10.*, 192.168.0.0/16")) { EmptyView() }
                    .textFieldStyle(.roundedBorder)
                    .font(.caption)
                    .focused($excludedFieldFocused)
                    .onAppear { excludedFieldFocused = true }
                    .onSubmit(store.saveExcluded)
                    .onExitCommand(perform: store.cancelExcludedEdit)
                    .help(L("Subnets separated by commas. Enter applies, Esc cancels; an active tunnel reconnects."))
                Button(action: store.saveExcluded) { Image(systemName: "checkmark") }
                    .buttonStyle(.borderless)
                    .help(L("Apply"))
            } else {
                Button(action: store.beginExcludedEdit) {
                    HStack {
                        Text(store.excluded.isEmpty ? L("none") : store.excluded.joined(separator: ", "))
                            .foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
                        Spacer(minLength: 0)
                        Image(systemName: "pencil").foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .font(.caption)
                .help(L("Subnets that go around the VPN"))
            }
        }
        .frame(height: 22)
        .disabled(store.busy || store.helper == .outdated)
    }

    /// One fixed-height line for whatever matters most right now: an error, an outdated helper, or traffic.
    private var infoRow: some View {
        HStack(spacing: 8) {
            if let error = store.error {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red).lineLimit(1).truncationMode(.tail)
                    .help(error)
                Spacer(minLength: 0)
                if store.errorLog != nil {
                    Button(L("Log"), action: store.copyErrorLog).buttonStyle(.link)
                        .help(L("Copy the error and the awg-quick log to the clipboard"))
                }
                Button { store.error = nil } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
            } else if store.helper == .outdated {
                Label(L("Helper is outdated"), systemImage: "exclamationmark.circle").foregroundStyle(.orange)
                Spacer(minLength: 0)
                Button(L("Update"), action: store.installHelper).buttonStyle(.link)
            } else if let stats = store.stats, store.isConnected {
                StatsView(stats: stats)
            } else if let endpoint = store.tunnels.first(where: { $0.name == store.selected })?.endpoint {
                // Same spot as the connected IP, dimmed: where the power button will connect to.
                Text(endpointHost(endpoint)).foregroundStyle(.tertiary).textSelection(.enabled)
                Spacer(minLength: 0)
            } else {
                Spacer(minLength: 0)
            }
        }
        .font(.caption)
        .frame(height: 16)
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray").font(.largeTitle).foregroundStyle(.secondary)
            Text(L("No tunnels")).font(.subheadline)
            Text(L("Copy a vpn://… key or choose a .conf file"))
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            HStack {
                Button(L("From Clipboard"), action: store.importFromClipboard)
                Button(L("From File…"), action: store.importFromFile)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var helperCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("Helper required")).font(.subheadline.bold())
            Text(L("Bringing up the tunnel, routes and DNS needs root. The helper is installed once; macOS will ask for your password."))
                .font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            if case .unreachable(let reason) = store.helper {
                Text(L("The helper is installed but not responding: %@", reason)).font(.caption).foregroundStyle(.orange)
            }
            Button(L("Install Helper"), action: store.installHelper).buttonStyle(.borderedProminent)
        }
    }

    private var footer: some View {
        HStack {
            Menu {
                Button(L("From Clipboard"), action: store.importFromClipboard)
                Button(L("From File…"), action: store.importFromFile)
            } label: {
                Image(systemName: "plus")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
            .disabled(!store.helper.usable || store.busy)
            .help(L("Add Tunnel"))

            Spacer()

            Menu {
                Toggle(L("Launch at Login"), isOn: Binding(get: { store.launchAtLogin }, set: { store.launchAtLogin = $0 }))
                Picker(L("Language"), selection: Binding(get: { store.language }, set: { store.language = $0 })) {
                    ForEach(AppLanguage.allCases) { Text($0.title).tag($0) }
                }
                Toggle(L("Debug Log"), isOn: Binding(get: { store.debugLog }, set: { store.setDebugLog($0) }))
                    .disabled(store.helper != .ready)
                Button(store.helper == .outdated ? L("Update Helper…") : L("Reinstall Helper…"), action: store.installHelper)
                Divider()
                // AppDelegate brings the tunnel down before the app exits.
                Button(store.isConnected ? L("Disconnect VPN and Quit") : L("Quit")) { NSApp.terminate(nil) }
            } label: {
                Image(systemName: store.helper == .outdated ? "gearshape.badge.exclamationmark" : "gearshape")
            }
            .menuStyle(.borderlessButton)
            .menuIndicator(.hidden)
            .fixedSize()
        }
    }
}

private struct TunnelRow: View {
    let tunnel: TunnelInfo
    let selected: Bool

    var body: some View {
        HStack {
            Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                .foregroundStyle(selected ? Color.accentColor : .secondary)
            Text(tunnel.displayName).lineLimit(1).truncationMode(.tail)
            Spacer()
            if tunnel.active {
                Image(systemName: "checkmark.shield.fill").foregroundStyle(.green)
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(selected ? Color.secondary.opacity(0.12) : .clear, in: RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())
    }
}

private struct StatsView: View {
    let stats: TunnelStats

    var body: some View {
        HStack(spacing: 10) {
            if let host = stats.endpoint.map(endpointHost) {
                Text(host).foregroundStyle(.secondary).textSelection(.enabled)
            }
            Spacer(minLength: 0)
            Text(verbatim: "↓ \(bytes(stats.rxBytes))")
            Text(verbatim: "↑ \(bytes(stats.txBytes))")
        }
        .font(.caption)
        .monospacedDigit()
        .lineLimit(1)
    }

    private func bytes(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .binary)
    }
}

/// "1.2.3.4:51820" → "1.2.3.4", "[2001:db8::1]:51820" → "2001:db8::1".
private func endpointHost(_ endpoint: String) -> String {
    guard let colon = endpoint.lastIndex(of: ":") else { return endpoint }
    return String(endpoint[..<colon]).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
}
