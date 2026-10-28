#include "autostart.h"

#include <QDir>
#include <QFile>
#include <QSaveFile>
#include <QSettings>
#include <QStandardPaths>

static const QString fileName = QStringLiteral("autostart/awgbar.desktop");

static QString userFile()
{
    return QStandardPaths::writableLocation(QStandardPaths::GenericConfigLocation) + QLatin1Char('/') + fileName;
}

// The package's copy in /etc/xdg (or wherever XDG_CONFIG_DIRS points).
static bool systemFileExists()
{
    for (const QString &dir : QStandardPaths::standardLocations(QStandardPaths::GenericConfigLocation)) {
        const QString path = dir + QLatin1Char('/') + fileName;
        if (path != userFile() && QFile::exists(path))
            return true;
    }
    return false;
}

bool Autostart::isEnabled()
{
    if (QFile::exists(userFile())) {
        QSettings entry(userFile(), QSettings::IniFormat);
        return !entry.value(QStringLiteral("Desktop Entry/Hidden")).toBool();
    }
    return systemFileExists();
}

QString Autostart::setEnabled(bool enabled)
{
    if (enabled && systemFileExists()) {
        if (QFile::exists(userFile()) && !QFile::remove(userFile()))
            return QStringLiteral("cannot remove %1").arg(userFile());
        return QString();
    }
    QDir().mkpath(QFileInfo(userFile()).absolutePath());
    QSaveFile file(userFile());
    if (!file.open(QIODevice::WriteOnly))
        return file.errorString();
    QByteArray text = "[Desktop Entry]\nType=Application\nName=AWG Bar\nIcon=awgbar\n";
    if (enabled)
        text += "Exec=awgbar --background\nX-GNOME-Autostart-enabled=true\n";
    else
        text += "Exec=awgbar --background\nHidden=true\n";
    file.write(text);
    return file.commit() ? QString() : file.errorString();
}
