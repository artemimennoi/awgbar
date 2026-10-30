#pragma once

#include <QColor>
#include <QIcon>
#include <QPixmap>

class QPainter;
class QRectF;

// The shield and gear of the macOS app, drawn with QPainter so the app needs no SVG module.
// Geometry is in the 1024-point space of Resources/make-icon.py and StatusGlyph.swift.
namespace Glyph {
enum class State { Off, Busy, On };

// Connected and busy, colored as every AWG Bar client colors them: here Breeze's positive and
// neutral colors, which read well on light and dark color schemes alike.
inline const QColor connectedColor(0x27, 0xae, 0x60);
inline const QColor busyColor(0xf6, 0x74, 0x00);

// The macOS menu's big shield (LogoShield.swift), centered in `rect`: outlined when off,
// half-filled while busy, green with a white gear when on. `ink` draws the gear and outline
// otherwise. Below 30 pixels the V is a smudge and is left out, as on macOS.
void logo(QPainter &painter, State state, const QRectF &rect, const QColor &ink);

// The monochrome tray glyph in `color`, `height` logical pixels tall.
QPixmap status(State state, const QColor &color, int height, qreal devicePixelRatio);
// Tray icon: the installed theme icon (which Plasma and GNOME recolor to match the panel) or,
// when running uninstalled, the glyph drawn in `fallbackColor`.
QIcon tray(State state, const QColor &fallbackColor);
// The full-color app icon: silver shield, dark gear, dark rounded square.
QIcon app();
// The tray glyph as a symbolic SVG for the icon theme, in the Breeze convention: one color,
// "ColorScheme-Text", which Plasma swaps for the panel's text color (GNOME recolors any symbolic icon).
QByteArray symbolicSvg(State state);
} // namespace Glyph
