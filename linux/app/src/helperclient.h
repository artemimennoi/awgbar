#pragma once

#include <QJsonObject>
#include <QObject>
#include <functional>

// One request per connection to awgbar-helper: a JSON line out, a JSON line back.
// See linux/helper/protocol.go for the messages.
struct HelperReply {
    QJsonObject json;
    // Set when the helper couldn't be reached at all, as opposed to a request it refused.
    QString transportError;

    bool ok() const { return transportError.isEmpty() && json.value(QStringLiteral("ok")).toBool(); }
    QString error() const;
    QString code() const { return json.value(QStringLiteral("code")).toString(); }
};

class HelperClient : public QObject {
    Q_OBJECT
public:
    static QString socketPath();

    using Callback = std::function<void(const HelperReply &)>;
    // `up` can take a while (name lookup, a polkit password prompt), hence the long default.
    void send(const QJsonObject &request, Callback done, int timeoutMs = 120000);
    // For quitting: the event loop may already be gone.
    static HelperReply sendBlocking(const QJsonObject &request, int timeoutMs);
};
