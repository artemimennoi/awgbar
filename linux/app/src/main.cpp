// AWG Bar for Linux: a tray icon and a small window in front of awgbar-helper, the root service
// that runs the tunnel. The app holds no privileges and no tunnel state of its own.
#include "autostart.h"
#include "glyph.h"
#include "l10n.h"
#include "store.h"
#include "tray.h"
#include "window.h"

#include <QAction>
#include <QActionGroup>
#include <QApplication>
#include <QFile>
#include <QLocalServer>
#include <QLocalSocket>
#include <QMenu>
#include <QSocketNotifier>
#include <QStandardPaths>
#include <QTimer>

#include <csignal>
#include <sys/socket.h>
#include <unistd.h>

// One instance per user: starting the app again (from the menu, or autostart after a manual
// start) shows the running one's window.
static QString instanceSocket()
{
    QString dir = QStandardPaths::writableLocation(QStandardPaths::RuntimeLocation);
    if (dir.isEmpty())
        dir = QStandardPaths::writableLocation(QStandardPaths::TempLocation);
    return dir + QStringLiteral("/awgbar-app.sock");
}

// SIGTERM (logging out, systemd stopping the session) quits through the event loop, so the VPN is
// disconnected as on an ordinary quit. The handler only writes a byte; Qt reads it.
static int signalFds[2];

static void quitOnSignals(QApplication *app)
{
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, signalFds) != 0)
        return;
    auto *notifier = new QSocketNotifier(signalFds[1], QSocketNotifier::Read, app);
    QObject::connect(notifier, &QSocketNotifier::activated, app, [notifier] {
        notifier->setEnabled(false);
        char c;
        if (::read(signalFds[1], &c, 1) < 0) {
        }
        QApplication::quit();
    });
    struct sigaction sa = {};
    sa.sa_handler = [](int) {
        if (::write(signalFds[0], "x", 1) < 0) {
        }
    };
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = SA_RESTART;
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);
    sigaction(SIGHUP, &sa, nullptr);
}

static QMenu *settingsMenu(Store *store, QApplication *app)
{
    auto *menu = new QMenu;
    auto *launch = menu->addAction(QString());
    launch->setCheckable(true);
    QObject::connect(launch, &QAction::toggled, store, [store](bool on) {
        if (on == Autostart::isEnabled())
            return;
        const QString error = Autostart::setEnabled(on);
        if (!error.isEmpty())
            store->setError(L("Launch at login: %1").arg(error));
    });

    auto *language = menu->addMenu(QString());
    auto *languages = new QActionGroup(language);
    // Always shown in their own language so they can be found whatever is selected.
    auto *english = language->addAction(QStringLiteral("English"));
    auto *russian = language->addAction(QStringLiteral("Русский"));
    for (QAction *a : {english, russian}) {
        a->setCheckable(true);
        languages->addAction(a);
    }
    QObject::connect(english, &QAction::triggered, store, [store] {
        L10n::setLanguage(L10n::Language::English);
        emit store->changed();
    });
    QObject::connect(russian, &QAction::triggered, store, [store] {
        L10n::setLanguage(L10n::Language::Russian);
        emit store->changed();
    });

    auto *debug = menu->addAction(QString());
    debug->setCheckable(true);
    QObject::connect(debug, &QAction::triggered, store, [store](bool on) { store->setDebugLog(on); });

    menu->addSeparator();
    auto *quit = menu->addAction(QIcon::fromTheme(QStringLiteral("application-exit")), QString(), app,
                                 &QApplication::quit);

    // Texts and checkmarks are refreshed each time the menu opens.
    QObject::connect(menu, &QMenu::aboutToShow, menu, [=] {
        launch->setText(L("Launch at Login"));
        {
            const QSignalBlocker block(launch);
            launch->setChecked(Autostart::isEnabled());
        }
        language->setTitle(L("Language"));
        english->setChecked(L10n::language() == L10n::Language::English);
        russian->setChecked(L10n::language() == L10n::Language::Russian);
        debug->setText(L("Debug Log"));
        debug->setChecked(store->debugLog());
        quit->setText(store->active() ? L("Disconnect VPN and Quit") : L("Quit"));
    });
    return menu;
}

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    QApplication::setApplicationName(QStringLiteral("awgbar"));
    QApplication::setOrganizationName(QStringLiteral("awgbar"));
    QApplication::setApplicationDisplayName(QStringLiteral("AWG Bar"));
    QApplication::setApplicationVersion(QStringLiteral(AWGBAR_VERSION));
    QApplication::setDesktopFileName(QStringLiteral("awgbar"));
    QApplication::setWindowIcon(Glyph::app());
    // Closing the window leaves the app in the tray.
    QApplication::setQuitOnLastWindowClosed(false);

    const QStringList args = QApplication::arguments();
    // Build step: write the tray icons for the icon theme (see linux/packaging).
    const int exportIcons = args.indexOf(QStringLiteral("--export-icons"));
    if (exportIcons >= 0 && exportIcons + 1 < args.size()) {
        const QString dir = args.at(exportIcons + 1);
        const QPair<Glyph::State, const char *> icons[] = {
            {Glyph::State::Off, "awgbar-off-symbolic"}, {Glyph::State::Busy, "awgbar-busy-symbolic"},
            {Glyph::State::On, "awgbar-on-symbolic"}};
        for (const auto &icon : icons) {
            QFile file(dir + QLatin1Char('/') + QLatin1String(icon.second) + QStringLiteral(".svg"));
            if (!file.open(QIODevice::WriteOnly) || file.write(Glyph::symbolicSvg(icon.first)) < 0)
                return 1;
        }
        return 0;
    }
    const bool background = args.contains(QStringLiteral("--background"));

    QLocalSocket other;
    other.connectToServer(instanceSocket());
    if (other.waitForConnected(500)) {
        if (!background)
            other.write("show\n");
        other.waitForBytesWritten(500);
        return 0;
    }
    QLocalServer::removeServer(instanceSocket());
    QLocalServer instance;
    instance.listen(instanceSocket());

    Store store;
    QMenu *menu = settingsMenu(&store, &app);
    Window window(&store, menu);
    Tray tray(&store, &window);

    QObject::connect(&instance, &QLocalServer::newConnection, &window, [&] {
        QLocalSocket *c = instance.nextPendingConnection();
        QObject::connect(c, &QLocalSocket::readyRead, &window, [&window, c] {
            if (c->readAll().startsWith("show"))
                window.showAndRaise();
            c->deleteLater();
        });
    });
    QObject::connect(&tray, &Tray::quitRequested, &app, &QApplication::quit);
    // Quitting disconnects, as on macOS; so does logging out.
    const int shot = args.indexOf(QStringLiteral("--screenshot"));
    if (shot < 0)
        QObject::connect(&app, &QApplication::aboutToQuit, &store, [&store] { store.disconnectAllBlocking(); });
    quitOnSignals(&app);

    // Started at login the app lives in the tray; without a tray there is nowhere else to be.
    if (!background || !tray.available())
        window.showAndRaise();

    // Debugging aid: render the window to an image (works with QT_QPA_PLATFORM=offscreen).
    if (shot >= 0 && shot + 1 < args.size()) {
        const QString path = args.at(shot + 1);
        QTimer::singleShot(1500, &app, [&window, path] {
            window.resize(window.width(), window.sizeHint().height());
            window.grab().save(path);
            QCoreApplication::exit(0);
        });
    }
    return app.exec();
}
