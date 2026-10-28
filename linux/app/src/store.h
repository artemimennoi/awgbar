#pragma once

#include "helperclient.h"
#include "keyimport.h"

#include <QList>
#include <QObject>
#include <QStringList>
#include <QTimer>
#include <optional>

struct Tunnel {
    QString name;
    QString title;
    QString endpoint;
    bool active = false;

    QString displayName() const { return title.isEmpty() ? name : title; }
};

struct Stats {
    QString endpoint;
    qint64 rxBytes = 0;
    qint64 txBytes = 0;
};

// The app's view of the helper, polled every few seconds, plus the actions the window and the
// tray share. TunnelStore.swift's counterpart.
class Store : public QObject {
    Q_OBJECT
public:
    enum class Helper { Checking, Ready, Unreachable, Denied, Outdated };

    explicit Store(QObject *parent = nullptr);

    Helper helper() const { return m_helper; }
    QString helperProblem() const { return m_helperProblem; }
    const QList<Tunnel> &tunnels() const { return m_tunnels; }
    const Tunnel *active() const;
    const Tunnel *selectedTunnel() const;
    QString selected() const { return m_selected; }
    std::optional<Stats> stats() const { return m_stats; }
    QStringList excluded() const { return m_excluded; }
    bool debugLog() const { return m_debugLog; }
    bool busy() const { return m_busy; }
    QString error() const { return m_error; }
    QString errorLog() const { return m_errorLog; }

    void refresh();
    void toggle();
    void select(const QString &name);
    void importText(const QString &text, const QString &suggestedName);
    void rename(const QString &name, const QString &title);
    void remove(const QString &name);
    void setExcluded(const QString &text);
    void setDebugLog(bool enabled);
    void setError(const QString &message, const QString &log = QString());
    // Quitting the app disconnects, as on macOS. Blocking with a short timeout: quitting must
    // not hang on a stuck helper.
    void disconnectAllBlocking();

signals:
    void changed();

private:
    using Done = std::function<void()>;
    void send(const QJsonObject &request, std::function<void(const HelperReply &)> done);
    // Runs a call that changes something with the busy state on, and surfaces its error.
    void perform(const QJsonObject &request, Done after = {});
    void apply(const QJsonObject &response);
    void importQueued();
    void importNext(QList<ImportedTunnel> items, QSet<QString> taken, bool importedAny, QString duplicate);

    HelperClient m_client;
    QTimer m_poll;
    QTimer m_errorTimer;
    Helper m_helper = Helper::Checking;
    QString m_helperProblem;
    QList<Tunnel> m_tunnels;
    QString m_selected;
    std::optional<Stats> m_stats;
    QStringList m_excluded;
    bool m_debugLog = false;
    bool m_busy = false;
    bool m_refreshing = false;
    QString m_error;
    QString m_errorLog;
    // Texts waiting to be imported; `name` is the suggested name, `config` the text.
    QList<ImportedTunnel> m_importQueue;
};
