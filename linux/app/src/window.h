#pragma once

#include <QWidget>

class ElidedLabel;
class Store;
class QLabel;
class QLineEdit;
class QListWidget;
class QMenu;
class QPushButton;
class QStackedWidget;
class QToolButton;

// The main window, as in the agreed mockup: status with the connect button, the tunnel list,
// import buttons and the split-tunnel field. Settings live in the ☰ menu.
class Window : public QWidget {
    Q_OBJECT
public:
    Window(Store *store, QMenu *settingsMenu);
    void showAndRaise();

protected:
    void closeEvent(QCloseEvent *event) override;
    void changeEvent(QEvent *event) override;

private:
    void rebuild();
    void mute(QWidget *widget);
    void importFromClipboard();
    void importFromFile();
    void showTunnelMenu(const QString &name, const QPoint &globalPos);

    Store *m_store;
    QLabel *m_badge;
    ElidedLabel *m_title;
    ElidedLabel *m_subtitle;
    QPushButton *m_power;
    QToolButton *m_menuButton;
    QLabel *m_error;
    QStackedWidget *m_pages;
    QLabel *m_problem;
    QLabel *m_tunnelsLabel;
    QListWidget *m_list;
    QWidget *m_empty;
    QLabel *m_emptyTitle;
    QLabel *m_emptyHint;
    QPushButton *m_fromClipboard;
    QPushButton *m_fromFile;
    QLabel *m_bypassLabel;
    QLineEdit *m_bypass;
    QPushButton *m_apply;
};
