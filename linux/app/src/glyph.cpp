#include "glyph.h"

#include <QLinearGradient>
#include <QPainter>
#include <QPainterPath>
#include <QPainterPathStroker>
#include <QtMath>

namespace {

// The tray glyph's shield: right half from the top center to the bottom tip, then its mirror image.
QPainterPath shieldPath()
{
    auto m = [](qreal x, qreal y) { return QPointF(1024 - x, y); };
    QPainterPath p;
    p.moveTo(512, 226);
    p.cubicTo(596, 270, 676, 290, 744, 292);
    p.cubicTo(758, 292, 768, 302, 768, 316);
    p.lineTo(768, 470);
    p.cubicTo(768, 636, 654, 742, 512, 800);
    p.cubicTo(m(654, 742), m(768, 636), m(768, 470));
    p.lineTo(m(768, 316));
    p.cubicTo(m(768, 302), m(758, 292), m(744, 292));
    p.cubicTo(m(676, 290), m(596, 270), QPointF(512, 226));
    p.closeSubpath();
    return p;
}

// The app icon's shield, from Resources/AppIcon.icon/Assets/shield.svg.
QPainterPath appShieldPath()
{
    QPainterPath p;
    p.moveTo(512, 156.58);
    p.cubicTo(616.39, 211.26, 715.81, 236.12, 800.31, 238.60);
    p.cubicTo(817.71, 238.60, 830.14, 251.03, 830.14, 268.43);
    p.lineTo(830.14, 459.81);
    p.cubicTo(830.14, 666.10, 688.47, 797.83, 512, 869.90);
    p.cubicTo(335.53, 797.83, 193.86, 666.10, 193.86, 459.81);
    p.lineTo(193.86, 268.43);
    p.cubicTo(193.86, 251.03, 206.29, 238.60, 223.69, 238.60);
    p.cubicTo(308.19, 236.12, 407.61, 211.26, 512, 156.58);
    p.closeSubpath();
    return p;
}

// Eight teeth with one pointing straight up, so the gear is symmetric too; the hole is odd-even.
// Arcs are short polylines: at icon sizes nobody can tell.
QPainterPath gearPath(QPointF c, qreal tip, qreal root, qreal hole)
{
    const int teeth = 8;
    const qreal step = 360.0 / teeth;
    const qreal tipHalf = step * 0.42 / 2, rootHalf = step * 0.62 / 2;
    auto point = [c](qreal r, qreal deg) {
        return QPointF(c.x() + r * qCos(qDegreesToRadians(deg)), c.y() + r * qSin(qDegreesToRadians(deg)));
    };
    auto arc = [&](QPainterPath &p, qreal r, qreal from, qreal to) {
        for (int i = 1; i <= 6; ++i)
            p.lineTo(point(r, from + (to - from) * i / 6));
    };
    QPainterPath p;
    p.setFillRule(Qt::OddEvenFill);
    for (int i = 0; i < teeth; ++i) {
        const qreal a = -90 + i * step;
        if (i == 0)
            p.moveTo(point(root, a - rootHalf));
        else
            p.lineTo(point(root, a - rootHalf));
        p.lineTo(point(tip, a - tipHalf));
        arc(p, tip, a - tipHalf, a + tipHalf);
        p.lineTo(point(root, a + rootHalf));
        arc(p, root, a + rootHalf, a + step - rootHalf);
    }
    p.closeSubpath();
    p.addEllipse(c, hole, hole);
    return p;
}

const qreal stroke = 64;
// The shield's stroked bounds; the glyph is cropped to them.
const QRectF bounds(256 - stroke / 2, 226 - stroke / 2, 512 + stroke, 574 + stroke);

QString themeName(Glyph::State state)
{
    switch (state) {
    case Glyph::State::On:
        return QStringLiteral("awgbar-on-symbolic");
    case Glyph::State::Busy:
        return QStringLiteral("awgbar-busy-symbolic");
    case Glyph::State::Off:
        break;
    }
    return QStringLiteral("awgbar-off-symbolic");
}

} // namespace

QPixmap Glyph::status(State state, const QColor &color, int height, qreal dpr)
{
    const qreal scale = height / bounds.height();
    const QSize size(qCeil(bounds.width() * scale), height);
    QPixmap pixmap(size * dpr);
    pixmap.setDevicePixelRatio(dpr);
    pixmap.fill(Qt::transparent);

    QPainter p(&pixmap);
    p.setRenderHint(QPainter::Antialiasing);
    p.scale(scale, scale);
    p.translate(-bounds.topLeft());

    const QPainterPath shield = shieldPath();
    const QPainterPath gear = gearPath(QPointF(512, 510), 168, 128, 62);
    QPen pen(color, stroke);
    pen.setJoinStyle(Qt::RoundJoin);

    switch (state) {
    case State::Off:
        p.strokePath(shield, pen);
        p.fillPath(gear, color);
        break;
    case State::Busy: {
        p.save();
        p.setClipRect(QRectF(0, 0, 512, 1024));
        QColor half = color;
        half.setAlphaF(0.45);
        p.fillPath(shield, half);
        p.restore();
        p.strokePath(shield, pen);
        p.fillPath(gear, color);
        break;
    }
    case State::On:
        p.strokePath(shield, pen);
        p.fillPath(shield, color);
        p.setCompositionMode(QPainter::CompositionMode_DestinationOut);
        p.fillPath(gear, Qt::black);
        break;
    }
    return pixmap;
}

static QByteArray svgPath(const QPainterPath &path)
{
    QByteArray d;
    auto num = [](qreal v) { return QByteArray::number(v, 'f', 1); };
    for (int i = 0; i < path.elementCount(); ++i) {
        const QPainterPath::Element e = path.elementAt(i);
        switch (e.type) {
        case QPainterPath::MoveToElement:
            d += "M" + num(e.x) + "," + num(e.y);
            break;
        case QPainterPath::LineToElement:
            d += "L" + num(e.x) + "," + num(e.y);
            break;
        case QPainterPath::CurveToElement: {
            const QPainterPath::Element c2 = path.elementAt(i + 1), end = path.elementAt(i + 2);
            d += "C" + num(e.x) + "," + num(e.y) + " " + num(c2.x) + "," + num(c2.y) + " " + num(end.x) + "," + num(end.y);
            i += 2;
            break;
        }
        case QPainterPath::CurveToDataElement:
            break;
        }
    }
    return d;
}

QByteArray Glyph::symbolicSvg(State state)
{
    // Boolean path operations instead of masks: Plasma renders icons with QtSvg, which has none.
    QPainterPathStroker stroker;
    stroker.setWidth(stroke);
    stroker.setJoinStyle(Qt::RoundJoin);
    const QPainterPath shield = shieldPath();
    const QPainterPath outline = stroker.createStroke(shield);
    const QPainterPath gear = gearPath(QPointF(512, 510), 168, 128, 62);

    QByteArray body;
    const QByteArray open = "<path class=\"ColorScheme-Text\" fill=\"currentColor\" fill-rule=\"evenodd\"";
    switch (state) {
    case State::On:
        body = open + " d=\"" + svgPath(outline.united(shield).subtracted(gear)) + "\"/>";
        break;
    case State::Busy: {
        QPainterPath left;
        left.addRect(QRectF(0, 0, 512, 1024));
        body = open + " fill-opacity=\"0.45\" d=\"" + svgPath(shield.intersected(left).subtracted(gear)) + "\"/>";
        body += open + " d=\"" + svgPath(outline.united(gear)) + "\"/>";
        break;
    }
    case State::Off:
        body = open + " d=\"" + svgPath(outline.united(gear)) + "\"/>";
        break;
    }
    // Square, with the glyph centered: tray icons are laid out as squares.
    const qreal side = bounds.height();
    const QRectF box(512 - side / 2, bounds.top(), side, side);
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\" viewBox=\"" +
           QByteArray::number(box.x(), 'f', 1) + " " + QByteArray::number(box.y(), 'f', 1) + " " +
           QByteArray::number(side, 'f', 1) + " " + QByteArray::number(side, 'f', 1) + "\">\n" +
           "<style type=\"text/css\" id=\"current-color-scheme\">.ColorScheme-Text { color:#232629; }</style>\n" +
           body + "\n</svg>\n";
}

QIcon Glyph::tray(State state, const QColor &fallbackColor)
{
    const QString name = themeName(state);
    if (QIcon::hasThemeIcon(name))
        return QIcon::fromTheme(name);
    QIcon icon;
    for (int size : {16, 22, 24, 32, 48, 64})
        icon.addPixmap(status(state, fallbackColor, size, 1.0));
    return icon;
}

QIcon Glyph::app()
{
    if (QIcon::hasThemeIcon(QStringLiteral("awgbar")))
        return QIcon::fromTheme(QStringLiteral("awgbar"));
    QIcon icon;
    for (int size : {16, 22, 32, 48, 64, 128, 256}) {
        QPixmap pixmap(size, size);
        pixmap.fill(Qt::transparent);
        QPainter p(&pixmap);
        p.setRenderHint(QPainter::Antialiasing);
        p.scale(size / 1024.0, size / 1024.0);

        QLinearGradient background(0, 40, 0, 984);
        background.setColorAt(0, QColor(0x3b, 0x3f, 0x47));
        background.setColorAt(1, QColor(0x10, 0x11, 0x14));
        QPainterPath square;
        square.addRoundedRect(QRectF(40, 40, 944, 944), 210, 210);
        p.fillPath(square, background);

        QLinearGradient silver(0, 156, 0, 870);
        silver.setColorAt(0, Qt::white);
        silver.setColorAt(1, QColor(0xb9, 0xbe, 0xc8));
        p.fillPath(appShieldPath(), silver);

        const QColor dark(0x1a, 0x1c, 0x21);
        p.fillPath(gearPath(QPointF(512, 497.09), 186.41, 146.64, 96.93), dark);
        QPainterPath chevron;
        chevron.moveTo(459.81, 456.08);
        chevron.lineTo(512, 548.04);
        chevron.lineTo(564.19, 456.08);
        QPen pen(dark, 37.28, Qt::SolidLine, Qt::RoundCap, Qt::RoundJoin);
        p.strokePath(chevron, pen);
        p.end();
        icon.addPixmap(pixmap);
    }
    return icon;
}
