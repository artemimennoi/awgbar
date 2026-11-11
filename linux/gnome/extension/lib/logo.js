// The macOS menu's big shield (LogoShield.swift), drawn with cairo as the Qt app's Glyph::logo
// does: outlined when off, half-filled while busy, green with a white gear when on. Geometry is in
// the 1024-point space of Resources/make-icon.py.
import Cairo from 'cairo';

export const State = Object.freeze({OFF: 'off', BUSY: 'busy', ON: 'on'});

// GNOME's palette (the HIG's Green 4 and Orange 3), as every AWG Bar client uses its desktop's
// green and orange for connected and busy.
export const connectedColor = [0x2e / 255, 0xc2 / 255, 0x7e / 255];
export const busyColor = [0xff / 255, 0x78 / 255, 0x00 / 255];

function shieldPath(cr) {
    const m = x => 1024 - x;
    cr.moveTo(512, 226);
    cr.curveTo(596, 270, 676, 290, 744, 292);
    cr.curveTo(758, 292, 768, 302, 768, 316);
    cr.lineTo(768, 470);
    cr.curveTo(768, 636, 654, 742, 512, 800);
    cr.curveTo(m(654), 742, m(768), 636, m(768), 470);
    cr.lineTo(m(768), 316);
    cr.curveTo(m(768), 302, m(758), 292, m(744), 292);
    cr.curveTo(m(676), 290, m(596), 270, 512, 226);
    cr.closePath();
}

// Eight teeth with one pointing straight up; the hole is even-odd. Arcs are short polylines.
function gearPath(cr, cx, cy, tip, root, hole) {
    const teeth = 8;
    const step = 360 / teeth;
    const tipHalf = step * 0.42 / 2, rootHalf = step * 0.62 / 2;
    const point = (r, deg) => [cx + r * Math.cos(deg * Math.PI / 180), cy + r * Math.sin(deg * Math.PI / 180)];
    const arc = (r, from, to) => {
        for (let i = 1; i <= 6; i++)
            cr.lineTo(...point(r, from + (to - from) * i / 6));
    };
    for (let i = 0; i < teeth; i++) {
        const a = -90 + i * step;
        if (i === 0)
            cr.moveTo(...point(root, a - rootHalf));
        else
            cr.lineTo(...point(root, a - rootHalf));
        cr.lineTo(...point(tip, a - tipHalf));
        arc(tip, a - tipHalf, a + tipHalf);
        cr.lineTo(...point(root, a + rootHalf));
        arc(root, a + rootHalf, a + step - rootHalf);
    }
    cr.closePath();
    cr.newSubPath();
    cr.arc(cx, cy, hole, 0, 2 * Math.PI);
    cr.closePath();
}

// Draws the shield centered in a width × height area; `ink` is [r, g, b, a] in 0..1, the text
// color of the button it sits on.
export function drawLogo(cr, state, width, height, ink) {
    const line = 48;
    const box = {x: 256 - line / 2, y: 226 - line / 2, w: 512 + line, h: 574 + line};
    const scale = Math.min(width / box.w, height / box.h);
    const [r, g, b, a] = ink;
    cr.save();
    cr.translate((width - box.w * scale) / 2, (height - box.h * scale) / 2);
    cr.scale(scale, scale);
    cr.translate(-box.x, -box.y);
    cr.setLineWidth(line);
    cr.setLineJoin(Cairo.LineJoin.ROUND);

    let gear = [r, g, b, a];
    switch (state) {
    case State.ON:
        shieldPath(cr);
        cr.setSourceRGBA(...connectedColor, 1);
        cr.fillPreserve();
        cr.stroke();
        gear = [1, 1, 1, 1];
        break;
    case State.BUSY:
        cr.save();
        cr.rectangle(0, 0, 512, 1024);
        cr.clip();
        shieldPath(cr);
        cr.setSourceRGBA(r, g, b, a * 0.25);
        cr.fill();
        cr.restore();
        shieldPath(cr);
        cr.setSourceRGBA(r, g, b, a * 0.55);
        cr.stroke();
        break;
    default:
        shieldPath(cr);
        cr.setSourceRGBA(r, g, b, a * 0.55);
        cr.stroke();
        break;
    }

    cr.setFillRule(Cairo.FillRule.EVEN_ODD);
    gearPath(cr, 512, 500, 150, 118, 78);
    cr.setSourceRGBA(...gear);
    cr.fill();
    // Below 30 pixels the V is a smudge and is left out, as on macOS.
    if (height >= 30) {
        cr.moveTo(470, 467);
        cr.lineTo(512, 541);
        cr.lineTo(554, 467);
        cr.setLineWidth(30);
        cr.setLineCap(Cairo.LineCap.ROUND);
        cr.stroke();
    }
    cr.restore();
}
