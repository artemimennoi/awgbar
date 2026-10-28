#pragma once

#include <QColor>
#include <QIcon>
#include <QPixmap>

// The shield and gear of the macOS app, drawn with QPainter so the app needs no SVG module.
// Geometry is in the 1024-point space of Resources/make-icon.py and StatusGlyph.swift.
namespace Glyph {
enum class State { Off, Busy, On };

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
