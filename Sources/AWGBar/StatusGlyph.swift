import AppKit

/// The menu bar icon: the app icon's shield and gear, drawn as a template image so the menu bar
/// tints it. Geometry is in the 1024-point space of `Resources/make-icon.py`; keep the two in step.
enum StatusGlyph {
    enum State { case off, busy, on }

    /// Matches the height of the system's own menu bar icons.
    static let height: CGFloat = 17

    private static let stroke: CGFloat = 64
    // The shield's stroked bounds; the image is cropped to them.
    private static let bounds = CGRect(x: 256 - stroke / 2, y: 226 - stroke / 2, width: 512 + stroke, height: 574 + stroke)

    static func image(_ state: State) -> NSImage {
        let scale = height / bounds.height
        let size = NSSize(width: (bounds.width * scale).rounded(.up), height: height)
        let image = NSImage(size: size, flipped: true) { rect in
            let transform = NSAffineTransform()
            transform.translateX(by: (rect.width - bounds.width * scale) / 2, yBy: 0)
            transform.scale(by: scale)
            transform.translateX(by: -bounds.minX, yBy: -bounds.minY)
            transform.concat()
            // Runs at draw time, so this is the menu bar's appearance: white glyph on dark, black on light.
            let dark = NSAppearance.currentDrawing().bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            draw(state, darkMenuBar: dark)
            return true
        }
        image.isTemplate = true
        image.accessibilityDescription = "AWG Bar"
        return image
    }

    private static func draw(_ state: State, darkMenuBar: Bool) {
        let shield = shieldPath()
        // A light shape on dark reads larger than the same shape dark on light. The gear is solid
        // when off and cut out when on, so which one is light flips with the menu bar; size each
        // so the gear looks the same in every state. The hole stays put.
        let lightGear = darkMenuBar != (state == .on)
        let grow: CGFloat = lightGear ? 0.96 : 1.04
        let gear = gearPath(center: CGPoint(x: 512, y: 510), tip: 168 * grow, root: 128 * grow, hole: 62)
        NSColor.black.set()
        switch state {
        case .off:
            shield.lineWidth = stroke
            shield.stroke()
            gear.fill()
        case .busy:
            NSGraphicsContext.saveGraphicsState()
            NSBezierPath(rect: CGRect(x: 0, y: 0, width: 512, height: 1024)).addClip()
            NSColor.black.withAlphaComponent(0.45).set()
            shield.fill()
            NSGraphicsContext.restoreGraphicsState()
            NSColor.black.set()
            shield.lineWidth = stroke
            shield.stroke()
            gear.fill()
        case .on:
            shield.lineWidth = stroke
            shield.stroke()
            shield.fill()
            NSGraphicsContext.current?.compositingOperation = .destinationOut
            gear.fill()
        }
    }

    /// The right half, top center to bottom tip; the left half is its mirror image.
    static func shieldPath() -> NSBezierPath {
        let mirror = { (p: CGPoint) in CGPoint(x: 1024 - p.x, y: p.y) }
        let path = NSBezierPath()
        path.lineJoinStyle = .round
        path.move(to: CGPoint(x: 512, y: 226))
        path.curve(to: CGPoint(x: 744, y: 292), controlPoint1: CGPoint(x: 596, y: 270), controlPoint2: CGPoint(x: 676, y: 290))
        path.curve(to: CGPoint(x: 768, y: 316), controlPoint1: CGPoint(x: 758, y: 292), controlPoint2: CGPoint(x: 768, y: 302))
        path.line(to: CGPoint(x: 768, y: 470))
        path.curve(to: CGPoint(x: 512, y: 800), controlPoint1: CGPoint(x: 768, y: 636), controlPoint2: CGPoint(x: 654, y: 742))
        path.curve(to: mirror(CGPoint(x: 768, y: 470)), controlPoint1: mirror(CGPoint(x: 654, y: 742)), controlPoint2: mirror(CGPoint(x: 768, y: 636)))
        path.line(to: mirror(CGPoint(x: 768, y: 316)))
        path.curve(to: mirror(CGPoint(x: 744, y: 292)), controlPoint1: mirror(CGPoint(x: 768, y: 302)), controlPoint2: mirror(CGPoint(x: 758, y: 292)))
        path.curve(to: CGPoint(x: 512, y: 226), controlPoint1: mirror(CGPoint(x: 676, y: 290)), controlPoint2: mirror(CGPoint(x: 596, y: 270)))
        path.close()
        return path
    }

    /// Eight teeth with one pointing straight up, so the gear is symmetric too; the hole is even-odd.
    static func gearPath(center c: CGPoint, tip: CGFloat, root: CGFloat, hole: CGFloat) -> NSBezierPath {
        let teeth = 8
        let step = 360 / CGFloat(teeth)
        let tipHalf = step * 0.42 / 2, rootHalf = step * 0.62 / 2
        let point = { (r: CGFloat, deg: CGFloat) in
            CGPoint(x: c.x + r * cos(deg * .pi / 180), y: c.y + r * sin(deg * .pi / 180))
        }
        let path = NSBezierPath()
        path.windingRule = .evenOdd
        for i in 0..<teeth {
            let a = -90 + CGFloat(i) * step
            if i == 0 { path.move(to: point(root, a - rootHalf)) } else { path.line(to: point(root, a - rootHalf)) }
            path.line(to: point(tip, a - tipHalf))
            path.appendArc(withCenter: c, radius: tip, startAngle: a - tipHalf, endAngle: a + tipHalf, clockwise: false)
            path.line(to: point(root, a + rootHalf))
            path.appendArc(withCenter: c, radius: root, startAngle: a + rootHalf, endAngle: a + step - rootHalf, clockwise: false)
        }
        path.close()
        path.appendOval(in: CGRect(x: c.x - hole, y: c.y - hole, width: hole * 2, height: hole * 2))
        return path
    }
}
