#pragma once

#include <QList>
#include <QSet>
#include <QString>

struct ImportedTunnel {
    QString name;
    QString config;
};

// Accepts an AmneziaVPN key (vpn://...), AmneziaVPN JSON (a decoded key, a connection export or a
// settings backup) or a plain AmneziaWG .conf text. The same rules as KeyImport.swift.
namespace KeyImport {
// Returns the configs found, or an empty list with `error` set.
QList<ImportedTunnel> parse(const QString &text, const QString &suggestedName, QString *error);
// A tunnel id from a display name: [a-zA-Z0-9_=+.-]{1,15}, unique among `taken`.
QString tunnelName(const QString &title, const QSet<QString> &taken);
} // namespace KeyImport
