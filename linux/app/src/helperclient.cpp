#include "helperclient.h"
#include "l10n.h"

#include <QJsonDocument>
#include <QLocalSocket>
#include <QTimer>

QString HelperClient::socketPath()
{
    const QByteArray env = qgetenv("AWGBAR_SOCKET");
    return env.isEmpty() ? QStringLiteral("/run/awgbar/helper.sock") : QString::fromLocal8Bit(env);
}

QString HelperReply::error() const
{
    if (!transportError.isEmpty())
        return transportError;
    const QString message = json.value(QStringLiteral("error")).toString();
    return message.isEmpty() ? L("Helper error") : message;
}

static HelperReply parse(const QByteArray &data)
{
    HelperReply reply;
    QJsonParseError error;
    const QJsonDocument doc = QJsonDocument::fromJson(data.trimmed(), &error);
    if (error.error != QJsonParseError::NoError || !doc.isObject())
        reply.transportError = L("The helper sent a broken reply");
    else
        reply.json = doc.object();
    return reply;
}

static QByteArray encode(const QJsonObject &request)
{
    return QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n';
}

void HelperClient::send(const QJsonObject &request, Callback done, int timeoutMs)
{
    auto *socket = new QLocalSocket(this);
    auto *timer = new QTimer(socket);
    auto buffer = std::make_shared<QByteArray>();
    auto finished = std::make_shared<bool>(false);

    auto finish = [socket, done, finished](const HelperReply &reply) {
        if (*finished)
            return;
        *finished = true;
        socket->abort();
        socket->deleteLater();
        done(reply);
    };

    timer->setSingleShot(true);
    connect(timer, &QTimer::timeout, socket, [finish] {
        finish({{}, L("The helper did not answer in time")});
    });
    connect(socket, &QLocalSocket::connected, socket, [socket, request] {
        socket->write(encode(request));
    });
    connect(socket, &QLocalSocket::readyRead, socket, [socket, buffer, finish] {
        buffer->append(socket->readAll());
        if (buffer->contains('\n'))
            finish(parse(*buffer));
    });
    connect(socket, &QLocalSocket::disconnected, socket, [buffer, finish] {
        if (!buffer->isEmpty())
            finish(parse(*buffer));
        else
            finish({{}, L("The helper closed the connection")});
    });
    connect(socket, &QLocalSocket::errorOccurred, socket, [socket, buffer, finish](QLocalSocket::LocalSocketError error) {
        if (error == QLocalSocket::PeerClosedError)
            return; // handled by disconnected
        finish({{}, socket->errorString()});
    });

    timer->start(timeoutMs);
    socket->connectToServer(socketPath());
}

HelperReply HelperClient::sendBlocking(const QJsonObject &request, int timeoutMs)
{
    QLocalSocket socket;
    socket.connectToServer(socketPath());
    if (!socket.waitForConnected(timeoutMs))
        return {{}, socket.errorString()};
    socket.write(encode(request));
    socket.waitForBytesWritten(timeoutMs);
    QByteArray data;
    while (!data.contains('\n') && socket.waitForReadyRead(timeoutMs))
        data += socket.readAll();
    data += socket.readAll();
    if (data.isEmpty())
        return {{}, L("The helper did not answer in time")};
    return parse(data);
}
