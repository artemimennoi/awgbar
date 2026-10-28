#pragma once

#include <QObject>

class QAction;
class QActionGroup;
class QMenu;
class QSystemTrayIcon;
class Store;
class Window;

// The tray icon (StatusNotifierItem on KDE, and on GNOME with the AppIndicator extension) and its
// menu: status, traffic, tunnel choice, connect/disconnect, open the window, quit.
class Tray : public QObject {
    Q_OBJECT
public:
    Tray(Store *store, Window *window, QObject *parent = nullptr);
    bool available() const;

signals:
    void quitRequested();

private:
    void rebuild();

    Store *m_store;
    Window *m_window;
    QSystemTrayIcon *m_icon;
    QMenu *m_menu;
    QAction *m_status;
    QAction *m_traffic;
    QAction *m_tunnelsSeparator;
    QActionGroup *m_tunnels;
    QAction *m_toggle;
    QAction *m_open;
    QAction *m_quit;
    QString m_tunnelSignature;
    int m_iconState = -1;
};
