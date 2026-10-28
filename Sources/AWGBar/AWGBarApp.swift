import AppKit
import SwiftUI

@main
struct AWGBarApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        MenuBarExtra {
            MenuView()
                .environmentObject(appDelegate.store)
                .environmentObject(appDelegate.updater)
        } label: {
            StatusIcon(store: appDelegate.store)
        }
        .menuBarExtraStyle(.window)
    }
}

private struct StatusIcon: View {
    @ObservedObject var store: TunnelStore

    var body: some View {
        Image(nsImage: Self.images[store.busy ? .busy : store.isConnected ? .on : .off]!)
    }

    private static let images: [StatusGlyph.State: NSImage] = [
        .off: StatusGlyph.image(.off), .busy: StatusGlyph.image(.busy), .on: StatusGlyph.image(.on),
    ]
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    let store = TunnelStore()
    let updater = Updater()

    override init() {
        super.init()
        updater.store = store
    }

    /// The menu bar icon is the only sign the VPN is on, so the tunnel must not outlive the app.
    /// Covers every way out: the Quit item, logout and shutdown. The one exception is relaunching
    /// after an update, when the new copy picks the running tunnel up again.
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        guard store.helper.usable, !updater.relaunching else { return .terminateNow }
        Task {
            await store.disconnectAll()
            sender.reply(toApplicationShouldTerminate: true)
        }
        return .terminateLater
    }
}
