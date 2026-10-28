#include "store.h"
#include "keyimport.h"
#include "l10n.h"

#include <QJsonArray>
#include <QRegularExpression>
#include <QSet>
#include <QSettings>

// The protocol version this app speaks; older helpers get a "reinstall" notice.
static const int requiredHelperVersion = 1;

static QJsonObject request(const QString &command, const QString &name = QString())
{
    QJsonObject r{{QStringLiteral("command"), command}};
    if (!name.isEmpty())
        r.insert(QStringLiteral("name"), name);
    return r;
}

Store::Store(QObject *parent) : QObject(parent)
{
    m_selected = QSettings().value(QStringLiteral("selected")).toString();
    m_poll.setInterval(3000);
    connect(&m_poll, &QTimer::timeout, this, &Store::refresh);
    m_poll.start();
    // Errors fade out like a notification; a new error restarts the clock.
    m_errorTimer.setSingleShot(true);
    m_errorTimer.setInterval(8000);
    connect(&m_errorTimer, &QTimer::timeout, this, [this] { setError(QString()); });
    refresh();
}

const Tunnel *Store::active() const
{
    for (const Tunnel &t : m_tunnels)
        if (t.active)
            return &t;
    return nullptr;
}

const Tunnel *Store::selectedTunnel() const
{
    for (const Tunnel &t : m_tunnels)
        if (t.name == m_selected)
            return &t;
    return nullptr;
}

void Store::setError(const QString &message, const QString &log)
{
    m_error = message;
    m_errorLog = log;
    if (!message.isEmpty())
        m_errorTimer.start();
    emit changed();
}

void Store::send(const QJsonObject &request, std::function<void(const HelperReply &)> done)
{
    m_client.send(request, std::move(done));
}

void Store::refresh()
{
    if (m_refreshing || m_busy)
        return;
    m_refreshing = true;
    send(request(QStringLiteral("version")), [this](const HelperReply &reply) {
        if (!reply.ok()) {
            m_refreshing = false;
            m_tunnels.clear();
            m_stats.reset();
            if (reply.code() == QLatin1String("denied")) {
                m_helper = Helper::Denied;
                m_helperProblem = reply.error();
            } else {
                m_helper = Helper::Unreachable;
                m_helperProblem = reply.error();
            }
            emit changed();
            return;
        }
        m_helper = reply.json.value(QStringLiteral("version")).toInt() < requiredHelperVersion ? Helper::Outdated
                                                                                                : Helper::Ready;
        send(request(QStringLiteral("list")), [this](const HelperReply &reply) {
            if (!reply.ok()) {
                m_refreshing = false;
                emit changed();
                return;
            }
            apply(reply.json);
            const Tunnel *on = active();
            if (!on) {
                m_refreshing = false;
                m_stats.reset();
                emit changed();
                return;
            }
            send(request(QStringLiteral("stats"), on->name), [this](const HelperReply &reply) {
                m_refreshing = false;
                const QJsonObject s = reply.json.value(QStringLiteral("stats")).toObject();
                if (reply.ok() && !s.isEmpty()) {
                    m_stats = Stats{s.value(QStringLiteral("endpoint")).toString(),
                                    qint64(s.value(QStringLiteral("rxBytes")).toDouble()),
                                    qint64(s.value(QStringLiteral("txBytes")).toDouble())};
                } else {
                    m_stats.reset();
                }
                emit changed();
            });
        });
    });
}

void Store::apply(const QJsonObject &response)
{
    if (response.contains(QStringLiteral("tunnels")) || response.contains(QStringLiteral("debugLog"))) {
        m_tunnels.clear();
        for (const QJsonValue &v : response.value(QStringLiteral("tunnels")).toArray()) {
            const QJsonObject o = v.toObject();
            m_tunnels.append({o.value(QStringLiteral("name")).toString(), o.value(QStringLiteral("title")).toString(),
                              o.value(QStringLiteral("endpoint")).toString(),
                              o.value(QStringLiteral("active")).toBool()});
        }
    }
    m_excluded.clear();
    for (const QJsonValue &v : response.value(QStringLiteral("excluded")).toArray())
        m_excluded.append(v.toString());
    m_debugLog = response.value(QStringLiteral("debugLog")).toBool();

    if (const Tunnel *on = active())
        m_selected = on->name;
    else if (!selectedTunnel())
        m_selected = m_tunnels.isEmpty() ? QString() : m_tunnels.first().name;
    QSettings().setValue(QStringLiteral("selected"), m_selected);
}

void Store::perform(const QJsonObject &req, Done after)
{
    m_busy = true;
    setError(QString());
    send(req, [this, after](const HelperReply &reply) {
        m_busy = false;
        if (reply.ok())
            apply(reply.json);
        else
            setError(reply.error(), reply.json.value(QStringLiteral("log")).toString());
        if (after)
            after();
        emit changed();
        refresh();
    });
    emit changed();
}

void Store::toggle()
{
    if (const Tunnel *on = active())
        perform(request(QStringLiteral("down"), on->name));
    else if (!m_selected.isEmpty())
        perform(request(QStringLiteral("up"), m_selected));
}

void Store::select(const QString &name)
{
    // Switching tunnels needs the VPN off first.
    if (selectionLocked())
        return;
    m_selected = name;
    QSettings().setValue(QStringLiteral("selected"), name);
    emit changed();
}

void Store::rename(const QString &name, const QString &title)
{
    QJsonObject r = request(QStringLiteral("rename"), name);
    r.insert(QStringLiteral("title"), title);
    perform(r);
}

void Store::remove(const QString &name) { perform(request(QStringLiteral("delete"), name)); }

void Store::setExcluded(const QString &text)
{
    QStringList items;
    for (const QString &item : text.split(QRegularExpression(QStringLiteral("[,;\\s]+")), Qt::SkipEmptyParts))
        items.append(item);
    // Saving an unchanged list would do nothing but flash the busy state.
    if (items == m_excluded)
        return;
    QJsonObject r = request(QStringLiteral("setExcluded"));
    r.insert(QStringLiteral("excluded"), QJsonArray::fromStringList(items));
    perform(r);
}

void Store::setDebugLog(bool enabled)
{
    QJsonObject r = request(QStringLiteral("setDebugLog"));
    r.insert(QStringLiteral("enabled"), enabled);
    perform(r);
}

void Store::importText(const QString &text, const QString &suggestedName)
{
    // Several files are imported one after another.
    m_importQueue.append({suggestedName, text});
    if (m_importQueue.size() == 1)
        importQueued();
}

void Store::importQueued()
{
    const QString text = m_importQueue.first().config, suggestedName = m_importQueue.first().name;
    QString error;
    const QList<ImportedTunnel> items = KeyImport::parse(text, suggestedName, &error);
    if (items.isEmpty()) {
        setError(error);
        m_importQueue.removeFirst();
        if (!m_importQueue.isEmpty())
            importQueued();
        return;
    }
    QSet<QString> taken;
    for (const Tunnel &t : m_tunnels)
        taken.insert(t.name);
    m_busy = true;
    setError(QString());
    importNext(items, taken, false, QString());
}

// A backup can hold several servers: skip the ones we already have and import the rest.
void Store::importNext(QList<ImportedTunnel> items, QSet<QString> taken, bool importedAny, QString duplicate)
{
    if (items.isEmpty()) {
        m_busy = false;
        if (!importedAny && !duplicate.isEmpty()) {
            // Nothing new: point at the copy we already have.
            m_selected = duplicate;
            QString title = duplicate;
            for (const Tunnel &t : m_tunnels)
                if (t.name == duplicate)
                    title = t.displayName();
            setError(L("Already added as %1").arg(title));
        }
        m_importQueue.removeFirst();
        if (!m_importQueue.isEmpty()) {
            importQueued();
            return;
        }
        emit changed();
        refresh();
        return;
    }
    const ImportedTunnel item = items.takeFirst();
    const QString name = KeyImport::tunnelName(item.name, taken);
    taken.insert(name);
    QJsonObject r = request(QStringLiteral("importConfig"), name);
    r.insert(QStringLiteral("config"), item.config);
    r.insert(QStringLiteral("title"), item.name);
    send(r, [=](const HelperReply &reply) mutable {
        if (reply.ok()) {
            apply(reply.json);
            m_selected = name;
            importedAny = true;
        } else if (reply.code().startsWith(QLatin1String("duplicate:"))) {
            if (duplicate.isEmpty())
                duplicate = reply.code().mid(int(qstrlen("duplicate:")));
        } else {
            setError(reply.error());
        }
        importNext(items, taken, importedAny, duplicate);
    });
}

void Store::disconnectAllBlocking()
{
    // Asks for the current list rather than trusting the last poll, which may be 3 s old.
    const HelperReply list = HelperClient::sendBlocking(request(QStringLiteral("list")), 3000);
    for (const QJsonValue &v : list.json.value(QStringLiteral("tunnels")).toArray()) {
        const QJsonObject o = v.toObject();
        if (o.value(QStringLiteral("active")).toBool())
            HelperClient::sendBlocking(request(QStringLiteral("down"), o.value(QStringLiteral("name")).toString()),
                                       10000);
    }
}
