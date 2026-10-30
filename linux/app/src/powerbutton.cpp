#include "powerbutton.h"

#include <QPainter>
#include <QRadialGradient>
#include <QtMath>

namespace {

const int diameter = 112;
// Room around the circle for the halo at its fullest.
const int side = 150;

QColor mix(const QColor &a, const QColor &b, qreal t)
{
    return QColor::fromRgbF(a.redF() + (b.redF() - a.redF()) * t, a.greenF() + (b.greenF() - a.greenF()) * t,
                            a.blueF() + (b.blueF() - a.blueF()) * t);
}

} // namespace

PowerButton::PowerButton(QWidget *parent) : QAbstractButton(parent)
{
    setCursor(Qt::PointingHandCursor);
    setFocusPolicy(Qt::StrongFocus);
    setAttribute(Qt::WA_Hover);
    setSizePolicy(QSizePolicy::Fixed, QSizePolicy::Fixed);
    m_breath.setStartValue(0.0);
    m_breath.setEndValue(1.0);
    m_breath.setLoopCount(-1);
    connect(&m_breath, &QVariantAnimation::valueChanged, this, [this] { update(); });
}

QSize PowerButton::sizeHint() const
{
    return QSize(side, side);
}

QRectF PowerButton::circle() const
{
    return QRectF((width() - diameter) / 2.0, (height() - diameter) / 2.0, diameter, diameter);
}

bool PowerButton::hitButton(const QPoint &pos) const
{
    const QPointF d = QPointF(pos) - circle().center();
    return QPointF::dotProduct(d, d) <= diameter * diameter / 4.0;
}

void PowerButton::setState(Glyph::State state)
{
    if (state == m_state)
        return;
    m_state = state;
    updateBreathing();
    update();
}

void PowerButton::showEvent(QShowEvent *event)
{
    QAbstractButton::showEvent(event);
    updateBreathing();
}

void PowerButton::hideEvent(QHideEvent *event)
{
    QAbstractButton::hideEvent(event);
    // Nothing to see in the tray: no reason to keep waking up.
    m_breath.stop();
}

// One breath in and out at the macOS pace: 4.4 s connected, 1.4 s busy. A new pace carries on
// from the same point of the breath, so the halo doesn't jump when the tunnel comes up.
void PowerButton::updateBreathing()
{
    if (m_state == Glyph::State::Off || !isVisible()) {
        m_breath.stop();
        return;
    }
    const int cycle = m_state == Glyph::State::Busy ? 1400 : 4400;
    const bool running = m_breath.state() == QAbstractAnimation::Running;
    if (running && m_breath.duration() == cycle)
        return;
    const qreal phase = running ? qreal(m_breath.currentLoopTime()) / m_breath.duration() : 0;
    m_breath.stop();
    m_breath.setDuration(cycle);
    m_breath.start();
    m_breath.setCurrentTime(int(phase * cycle));
}

void PowerButton::paintEvent(QPaintEvent *)
{
    QPainter p(this);
    p.setRenderHint(QPainter::Antialiasing);
    const QPalette &pal = palette();
    const QRectF c = circle();
    const QPointF center = c.center();
    const qreal r = diameter / 2.0;

    if (m_state != Glyph::State::Off) {
        const qreal b = (1 - qCos(2 * M_PI * m_breath.currentValue().toReal())) / 2;
        const qreal halo = r * (0.96 + 0.28 * b);
        QColor color = m_state == Glyph::State::Busy ? Glyph::busyColor : Glyph::connectedColor;
        color.setAlphaF(0.18 + 0.14 * b);
        QColor clear = color;
        clear.setAlphaF(0);
        QRadialGradient glow(center, halo);
        glow.setColorAt(0, color);
        glow.setColorAt(0.75, color);
        glow.setColorAt(1, clear);
        p.setPen(Qt::NoPen);
        p.setBrush(glow);
        p.drawEllipse(center, halo, halo);
    }

    // The palette's button, tinted green while connected; outlined like Breeze's buttons, in the
    // highlight color under the pointer or with keyboard focus.
    QColor fill = pal.color(QPalette::Button);
    if (m_state == Glyph::State::On)
        fill = mix(fill, Glyph::connectedColor, 0.25);
    if (isDown())
        fill = fill.darker(108);
    QColor border = mix(pal.color(QPalette::Button), pal.color(QPalette::ButtonText), 0.25);
    if (isEnabled() && (underMouse() || hasFocus()))
        border = pal.color(QPalette::Highlight);
    p.setPen(QPen(border, 1));
    p.setBrush(fill);
    p.drawEllipse(c.adjusted(0.5, 0.5, -0.5, -0.5));

    if (!isEnabled() && m_state == Glyph::State::Off)
        p.setOpacity(0.5);
    // The shield is 50 of the Mac button's 88 points.
    const qreal h = diameter * 0.57;
    Glyph::logo(p, m_state, QRectF(center.x() - h / 2, center.y() - h / 2, h, h), pal.color(QPalette::ButtonText));
}
