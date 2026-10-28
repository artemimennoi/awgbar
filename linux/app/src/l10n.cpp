#include "l10n.h"

#include <QHash>
#include <QLocale>
#include <QSettings>

static const QHash<QString, QString> &russian()
{
    static const QHash<QString, QString> table = {
        // Window and tray
        {QStringLiteral("Please wait…"), QStringLiteral("Подождите…")},
        {QStringLiteral("Connecting…"), QStringLiteral("Подключение…")},
        {QStringLiteral("Connected · %1"), QStringLiteral("Подключено · %1")},
        {QStringLiteral("Disconnected"), QStringLiteral("Отключено")},
        {QStringLiteral("Connect"), QStringLiteral("Подключить")},
        {QStringLiteral("Disconnect"), QStringLiteral("Отключить")},
        {QStringLiteral("Tunnels"), QStringLiteral("Туннели")},
        {QStringLiteral("Rename…"), QStringLiteral("Переименовать…")},
        {QStringLiteral("Delete…"), QStringLiteral("Удалить…")},
        {QStringLiteral("Rename Tunnel"), QStringLiteral("Переименовать туннель")},
        {QStringLiteral("Name:"), QStringLiteral("Имя:")},
        {QStringLiteral("Bypass VPN"), QStringLiteral("Обход VPN")},
        {QStringLiteral("Disconnect to switch tunnels"), QStringLiteral("Чтобы сменить туннель, сначала отключитесь")},
        {QStringLiteral("Apply"), QStringLiteral("Применить")},
        {QStringLiteral("Subnets separated by commas; they go around the VPN"),
         QStringLiteral("Подсети через запятую; они идут мимо VPN")},
        {QStringLiteral("No tunnels"), QStringLiteral("Нет туннелей")},
        {QStringLiteral("Copy a vpn://… key or choose a .conf file"),
         QStringLiteral("Скопируй ключ vpn://… или выбери файл .conf")},
        {QStringLiteral("From Clipboard"), QStringLiteral("Из буфера")},
        {QStringLiteral("From File…"), QStringLiteral("Из файла…")},
        {QStringLiteral("Open AWG Bar…"), QStringLiteral("Открыть AWG Bar…")},
        {QStringLiteral("Menu"), QStringLiteral("Меню")},
        {QStringLiteral("Copy Log"), QStringLiteral("Скопировать лог")},
        // Helper states
        {QStringLiteral("The AWG Bar service is not running"), QStringLiteral("Служба AWG Bar не запущена")},
        {QStringLiteral("Start it with:"), QStringLiteral("Запусти её командой:")},
        {QStringLiteral("Not allowed to control the VPN"), QStringLiteral("Нет прав управлять VPN")},
        {QStringLiteral("The AWG Bar service is outdated; reinstall the package"),
         QStringLiteral("Служба AWG Bar устарела, переустанови пакет")},
        // Settings menu
        {QStringLiteral("Launch at Login"), QStringLiteral("Запускать при входе")},
        {QStringLiteral("Language"), QStringLiteral("Язык")},
        {QStringLiteral("Debug Log"), QStringLiteral("Отладочный лог")},
        {QStringLiteral("Disconnect VPN and Quit"), QStringLiteral("Отключить VPN и выйти")},
        {QStringLiteral("Quit"), QStringLiteral("Выйти")},
        // Alerts and errors
        {QStringLiteral("Helper error"), QStringLiteral("Ошибка службы")},
        {QStringLiteral("The helper sent a broken reply"), QStringLiteral("Служба прислала непонятный ответ")},
        {QStringLiteral("The helper did not answer in time"), QStringLiteral("Служба не ответила вовремя")},
        {QStringLiteral("The helper closed the connection"), QStringLiteral("Служба закрыла соединение")},
        {QStringLiteral("Delete tunnel %1?"), QStringLiteral("Удалить туннель %1?")},
        {QStringLiteral("The config will be deleted permanently."),
         QStringLiteral("Конфиг будет удалён без возможности восстановления.")},
        {QStringLiteral("Delete"), QStringLiteral("Удалить")},
        {QStringLiteral("Cancel"), QStringLiteral("Отмена")},
        {QStringLiteral("Not a subnet: %1"), QStringLiteral("Не похоже на подсеть: %1")},
        {QStringLiteral("Choose an AmneziaWG .conf or a file with a vpn:// key"),
         QStringLiteral("Выбери .conf AmneziaWG или файл с ключом vpn://")},
        {QStringLiteral("Cannot read %1"), QStringLiteral("Не удалось прочитать %1")},
        {QStringLiteral("Launch at login: %1"), QStringLiteral("Автозапуск: %1")},
        {QStringLiteral("The VPN stays connected while AWG Bar runs in the tray."),
         QStringLiteral("VPN остаётся подключённым, пока AWG Bar работает в трее.")},
        // Import
        {QStringLiteral("Already added as %1"), QStringLiteral("Уже добавлен как %1")},
        {QStringLiteral("Expected a vpn://… key, AmneziaVPN JSON or an AmneziaWG config"),
         QStringLiteral("Нужен ключ vpn://…, JSON из AmneziaVPN или конфиг AmneziaWG")},
        {QStringLiteral("The file is not valid JSON"), QStringLiteral("Файл не является корректным JSON")},
        {QStringLiteral("Broken key: not base64"), QStringLiteral("Ключ повреждён: это не base64")},
        {QStringLiteral("Broken key: cannot unpack it"), QStringLiteral("Ключ повреждён: не удалось распаковать")},
        {QStringLiteral("This is an Amnezia Premium/Free key: its config is issued by Amnezia's server at connect time "
                        "and cannot be imported"),
         QStringLiteral("Это ключ Amnezia Premium/Free: конфиг выдаётся их сервером при подключении, импортировать "
                        "его нельзя")},
        {QStringLiteral("No ready AmneziaWG config inside. If this is an admin key or a backup, connect once in "
                        "AmneziaVPN and share the connection in AmneziaWG format"),
         QStringLiteral("Внутри нет готового конфига AmneziaWG. Если это админский ключ или бэкап, подключись один "
                        "раз в AmneziaVPN и поделись подключением в формате AmneziaWG")},
    };
    return table;
}

// Read on first use, after main() has set the application name QSettings depends on.
static L10n::Language &current()
{
    static L10n::Language language = [] {
        const QString saved = QSettings().value(QStringLiteral("language")).toString();
        if (saved == QLatin1String("ru"))
            return L10n::Language::Russian;
        if (saved == QLatin1String("en"))
            return L10n::Language::English;
        return QLocale::system().language() == QLocale::Russian ? L10n::Language::Russian
                                                                : L10n::Language::English;
    }();
    return language;
}

L10n::Language L10n::language() { return current(); }

void L10n::setLanguage(Language language)
{
    current() = language;
    QSettings().setValue(QStringLiteral("language"),
                         language == Language::Russian ? QStringLiteral("ru") : QStringLiteral("en"));
}

QString L(const char *english)
{
    const QString key = QString::fromUtf8(english);
    if (current() == L10n::Language::Russian)
        return russian().value(key, key);
    return key;
}
