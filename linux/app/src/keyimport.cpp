#include "keyimport.h"
#include "l10n.h"

#include <QHash>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QtEndian>
#include <QRegularExpression>

namespace {

bool containsCI(const QString &text, const QString &needle) { return text.contains(needle, Qt::CaseInsensitive); }

bool parseJson(const QString &text, QJsonValue *out)
{
    QJsonParseError error;
    const QJsonDocument doc = QJsonDocument::fromJson(text.toUtf8(), &error);
    if (error.error != QJsonParseError::NoError)
        return false;
    *out = doc.isArray() ? QJsonValue(doc.array()) : QJsonValue(doc.object());
    return true;
}

struct Walker {
    QString suggestedName;
    QList<ImportedTunnel> result;
    QSet<QString> seen;
    bool sawAPIConfig = false;

    // AmneziaVPN keeps the ready wg-quick text in containers[].awg.last_config.config, where
    // last_config is itself JSON encoded as a string. Keys, exports and backups nest that at
    // different depths, so walk the whole document, unwrap JSON found in strings, and take every
    // string that is a config. The title is the nearest server `description`.
    void walk(const QJsonValue &node, const QString &title)
    {
        if (node.isObject()) {
            const QJsonObject dict = node.toObject();
            if (dict.contains(QStringLiteral("api_config")) || dict.contains(QStringLiteral("auth_data")))
                sawAPIConfig = true;
            QString t = dict.value(QStringLiteral("description")).toString();
            if (t.isEmpty())
                t = title;
            if (t.isEmpty())
                t = dict.value(QStringLiteral("hostName")).toString();
            QStringList keys = dict.keys();
            keys.sort();
            for (const QString &key : keys)
                walk(dict.value(key), t);
        } else if (node.isArray()) {
            for (const QJsonValue &item : node.toArray())
                walk(item, title);
        } else if (node.isString()) {
            const QString text = node.toString().trimmed();
            QJsonValue nested;
            // JSON first: an encoded server list contains "[Interface]" too. A config itself
            // starts with "[Interface]" and never parses as JSON.
            if ((text.startsWith(QLatin1Char('{')) || text.startsWith(QLatin1Char('['))) && parseJson(text, &nested)) {
                walk(nested, title);
            } else if (containsCI(text, QStringLiteral("[Interface]")) && containsCI(text, QStringLiteral("[Peer]"))) {
                if (!seen.contains(text)) {
                    seen.insert(text);
                    result.append({title.isEmpty() ? suggestedName : title, text + QLatin1Char('\n')});
                }
            }
        }
    }
};

QList<ImportedTunnel> configs(const QJsonValue &doc, const QString &suggestedName, QString *error)
{
    Walker walker{suggestedName, {}, {}, false};
    walker.walk(doc, QString());
    if (walker.result.isEmpty()) {
        *error = walker.sawAPIConfig
            ? L("This is an Amnezia Premium/Free key: its config is issued by Amnezia's server at connect time "
                "and cannot be imported")
            : L("No ready AmneziaWG config inside. If this is an admin key or a backup, connect once in "
                "AmneziaVPN and share the connection in AmneziaWG format");
    }
    return walker.result;
}

// vpn:// is base64url of Qt's qCompress output, which qUncompress reads back as is.
bool decodeVpnKey(const QString &key, QJsonValue *out, QString *error)
{
    QString b64 = key.mid(int(qstrlen("vpn://")));
    b64.remove(QRegularExpression(QStringLiteral("\\s")));
    const auto decoded = QByteArray::fromBase64Encoding(
        b64.toLatin1(), QByteArray::Base64UrlEncoding | QByteArray::AbortOnBase64DecodingErrors);
    if (!decoded) {
        *error = L("Broken key: not base64");
        return false;
    }
    QByteArray json = *decoded;
    // qUncompress allocates the size the 4-byte header claims; a real key unpacks to a few KB.
    if (json.size() > 6 && quint8(json.at(4)) == 0x78 && qFromBigEndian<quint32>(json.constData()) <= 1 << 20) {
        const QByteArray inflated = qUncompress(json);
        if (!inflated.isEmpty())
            json = inflated;
    }
    if (!parseJson(QString::fromUtf8(json), out)) {
        *error = L("Broken key: cannot unpack it");
        return false;
    }
    return true;
}

// Enough of Cyrillic for readable ids: "Резерв | #202 · NUE" → "Rezerv-202-NUE".
const QHash<QChar, QString> &transliteration()
{
    static const QHash<QChar, QString> table = [] {
        const QString from = QStringLiteral("абвгдеёжзийклмнопрстуфхцчшщъыьэюяіїєґ");
        const char *to[] = {"a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r",
                            "s", "t", "u", "f", "kh", "ts", "ch", "sh", "shch", "", "y", "", "e", "yu", "ya", "i", "i",
                            "ye", "g"};
        QHash<QChar, QString> t;
        for (int i = 0; i < from.size(); ++i) {
            const QString latin = QString::fromLatin1(to[i]);
            t.insert(from.at(i), latin);
            QString upper = latin;
            if (!upper.isEmpty())
                upper[0] = upper.at(0).toUpper();
            t.insert(from.at(i).toUpper(), upper);
        }
        return t;
    }();
    return table;
}

[[maybe_unused]] bool isValidTunnelName(const QString &name)
{
    static const QRegularExpression pattern(QStringLiteral("^[a-zA-Z0-9_=+.-]{1,15}$"));
    return pattern.match(name).hasMatch();
}

} // namespace

QList<ImportedTunnel> KeyImport::parse(const QString &text, const QString &suggestedName, QString *error)
{
    const QString trimmed = text.trimmed();
    if (trimmed.startsWith(QLatin1String("vpn://"))) {
        QJsonValue doc;
        if (!decodeVpnKey(trimmed, &doc, error))
            return {};
        return configs(doc, suggestedName, error);
    }
    const bool looksLikeJSON = trimmed.startsWith(QLatin1Char('{')) || trimmed.startsWith(QLatin1Char('['));
    // A .conf starts with "[Interface]" too, so only a successful parse makes it JSON.
    QJsonValue doc;
    if (looksLikeJSON && parseJson(trimmed, &doc))
        return configs(doc, suggestedName, error);
    if (containsCI(trimmed, QStringLiteral("[Interface]")))
        return {{suggestedName, trimmed + QLatin1Char('\n')}};
    *error = looksLikeJSON ? L("The file is not valid JSON")
                           : L("Expected a vpn://… key, AmneziaVPN JSON or an AmneziaWG config");
    return {};
}

QString KeyImport::tunnelName(const QString &title, const QSet<QString> &taken)
{
    QString latin;
    for (const QChar ch : title)
        latin += transliteration().value(ch, QString(ch));
    latin = latin.normalized(QString::NormalizationForm_D);

    // Anything outside the id charset becomes one "-".
    QString base;
    for (const QChar ch : latin) {
        if (ch.unicode() < 128 && (ch.isLetterOrNumber() || QStringLiteral("_=+.").contains(ch)))
            base += ch;
        else if (ch.category() == QChar::Mark_NonSpacing)
            continue; // diacritics left over from the decomposition
        else if (!base.isEmpty() && !base.endsWith(QLatin1Char('-')))
            base += QLatin1Char('-');
    }
    while (base.endsWith(QLatin1Char('-')))
        base.chop(1);
    base = base.left(15);
    while (base.endsWith(QLatin1Char('-')))
        base.chop(1);
    if (base.isEmpty())
        base = QStringLiteral("awg");

    QString name = base;
    for (int n = 2; taken.contains(name); ++n) {
        const QString suffix = QStringLiteral("-%1").arg(n);
        name = base.left(15 - suffix.size()) + suffix;
    }
    Q_ASSERT(isValidTunnelName(name));
    return name;
}
