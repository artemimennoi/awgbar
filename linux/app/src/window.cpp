#include "window.h"
#include "glyph.h"
#include "l10n.h"
#include "powerbutton.h"
#include "store.h"

#include <QApplication>
#include <QClipboard>
#include <QCloseEvent>
#include <QFileDialog>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QInputDialog>
#include <QKeyEvent>
#include <QLabel>
#include <QLineEdit>
#include <QListWidget>
#include <QMenu>
#include <QMessageBox>
#include <QPainter>
#include <QPushButton>
#include <QRadioButton>
#include <QResizeEvent>
#include <QStackedWidget>
#include <QStyle>
#include <QTimer>
#include <QToolButton>
#include <QVBoxLayout>

namespace {

// Breeze's negative color, for errors; positive and neutral are Glyph's state colors.
const QColor red(0xda, 0x44, 0x53);

QString bytes(qint64 n)
{
    const bool ru = L10n::language() == L10n::Language::Russian;
    const char *units[] = {"B", "KB", "MB", "GB", "TB"};
    const char *unitsRu[] = {"Б", "КБ", "МБ", "ГБ", "ТБ"};
    double v = double(n);
    int i = 0;
    while (v >= 1000 && i < 4) {
        v /= 1000;
        ++i;
    }
    const QLocale locale = ru ? QLocale(QLocale::Russian) : QLocale(QLocale::English);
    const QString number = locale.toString(v, 'f', i == 0 || v >= 100 ? 0 : 1);
    return number + QChar(0x00a0) + QString::fromUtf8(ru ? unitsRu[i] : units[i]);
}

QString host(const QString &endpoint)
{
    const int colon = endpoint.lastIndexOf(QLatin1Char(':'));
    QString h = colon < 0 ? endpoint : endpoint.left(colon);
    h.remove(QLatin1Char('[')).remove(QLatin1Char(']'));
    return h;
}

QPixmap dot(const QColor &color, qreal dpr)
{
    const int size = 8;
    QPixmap pixmap(QSize(size, size) * dpr);
    pixmap.setDevicePixelRatio(dpr);
    pixmap.fill(Qt::transparent);
    QPainter p(&pixmap);
    p.setRenderHint(QPainter::Antialiasing);
    p.setPen(Qt::NoPen);
    p.setBrush(color);
    p.drawEllipse(QRectF(0, 0, size, size));
    return pixmap;
}

// Fonts may be set in points or in pixels, depending on the platform theme.
QFont scaled(QFont f, qreal factor)
{
    if (f.pointSizeF() > 0)
        f.setPointSizeF(f.pointSizeF() * factor);
    else if (f.pixelSize() > 0)
        f.setPixelSize(qRound(f.pixelSize() * factor));
    return f;
}

// The first icon the theme has: Breeze's name first, then GNOME's.
QIcon themeIcon(std::initializer_list<const char *> names)
{
    for (const char *name : names) {
        const QIcon icon = QIcon::fromTheme(QLatin1String(name));
        if (!icon.isNull())
            return icon;
    }
    return QIcon();
}

QToolButton *iconButton(const QIcon &icon, const QString &fallback)
{
    auto *button = new QToolButton;
    button->setIcon(icon);
    if (icon.isNull())
        button->setText(fallback);
    button->setAutoRaise(true);
    return button;
}

} // namespace

// A one-line label that elides with "…" instead of widening the window. It asks for its whole
// text and gives way when the room runs out.
class ElidedLabel : public QLabel {
public:
    void setFullText(const QString &text)
    {
        m_text = text;
        updateText();
        updateGeometry();
    }
    QSize minimumSizeHint() const override { return QSize(0, QLabel::minimumSizeHint().height()); }
    QSize sizeHint() const override
    {
        return QSize(fontMetrics().horizontalAdvance(m_text) + 2, QLabel::sizeHint().height());
    }

protected:
    void resizeEvent(QResizeEvent *event) override
    {
        QLabel::resizeEvent(event);
        updateText();
    }

private:
    void updateText()
    {
        const QString shown = fontMetrics().elidedText(m_text, Qt::ElideRight, width());
        setText(shown);
        setToolTip(shown == m_text ? QString() : m_text);
    }
    QString m_text;
};

Window::Window(Store *store, QMenu *settingsMenu) : m_store(store)
{
    setWindowTitle(QStringLiteral("AWG Bar"));
    setWindowIcon(Glyph::app());
    setMinimumWidth(340);
    resize(380, 0);

    auto *root = new QVBoxLayout(this);
    root->setContentsMargins(16, 14, 16, 12);
    root->setSpacing(10);

    // Header: the app's name, then a status dot and what the VPN is doing.
    auto *header = new QHBoxLayout;
    header->setSpacing(8);
    auto *name = new QLabel(QStringLiteral("AWG Bar"));
    QFont nameFont = scaled(name->font(), 1.25);
    nameFont.setWeight(QFont::DemiBold);
    name->setFont(nameFont);
    header->addWidget(name);
    header->addStretch(1);
    m_dot = new QLabel;
    m_dot->setFixedSize(8, 8);
    header->addWidget(m_dot);
    m_status = new ElidedLabel;
    header->addWidget(m_status);
    root->addLayout(header);

    m_pages = new QStackedWidget;
    root->addWidget(m_pages);

    // Page 0: the power button, the tunnels and Bypass.
    auto *main = new QWidget;
    auto *mainLayout = new QVBoxLayout(main);
    mainLayout->setContentsMargins(0, 0, 0, 0);
    mainLayout->setSpacing(10);
    m_power = new PowerButton;
    connect(m_power, &PowerButton::clicked, store, &Store::toggle);
    mainLayout->addWidget(m_power, 0, Qt::AlignHCenter);

    m_list = new QListWidget;
    // The radio buttons show the chosen tunnel; a selection highlight on top would be noise.
    m_list->setSelectionMode(QAbstractItemView::NoSelection);
    m_list->setFocusPolicy(Qt::NoFocus);
    m_list->setContextMenuPolicy(Qt::CustomContextMenu);
    connect(m_list, &QListWidget::itemClicked, this,
            [this](QListWidgetItem *item) { m_store->select(item->data(Qt::UserRole).toString()); });
    connect(m_list, &QListWidget::customContextMenuRequested, this, [this](const QPoint &pos) {
        if (QListWidgetItem *item = m_list->itemAt(pos))
            showTunnelMenu(item->data(Qt::UserRole).toString(), m_list->viewport()->mapToGlobal(pos));
    });
    mainLayout->addWidget(m_list);

    m_empty = new QWidget;
    auto *emptyLayout = new QVBoxLayout(m_empty);
    emptyLayout->setContentsMargins(0, 8, 0, 8);
    emptyLayout->setSpacing(4);
    m_emptyTitle = new QLabel;
    QFont bold = m_emptyTitle->font();
    bold.setWeight(QFont::DemiBold);
    m_emptyTitle->setFont(bold);
    m_emptyTitle->setAlignment(Qt::AlignHCenter);
    m_emptyHint = new QLabel;
    m_emptyHint->setWordWrap(true);
    m_emptyHint->setAlignment(Qt::AlignHCenter);
    emptyLayout->addWidget(m_emptyTitle);
    emptyLayout->addWidget(m_emptyHint);
    auto *importRow = new QHBoxLayout;
    importRow->setContentsMargins(0, 6, 0, 0);
    m_fromClipboard = new QPushButton(QIcon::fromTheme(QStringLiteral("edit-paste")), QString());
    m_fromFile = new QPushButton(QIcon::fromTheme(QStringLiteral("document-open")), QString());
    connect(m_fromClipboard, &QPushButton::clicked, this, &Window::importFromClipboard);
    connect(m_fromFile, &QPushButton::clicked, this, &Window::importFromFile);
    importRow->addStretch();
    importRow->addWidget(m_fromClipboard);
    importRow->addWidget(m_fromFile);
    importRow->addStretch();
    emptyLayout->addLayout(importRow);
    mainLayout->addWidget(m_empty);

    // Bypass: a label and the subnets in a text field. Enter or the checkmark applies (an active
    // tunnel reconnects), Esc puts the saved list back; an empty list reads "Off".
    auto *bypassRow = new QHBoxLayout;
    bypassRow->setSpacing(8);
    m_bypassLabel = new QLabel;
    bypassRow->addWidget(m_bypassLabel);
    m_bypass = new QLineEdit;
    m_bypass->installEventFilter(this);
    connect(m_bypass, &QLineEdit::returnPressed, this, &Window::applyBypass);
    connect(m_bypass, &QLineEdit::textEdited, this, [this] {
        m_bypassApply->setVisible(m_bypass->text() != m_store->excluded().join(QStringLiteral(", ")));
    });
    bypassRow->addWidget(m_bypass, 1);
    m_bypassApply = iconButton(themeIcon({"dialog-ok-apply", "dialog-ok", "object-select-symbolic"}), QStringLiteral("✓"));
    connect(m_bypassApply, &QToolButton::clicked, this, &Window::applyBypass);
    m_bypassApply->hide();
    bypassRow->addWidget(m_bypassApply);
    mainLayout->addLayout(bypassRow);
    m_pages->addWidget(main);

    // Page 1: the helper isn't usable.
    m_problem = new QLabel;
    m_problem->setWordWrap(true);
    m_problem->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_problem->setAlignment(Qt::AlignTop | Qt::AlignLeft);
    m_pages->addWidget(m_problem);

    // One line for what matters most right now: an error, an outdated service, the traffic, or
    // where Connect goes. Always the same height, so the window doesn't jump.
    auto *info = new QWidget;
    auto *infoLayout = new QHBoxLayout(info);
    infoLayout->setContentsMargins(2, 0, 0, 0);
    infoLayout->setSpacing(6);
    m_infoIcon = new QLabel;
    infoLayout->addWidget(m_infoIcon);
    m_infoText = new ElidedLabel;
    m_infoText->setTextInteractionFlags(Qt::TextSelectableByMouse);
    infoLayout->addWidget(m_infoText, 1);
    m_traffic = new QLabel;
    infoLayout->addWidget(m_traffic);
    m_copyLog = new QToolButton;
    m_copyLog->setAutoRaise(true);
    connect(m_copyLog, &QToolButton::clicked, this, &Window::copyErrorLog);
    infoLayout->addWidget(m_copyLog);
    m_dismiss = iconButton(themeIcon({"window-close", "dialog-close", "window-close-symbolic"}), QStringLiteral("✕"));
    connect(m_dismiss, &QToolButton::clicked, this, [this] { m_store->setError(QString()); });
    infoLayout->addWidget(m_dismiss);
    info->setFixedHeight(m_dismiss->sizeHint().height());
    // A taller window than needed gets empty space here rather than stretched rows.
    root->addStretch(1);
    root->addWidget(info);

    // Add a tunnel on the left, settings on the right.
    auto *footer = new QHBoxLayout;
    m_add = new QToolButton;
    m_add->setIcon(themeIcon({"list-add", "list-add-symbolic"}));
    if (m_add->icon().isNull())
        m_add->setText(QStringLiteral("+"));
    m_addMenu = new QMenu(this);
    QAction *paste = m_addMenu->addAction(QIcon::fromTheme(QStringLiteral("edit-paste")), QString(), this,
                                          &Window::importFromClipboard);
    QAction *open = m_addMenu->addAction(QIcon::fromTheme(QStringLiteral("document-open")), QString(), this,
                                         &Window::importFromFile);
    connect(m_addMenu, &QMenu::aboutToShow, m_addMenu, [paste, open] {
        paste->setText(L("From Clipboard"));
        open->setText(L("From File…"));
    });
    m_add->setMenu(m_addMenu);
    m_menuButton = new QToolButton;
    m_menuButton->setIcon(themeIcon({"configure", "application-menu", "open-menu-symbolic"}));
    if (m_menuButton->icon().isNull())
        m_menuButton->setText(QStringLiteral("☰"));
    m_menuButton->setMenu(settingsMenu);
    for (QToolButton *b : {m_add, m_menuButton}) {
        b->setPopupMode(QToolButton::InstantPopup);
        b->setIconSize(QSize(20, 20));
        b->setStyleSheet(QStringLiteral("QToolButton::menu-indicator { image: none; width: 0; }"));
    }
    footer->addWidget(m_add);
    footer->addStretch();
    footer->addWidget(m_menuButton);
    root->addLayout(footer);

    connect(store, &Store::changed, this, &Window::rebuild);
    rebuild();
}

void Window::showAndRaise()
{
    const bool opening = !isVisible();
    show();
    setWindowState((windowState() & ~Qt::WindowMinimized) | Qt::WindowActive);
    raise();
    activateWindow();
    // Nothing is focused until Tab or a click picks something, as in the macOS menu: otherwise
    // the power button would open with a focus ring.
    if (opening)
        setFocus(Qt::ActiveWindowFocusReason);
}

void Window::closeEvent(QCloseEvent *event)
{
    // The window is only a view; the app keeps running in the tray and the VPN stays up.
    event->ignore();
    revertBypass();
    hide();
}

void Window::changeEvent(QEvent *event)
{
    // Follow color scheme changes for the dot and the muted texts.
    if (event->type() == QEvent::PaletteChange)
        rebuild();
    QWidget::changeEvent(event);
}

bool Window::eventFilter(QObject *watched, QEvent *event)
{
    if (watched == m_bypass && event->type() == QEvent::KeyPress &&
        static_cast<QKeyEvent *>(event)->key() == Qt::Key_Escape) {
        revertBypass();
        return true;
    }
    return QWidget::eventFilter(watched, event);
}

// Secondary text: the window's text color, faded. Not QPalette::PlaceholderText, which Breeze
// for Qt 5 leaves invisible in some containers. Text too: labels on a Base background (Bypass)
// draw with it.
void Window::mute(QWidget *widget, qreal alpha)
{
    QPalette p = widget->palette();
    for (QPalette::ColorRole role : {QPalette::WindowText, QPalette::Text}) {
        QColor c = palette().color(role);
        c.setAlphaF(alpha);
        p.setColor(role, c);
    }
    widget->setPalette(p);
}

// The helper checks the list and reconnects an active tunnel; a bad subnet comes back as an error.
void Window::applyBypass()
{
    m_store->setExcluded(m_bypass->text());
    m_bypassApply->hide();
    m_bypass->clearFocus();
}

void Window::revertBypass()
{
    m_bypass->setText(m_store->excluded().join(QStringLiteral(", ")));
    m_bypassApply->hide();
    m_bypass->clearFocus();
}

void Window::copyErrorLog()
{
    QString text = m_store->error();
    if (!m_store->errorLog().isEmpty())
        text += QStringLiteral("\n\n") + m_store->errorLog();
    QApplication::clipboard()->setText(text);
}

void Window::rebuild()
{
    const Tunnel *active = m_store->active();
    const bool busy = m_store->busy();
    const Glyph::State state = busy ? Glyph::State::Busy : active ? Glyph::State::On : Glyph::State::Off;
    const bool usable = m_store->helper() == Store::Helper::Ready || m_store->helper() == Store::Helper::Outdated;
    const QColor text = palette().color(QPalette::WindowText);

    // Header
    QColor dotColor = text;
    dotColor.setAlphaF(0.35);
    if (busy)
        dotColor = Glyph::busyColor;
    else if (active)
        dotColor = Glyph::connectedColor;
    m_dot->setPixmap(dot(dotColor, devicePixelRatioF()));
    if (busy)
        m_status->setFullText(L("Please wait…"));
    else if (active)
        m_status->setFullText(L("Connected · %1").arg(active->displayName()));
    else
        m_status->setFullText(L("Disconnected"));
    mute(m_status);

    // Power button
    m_power->setState(state);
    m_power->setEnabled(usable && !busy && (active || m_store->selectedTunnel()));
    m_power->setToolTip(active ? L("Disconnect") : L("Connect"));
    m_power->setAccessibleName(m_power->toolTip());

    // Helper problems replace the power button, the tunnels and Bypass.
    switch (m_store->helper()) {
    case Store::Helper::Unreachable:
        m_problem->setText(QStringLiteral("<b>%1</b><br><br>%2<br><code>sudo systemctl enable --now awgbar-helper</code>"
                                          "<br><br><span>%3</span>")
                               .arg(L("The AWG Bar service is not running").toHtmlEscaped(),
                                    L("Start it with:").toHtmlEscaped(), m_store->helperProblem().toHtmlEscaped()));
        m_pages->setCurrentIndex(1);
        break;
    case Store::Helper::Denied:
        m_problem->setText(QStringLiteral("<b>%1</b><br><br>%2")
                               .arg(L("Not allowed to control the VPN").toHtmlEscaped(),
                                    m_store->helperProblem().toHtmlEscaped()));
        m_pages->setCurrentIndex(1);
        break;
    case Store::Helper::Checking:
    case Store::Helper::Ready:
    case Store::Helper::Outdated:
        // Cleared, so its old text doesn't keep the window tall.
        m_problem->clear();
        m_pages->setCurrentIndex(0);
        break;
    }

    // Tunnels. Rows are rebuilt only when something in them changed, so polling doesn't reset
    // hover or focus.
    QString signature = m_store->selected() + QLatin1Char('|') + QString::number(int(L10n::language())) +
                        QLatin1Char('|') + QString::number(m_store->selectionLocked());
    for (const Tunnel &t : m_store->tunnels())
        signature += t.name + QLatin1Char('\t') + t.displayName() + QLatin1Char('\n');
    if (m_list->property("signature").toString() != signature) {
        m_list->setProperty("signature", signature);
        m_list->clear();
        for (const Tunnel &t : m_store->tunnels()) {
            auto *item = new QListWidgetItem(m_list);
            item->setData(Qt::UserRole, t.name);
            auto *row = new QWidget;
            auto *layout = new QHBoxLayout(row);
            layout->setContentsMargins(8, 2, 2, 2);
            auto *radio = new QRadioButton(t.displayName());
            radio->setChecked(t.name == m_store->selected());
            // The row handles clicks; the radio button only shows which tunnel is chosen.
            radio->setAttribute(Qt::WA_TransparentForMouseEvents);
            radio->setFocusPolicy(Qt::NoFocus);
            // Switching tunnels needs the VPN off first; the row's ⋮ still works.
            if (m_store->selectionLocked() && t.name != m_store->selected()) {
                radio->setEnabled(false);
                row->setToolTip(L("Disconnect to switch tunnels"));
            }
            layout->addWidget(radio, 1);
            auto *more = new QToolButton;
            more->setIcon(themeIcon({"overflow-menu", "view-more-symbolic", "view-more"}));
            if (more->icon().isNull())
                more->setText(QStringLiteral("⋮"));
            more->setAutoRaise(true);
            more->setToolTip(L("More"));
            const QString name = t.name;
            connect(more, &QToolButton::clicked, this,
                    [this, more, name] { showTunnelMenu(name, more->mapToGlobal(QPoint(0, more->height()))); });
            layout->addWidget(more);
            item->setSizeHint(row->sizeHint());
            m_list->setItemWidget(item, row);
        }
        const int rows = qBound(1, m_list->count(), 6);
        const int rowHeight = m_list->count() ? m_list->sizeHintForRow(0) : 32;
        m_list->setFixedHeight(rows * rowHeight + 2 * m_list->frameWidth() + 2);
    }
    m_list->setVisible(!m_store->tunnels().isEmpty());
    m_empty->setVisible(m_store->tunnels().isEmpty());
    m_list->setEnabled(!busy);
    m_emptyTitle->setText(L("No tunnels"));
    m_emptyHint->setText(L("Copy a vpn://… key or choose a .conf file"));
    mute(m_emptyHint);
    m_fromClipboard->setText(L("From Clipboard"));
    m_fromFile->setText(L("From File…"));
    m_fromClipboard->setEnabled(!busy);
    m_fromFile->setEnabled(!busy);

    // Bypass. Only touch the field while the user isn't typing in it.
    m_bypassLabel->setText(L("Bypass"));
    m_bypass->setPlaceholderText(L("Off"));
    m_bypass->setToolTip(L("Subnets separated by commas; they go around the VPN"));
    m_bypassApply->setToolTip(L("Apply"));
    m_bypass->setEnabled(!busy);
    if (!m_bypass->hasFocus()) {
        m_bypass->setText(m_store->excluded().join(QStringLiteral(", ")));
        m_bypassApply->hide();
    }

    // Info line
    const QString error = m_store->error();
    const bool outdated = m_store->helper() == Store::Helper::Outdated;
    const int iconSize = style()->pixelMetric(QStyle::PM_SmallIconSize);
    QString info;
    QColor infoColor = text;
    m_traffic->clear();
    if (!error.isEmpty()) {
        info = error;
        infoColor = red;
        m_infoIcon->setPixmap(themeIcon({"dialog-error", "dialog-error-symbolic"}).pixmap(iconSize));
    } else if (outdated) {
        info = L("The AWG Bar service is outdated; reinstall the package");
        infoColor = Glyph::busyColor;
        m_infoIcon->setPixmap(themeIcon({"dialog-warning", "dialog-warning-symbolic"}).pixmap(iconSize));
    } else if (active && m_store->stats()) {
        const Stats s = *m_store->stats();
        info = host(s.endpoint.isEmpty() ? active->endpoint : s.endpoint);
        infoColor.setAlphaF(0.6);
        m_traffic->setText(QStringLiteral("↓ ") + bytes(s.rxBytes) + QStringLiteral("    ↑ ") + bytes(s.txBytes));
    } else if (const Tunnel *t = active ? active : m_store->selectedTunnel()) {
        // Where the power button will connect to, dimmed, in the same spot as the connected address.
        info = host(t->endpoint);
        infoColor.setAlphaF(0.4);
    }
    m_infoText->setFullText(info);
    QPalette infoPalette = m_infoText->palette();
    infoPalette.setColor(QPalette::WindowText, infoColor);
    m_infoText->setPalette(infoPalette);
    m_infoIcon->setVisible(!error.isEmpty() || outdated);
    m_traffic->setVisible(!m_traffic->text().isEmpty());
    m_copyLog->setText(L("Copy Log"));
    m_copyLog->setVisible(!error.isEmpty() && !m_store->errorLog().isEmpty());
    m_dismiss->setVisible(!error.isEmpty());
    m_dismiss->setToolTip(L("Close"));

    // Footer
    m_add->setEnabled(usable && !busy);
    m_add->setToolTip(L("Add Tunnel"));
    m_menuButton->setToolTip(L("Menu"));

    QTimer::singleShot(0, this, &Window::fitHeight);
}

// Fits the height to what the window shows when that changes: tunnels added or removed, the empty
// state, a helper problem. The window opens before the tunnels arrive, and one sized for the empty
// state would keep its blank space. In between, the user's size stays. Runs once the layouts have
// caught up with what rebuild() showed and hid, then activates the window's own, or resize() would
// stop at the old, taller minimum.
void Window::fitHeight()
{
    layout()->activate();
    const int fitted = sizeHint().height();
    if (fitted != m_fittedHeight) {
        m_fittedHeight = fitted;
        resize(width(), fitted);
    }
}

void Window::showTunnelMenu(const QString &name, const QPoint &globalPos)
{
    QString title = name;
    for (const Tunnel &t : m_store->tunnels())
        if (t.name == name)
            title = t.displayName();

    QMenu menu(this);
    menu.addAction(QIcon::fromTheme(QStringLiteral("edit-rename")), L("Rename…"), this, [this, name, title] {
        bool ok = false;
        const QString text = QInputDialog::getText(this, L("Rename Tunnel"), L("Name:"), QLineEdit::Normal, title, &ok)
                                 .trimmed();
        if (ok && !text.isEmpty() && text != title)
            m_store->rename(name, text);
    });
    menu.addAction(QIcon::fromTheme(QStringLiteral("edit-delete")), L("Delete…"), this, [this, name, title] {
        QMessageBox box(QMessageBox::Warning, L("Delete tunnel %1?").arg(title), L("The config will be deleted permanently."),
                        QMessageBox::NoButton, this);
        QPushButton *remove = box.addButton(L("Delete"), QMessageBox::DestructiveRole);
        box.addButton(L("Cancel"), QMessageBox::RejectRole);
        box.exec();
        if (box.clickedButton() == remove)
            m_store->remove(name);
    });
    menu.exec(globalPos);
}

void Window::importFromClipboard()
{
    m_store->importText(QApplication::clipboard()->text(), QStringLiteral("awg"));
}

void Window::importFromFile()
{
    const QStringList files = QFileDialog::getOpenFileNames(this, L("Choose an AmneziaWG .conf or a file with a vpn:// key"),
                                                            QDir::homePath(), QStringLiteral("*.conf *.txt *.vpn *.json;;*"));
    for (const QString &path : files) {
        QFile file(path);
        if (!file.open(QIODevice::ReadOnly) || file.size() > 1 << 20) {
            m_store->setError(L("Cannot read %1").arg(QFileInfo(path).fileName()));
            continue;
        }
        m_store->importText(QString::fromUtf8(file.readAll()), QFileInfo(path).completeBaseName());
    }
}
