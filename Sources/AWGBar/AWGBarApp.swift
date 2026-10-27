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
        Image(nsImage: Self.symbol(store.busy ? "shield.lefthalf.filled" : store.isConnected ? "checkmark.shield.fill" : "shield"))
    }

    /// Menu bar extras draw SF Symbols noticeably smaller than the system's own icons, and the
    /// label ignores font modifiers, so render the symbol at a fixed size up front.
    static let pointSize: CGFloat = 17

    private static func symbol(_ name: String) -> NSImage {
        let image = NSImage(systemSymbolName: name, accessibilityDescription: "awgbar")?
            .withSymbolConfiguration(.init(pointSize: pointSize, weight: .regular)) ?? NSImage()
        image.isTemplate = true
        return image
    }
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
