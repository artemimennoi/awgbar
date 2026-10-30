#pragma once

#include "glyph.h"

#include <QAbstractButton>
#include <QVariantAnimation>

// The macOS menu's power button in the desktop's own colors: the app's shield on a round button,
// with a soft halo breathing behind it while connected (slowly) or busy (quickly).
class PowerButton : public QAbstractButton {
    Q_OBJECT
public:
    explicit PowerButton(QWidget *parent = nullptr);
    void setState(Glyph::State state);
    QSize sizeHint() const override;

protected:
    void paintEvent(QPaintEvent *event) override;
    void showEvent(QShowEvent *event) override;
    void hideEvent(QHideEvent *event) override;
    // Only the circle is the button, not the room around it for the halo.
    bool hitButton(const QPoint &pos) const override;

private:
    QRectF circle() const;
    void updateBreathing();

    Glyph::State m_state = Glyph::State::Off;
    // Linear 0 to 1 over one breath, in and out; the halo eases it like a sine.
    QVariantAnimation m_breath;
};
