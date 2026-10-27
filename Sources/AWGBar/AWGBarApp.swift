import AppKit
import SwiftUI

@main
struct AWGBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            MenuView().environmentObject(appDelegate.store)
        } label: {
            StatusIcon(store: appDelegate.store)
        }
        .menuBarExtraStyle(.window)
    }
}

private struct StatusIcon: View {
    @ObservedObject var store: TunnelStore

    var body: some View {
        Image(systemName: store.busy ? "shield.lefthalf.filled" : store.isConnected ? "checkmark.shield.fill" : "shield")
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let store = TunnelStore()

    /// The menu bar icon is the only sign the VPN is on, so the tunnel must not outlive the app.
    /// Covers every way out: the Quit item, logout and shutdown.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard store.helper.usable else { return .terminateNow }
        Task {
            await store.disconnectAll()
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
