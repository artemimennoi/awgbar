#include "tray.h"
#include "glyph.h"
#include "l10n.h"
#include "store.h"
#include "window.h"

#include <QAction>
#include <QActionGroup>
#include <QApplication>
#include <QMenu>
#include <QPalette>
#include <QSystemTrayIcon>

Tray::Tray(Store *store, Window *window, QObject *parent) : QObject(parent), m_store(store), m_window(window)
{
    m_menu = new QMenu;
    m_status = m_menu->addAction(QString());
    m_status->setEnabled(false);
    m_traffic = m_menu->addAction(QString());
    m_traffic->setEnabled(false);
    m_tunnelsSeparator = m_menu->addSeparator();
    m_tunnels = new QActionGroup(this);
    m_tunnels->setExclusive(true);
    m_menu->addSeparator();
    m_toggle = m_menu->addAction(QString(), store, &Store::toggle);
    m_open = m_menu->addAction(Glyph::app(), QString(), window, &Window::showAndRaise);
    m_menu->addSeparator();
    m_quit = m_menu->addAction(QIcon::fromTheme(QStringLiteral("application-exit")), QString(), this,
                               &Tray::quitRequested);

    m_icon = new QSystemTrayIcon(this);
    m_icon->setContextMenu(m_menu);
    connect(m_icon, &QSystemTrayIcon::activated, this, [this](QSystemTrayIcon::ActivationReason reason) {
        // A click opens or hides the window; the menu is on the other button.
        if (reason == QSystemTrayIcon::Trigger) {
            if (m_window->isVisible() && m_window->isActiveWindow())
                m_window->hide();
            else
                m_window->showAndRaise();
        }
    });
    connect(store, &Store::changed, this, &Tray::rebuild);
    rebuild();
    m_icon->show();
}

bool Tray::available() const { return QSystemTrayIcon::isSystemTrayAvailable(); }

void Tray::rebuild()
{
    const Tunnel *active = m_store->active();
    const bool busy = m_store->busy();
    const Glyph::State state = busy ? Glyph::State::Busy : active ? Glyph::State::On : Glyph::State::Off;
    if (int(state) != m_iconState) {
        m_iconState = int(state);
        // Only used when the theme icons aren't installed (running from the build directory).
        m_icon->setIcon(Glyph::tray(state, QApplication::palette().color(QPalette::WindowText)));
    }

    const QString status = busy ? L("Please wait…")
                           : active ? L("Connected · %1").arg(active->displayName())
                                    : L("Disconnected");
    m_status->setText(status);
    m_icon->setToolTip(QStringLiteral("AWG Bar — ") + status);

    if (active && m_store->stats()) {
        auto mb = [](qint64 n) { return QString::number(double(n) / 1e6, 'f', n >= 1e8 ? 0 : 1); };
        m_traffic->setText(QStringLiteral("↓ %1 MB  ↑ %2 MB").arg(mb(m_store->stats()->rxBytes), mb(m_store->stats()->txBytes)));
        if (L10n::language() == L10n::Language::Russian)
            m_traffic->setText(m_traffic->text().replace(QStringLiteral("MB"), QStringLiteral("МБ")));
        m_traffic->setVisible(true);
    } else {
        m_traffic->setVisible(false);
    }

    QString signature = m_store->selected();
    for (const Tunnel &t : m_store->tunnels())
        signature += QLatin1Char('\n') + t.name + QLatin1Char('\t') + t.displayName();
    if (signature != m_tunnelSignature) {
        m_tunnelSignature = signature;
        for (QAction *a : m_tunnels->actions()) {
            m_tunnels->removeAction(a);
            m_menu->removeAction(a);
            delete a;
        }
        QAction *before = m_menu->actions().value(m_menu->actions().indexOf(m_tunnelsSeparator) + 1);
        for (const Tunnel &t : m_store->tunnels()) {
            auto *a = new QAction(t.displayName(), m_tunnels);
            a->setCheckable(true);
            a->setChecked(t.name == m_store->selected());
            const QString name = t.name;
            connect(a, &QAction::triggered, m_store, [this, name] { m_store->select(name); });
            m_menu->insertAction(before, a);
        }
    }
    m_tunnelsSeparator->setVisible(!m_store->tunnels().isEmpty());
    for (QAction *a : m_tunnels->actions())
        a->setEnabled(!busy);

    const bool usable = m_store->helper() == Store::Helper::Ready || m_store->helper() == Store::Helper::Outdated;
    m_toggle->setText(active ? L("Disconnect") : L("Connect"));
    m_toggle->setEnabled(usable && !busy && (active || m_store->selectedTunnel()));
    m_open->setText(L("Open AWG Bar…"));
    m_quit->setText(active ? L("Disconnect VPN and Quit") : L("Quit"));
}
