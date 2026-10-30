#pragma once

#include <QWidget>

class ElidedLabel;
class PowerButton;
class Store;
class QFrame;
class QLabel;
class QLineEdit;
class QListWidget;
class QMenu;
class QPushButton;
class QStackedWidget;
class QToolButton;

// The main window, laid out like the macOS menu with the desktop's own widgets: header with the
// status, the round power button, the tunnels, Bypass, one info line, and add and settings
// buttons at the bottom.
class Window : public QWidget {
    Q_OBJECT
public:
    Window(Store *store, QMenu *settingsMenu);
    void showAndRaise();

protected:
    void closeEvent(QCloseEvent *event) override;
    void changeEvent(QEvent *event) override;
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void rebuild();
    void mute(QWidget *widget, qreal alpha = 0.6);
    void importFromClipboard();
    void importFromFile();
    void showTunnelMenu(const QString &name, const QPoint &globalPos);
    void beginBypassEdit();
    void endBypassEdit(bool apply);
    bool editingBypass() const;
    void copyErrorLog();

    Store *m_store;
    QLabel *m_dot;
    ElidedLabel *m_status;
    QStackedWidget *m_pages;
    PowerButton *m_power;
    QLabel *m_problem;
    QListWidget *m_list;
    QWidget *m_empty;
    QLabel *m_emptyTitle;
    QLabel *m_emptyHint;
    QPushButton *m_fromClipboard;
    QPushButton *m_fromFile;
    QFrame *m_bypassRow;
    QLabel *m_bypassLabel;
    QStackedWidget *m_bypassStack;
    ElidedLabel *m_bypassValue;
    QLineEdit *m_bypassEdit;
    QToolButton *m_bypassButton;
    QLabel *m_infoIcon;
    ElidedLabel *m_infoText;
    QLabel *m_traffic;
    QToolButton *m_copyLog;
    QToolButton *m_dismiss;
    QToolButton *m_add;
    QMenu *m_addMenu;
    QToolButton *m_menuButton;
};
