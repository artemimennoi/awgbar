#include "window.h"
#include "glyph.h"
#include "l10n.h"
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
#include <QToolButton>
#include <QUrl>
#include <QVBoxLayout>

namespace {

// Breeze's positive and neutral colors; they read well on light and dark color schemes alike.
const QColor green(0x27, 0xae, 0x60);
const QColor amber(0xf6, 0x74, 0x00);

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

// The status glyph in a tinted circle: green when connected, amber while busy, gray when off.
QPixmap badge(Glyph::State state, const QPalette &palette, qreal dpr)
{
    const int size = 44;
    QColor color = palette.color(QPalette::WindowText);
    if (state == Glyph::State::On)
        color = green;
    else if (state == Glyph::State::Busy)
        color = amber;
    else
        color.setAlphaF(0.55);
    QPixmap pixmap(QSize(size, size) * dpr);
    pixmap.setDevicePixelRatio(dpr);
    pixmap.fill(Qt::transparent);
    QPainter p(&pixmap);
    p.setRenderHint(QPainter::Antialiasing);
    QColor tint = color;
    tint.setAlphaF(0.18);
    p.setPen(Qt::NoPen);
    p.setBrush(tint);
    p.drawEllipse(QRectF(0, 0, size, size));
    const QPixmap glyph = Glyph::status(state, color, 22, dpr);
    const QSizeF g = QSizeF(glyph.size()) / dpr;
    p.drawPixmap(QPointF((size - g.width()) / 2, (size - g.height()) / 2), glyph);
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

QLabel *sectionLabel()
{
    auto *label = new QLabel;
    label->setFont(scaled(label->font(), 0.9));
    return label;
}



} // namespace

// A one-line label that elides with "…" instead of widening the window.
class ElidedLabel : public QLabel {
public:
    void setFullText(const QString &text)
    {
        m_text = text;
        setToolTip(text);
        updateText();
    }
    QSize minimumSizeHint() const override { return QSize(0, QLabel::minimumSizeHint().height()); }
    QSize sizeHint() const override { return QSize(0, QLabel::sizeHint().height()); }

protected:
    void resizeEvent(QResizeEvent *event) override
    {
        QLabel::resizeEvent(event);
        updateText();
    }

private:
    void updateText() { setText(fontMetrics().elidedText(m_text, Qt::ElideRight, width())); }
    QString m_text;
};

Window::Window(Store *store, QMenu *settingsMenu) : m_store(store)
{
    setWindowTitle(QStringLiteral("AWG Bar"));
    setWindowIcon(Glyph::app());
    setMinimumWidth(420);
    resize(520, 0);

    auto *root = new QVBoxLayout(this);
    root->setContentsMargins(16, 16, 16, 16);
    root->setSpacing(10);

    // Status
    auto *header = new QHBoxLayout;
    header->setSpacing(12);
    m_badge = new QLabel;
    m_badge->setFixedSize(44, 44);
    header->addWidget(m_badge);
    auto *titles = new QVBoxLayout;
    titles->setSpacing(0);
    m_title = new ElidedLabel;
    QFont titleFont = scaled(m_title->font(), 1.1);
    titleFont.setWeight(QFont::DemiBold);
    m_title->setFont(titleFont);
    m_subtitle = new ElidedLabel;
    m_subtitle->setTextInteractionFlags(Qt::TextSelectableByMouse);
    titles->addStretch();
    titles->addWidget(m_title);
    titles->addWidget(m_subtitle);
    titles->addStretch();
    header->addLayout(titles, 1);
    m_power = new QPushButton;
    connect(m_power, &QPushButton::clicked, store, &Store::toggle);
    header->addWidget(m_power);
    m_menuButton = new QToolButton;
    m_menuButton->setIcon(QIcon::fromTheme(QStringLiteral("application-menu"),
                                           QIcon::fromTheme(QStringLiteral("open-menu-symbolic"))));
    if (m_menuButton->icon().isNull())
        m_menuButton->setText(QStringLiteral("☰"));
    m_menuButton->setAutoRaise(true);
    m_menuButton->setStyleSheet(QStringLiteral("QToolButton::menu-indicator { image: none; width: 0; }"));
    m_menuButton->setPopupMode(QToolButton::InstantPopup);
    m_menuButton->setMenu(settingsMenu);
    header->addWidget(m_menuButton, 0, Qt::AlignTop);
    root->addLayout(header);

    m_error = new QLabel;
    m_error->setWordWrap(true);
    m_error->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_error->setStyleSheet(QStringLiteral(
        "QLabel { background: rgba(218, 68, 83, 0.15); border: 1px solid rgba(218, 68, 83, 0.6);"
        " border-radius: 4px; padding: 6px 8px; }"));
    m_error->hide();
    root->addWidget(m_error);

    m_pages = new QStackedWidget;
    root->addWidget(m_pages);

    // Page 0: tunnels and split tunnel
    auto *main = new QWidget;
    auto *mainLayout = new QVBoxLayout(main);
    mainLayout->setContentsMargins(0, 0, 0, 0);
    mainLayout->setSpacing(6);
    m_tunnelsLabel = sectionLabel();
    mainLayout->addWidget(m_tunnelsLabel);
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
    m_emptyTitle = new QLabel;
    QFont bold = m_emptyTitle->font();
    bold.setWeight(QFont::DemiBold);
    m_emptyTitle->setFont(bold);
    m_emptyHint = new QLabel;
    m_emptyHint->setWordWrap(true);
    emptyLayout->addWidget(m_emptyTitle);
    emptyLayout->addWidget(m_emptyHint);
    mainLayout->addWidget(m_empty);

    auto *importRow = new QHBoxLayout;
    m_fromClipboard = new QPushButton(QIcon::fromTheme(QStringLiteral("edit-paste")), QString());
    m_fromFile = new QPushButton(QIcon::fromTheme(QStringLiteral("document-open")), QString());
    connect(m_fromClipboard, &QPushButton::clicked, this, &Window::importFromClipboard);
    connect(m_fromFile, &QPushButton::clicked, this, &Window::importFromFile);
    importRow->addWidget(m_fromClipboard);
    importRow->addWidget(m_fromFile);
    importRow->addStretch();
    mainLayout->addLayout(importRow);

    mainLayout->addSpacing(8);
    m_bypassLabel = sectionLabel();
    mainLayout->addWidget(m_bypassLabel);
    auto *bypassRow = new QHBoxLayout;
    m_bypass = new QLineEdit;
    m_bypass->setPlaceholderText(QStringLiteral("10.*, 192.168.0.0/16"));
    m_bypass->setClearButtonEnabled(true);
    m_apply = new QPushButton;
    auto apply = [this] { m_store->setExcluded(m_bypass->text()); };
    connect(m_bypass, &QLineEdit::returnPressed, this, apply);
    connect(m_apply, &QPushButton::clicked, this, apply);
    bypassRow->addWidget(m_bypass, 1);
    bypassRow->addWidget(m_apply);
    mainLayout->addLayout(bypassRow);
    m_pages->addWidget(main);

    // Page 1: the helper isn't usable
    m_problem = new QLabel;
    m_problem->setWordWrap(true);
    m_problem->setTextInteractionFlags(Qt::TextSelectableByMouse);
    m_problem->setAlignment(Qt::AlignTop | Qt::AlignLeft);
    m_pages->addWidget(m_problem);

    connect(store, &Store::changed, this, &Window::rebuild);
    rebuild();
}

void Window::showAndRaise()
{
    show();
    setWindowState((windowState() & ~Qt::WindowMinimized) | Qt::WindowActive);
    raise();
    activateWindow();
}

void Window::closeEvent(QCloseEvent *event)
{
    // The window is only a view; the app keeps running in the tray and the VPN stays up.
    event->ignore();
    hide();
}

void Window::changeEvent(QEvent *event)
{
    // Follow color scheme changes for the badge.
    if (event->type() == QEvent::PaletteChange)
        rebuild();
    QWidget::changeEvent(event);
}

// Secondary text: the window's text color, faded. Not QPalette::PlaceholderText, which Breeze
// for Qt 5 leaves invisible in some containers.
void Window::mute(QWidget *widget)
{
    QPalette p = widget->palette();
    QColor c = palette().color(QPalette::WindowText);
    c.setAlphaF(0.6);
    p.setColor(QPalette::WindowText, c);
    widget->setPalette(p);
}

void Window::rebuild()
{
    for (QWidget *w : {static_cast<QWidget *>(m_subtitle), static_cast<QWidget *>(m_tunnelsLabel),
                       static_cast<QWidget *>(m_bypassLabel), static_cast<QWidget *>(m_emptyHint)})
        mute(w);
    const Tunnel *active = m_store->active();
    const bool busy = m_store->busy();
    const Glyph::State state = busy ? Glyph::State::Busy : active ? Glyph::State::On : Glyph::State::Off;
    m_badge->setPixmap(badge(state, palette(), devicePixelRatioF()));

    if (busy)
        m_title->setFullText(active ? L("Please wait…") : L("Connecting…"));
    else if (active)
        m_title->setFullText(L("Connected · %1").arg(active->displayName()));
    else
        m_title->setFullText(L("Disconnected"));

    QString subtitle;
    if (active && m_store->stats()) {
        const Stats s = *m_store->stats();
        subtitle = host(s.endpoint.isEmpty() ? active->endpoint : s.endpoint) + QStringLiteral(" · ↓ ") +
                   bytes(s.rxBytes) + QStringLiteral(" ↑ ") + bytes(s.txBytes);
    } else if (const Tunnel *t = active ? active : m_store->selectedTunnel()) {
        subtitle = host(t->endpoint);
    }
    m_subtitle->setFullText(subtitle);
    m_subtitle->setVisible(!subtitle.isEmpty());

    const bool usable = m_store->helper() == Store::Helper::Ready || m_store->helper() == Store::Helper::Outdated;
    m_power->setText(active ? L("Disconnect") : L("Connect"));
    m_power->setEnabled(usable && !busy && (active || m_store->selectedTunnel()));
    m_power->setDefault(!active);
    m_menuButton->setToolTip(L("Menu"));

    m_error->setText(m_store->error());
    m_error->setVisible(!m_store->error().isEmpty());

    // Helper problems replace the main page.
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
        m_pages->setCurrentIndex(0);
        break;
    }
    if (m_store->helper() == Store::Helper::Outdated && m_store->error().isEmpty()) {
        m_error->setText(L("The AWG Bar service is outdated; reinstall the package"));
        m_error->show();
    }

    m_tunnelsLabel->setText(L("Tunnels"));
    m_emptyTitle->setText(L("No tunnels"));
    m_emptyHint->setText(L("Copy a vpn://… key or choose a .conf file"));
    m_fromClipboard->setText(L("From Clipboard"));
    m_fromFile->setText(L("From File…"));
    m_bypassLabel->setText(L("Bypass VPN"));
    m_bypass->setToolTip(L("Subnets separated by commas; they go around the VPN"));
    m_apply->setText(L("Apply"));
    m_fromClipboard->setEnabled(!busy);
    m_fromFile->setEnabled(!busy);
    m_apply->setEnabled(!busy);

    // Only touch the field while the user isn't typing in it.
    const QString excluded = m_store->excluded().join(QStringLiteral(", "));
    if (!m_bypass->hasFocus())
        m_bypass->setText(excluded);

    // Rows are rebuilt only when something in them changed, so polling doesn't reset hover or focus.
    QString signature = m_store->selected() + QLatin1Char('|') + QString::number(int(L10n::language()));
    for (const Tunnel &t : m_store->tunnels())
        signature += t.name + QLatin1Char('\t') + t.displayName() + QLatin1Char('\t') + QString::number(t.active) + QLatin1Char('\n');
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
            layout->addWidget(radio, 1);
            auto *more = new QToolButton;
            more->setIcon(QIcon::fromTheme(QStringLiteral("overflow-menu"),
                                           QIcon::fromTheme(QStringLiteral("view-more-symbolic"))));
            if (more->icon().isNull())
                more->setText(QStringLiteral("⋮"));
            more->setAutoRaise(true);
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
