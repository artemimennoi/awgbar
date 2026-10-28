import SwiftUI

/// The app icon's shield, gear and V in color, as the Android app draws them: an outlined shield
/// when off, a green one with a white gear when connected, half-filled while busy. Shares its
/// geometry with the menu bar glyph; the gear and V are the app icon's (`Resources/make-icon.py`).
struct LogoShield: View {
    let state: StatusGlyph.State

    private static let stroke: CGFloat = 48
    private static let box = CGRect(x: 256 - stroke / 2, y: 226 - stroke / 2, width: 512 + stroke, height: 574 + stroke)
    private static let shield = Path(StatusGlyph.shieldPath().cgPath)
    private static let gear = Path(StatusGlyph.gearPath(center: CGPoint(x: 512, y: 500), tip: 150, root: 118, hole: 78).cgPath)
    private static let v = Path { p in
        p.move(to: CGPoint(x: 470, y: 467))
        p.addLine(to: CGPoint(x: 512, y: 541))
        p.addLine(to: CGPoint(x: 554, y: 467))
    }

    var body: some View {
        Canvas { ctx, size in
            let box = Self.box
            let scale = min(size.width / box.width, size.height / box.height)
            ctx.translateBy(x: (size.width - box.width * scale) / 2, y: (size.height - box.height * scale) / 2)
            ctx.scaleBy(x: scale, y: scale)
            ctx.translateBy(x: -box.minX, y: -box.minY)

            let outline = StrokeStyle(lineWidth: Self.stroke, lineJoin: .round)
            let ink: GraphicsContext.Shading
            switch state {
            case .on:
                ctx.fill(Self.shield, with: .color(.green))
                ctx.stroke(Self.shield, with: .color(.green), style: outline)
                ink = .color(.white)
            case .busy:
                var left = ctx
                left.clip(to: Path(CGRect(x: 0, y: 0, width: 512, height: 1024)))
                left.fill(Self.shield, with: .style(.tertiary))
                ctx.stroke(Self.shield, with: .style(.secondary), style: outline)
                ink = .style(.primary)
            case .off:
                ctx.stroke(Self.shield, with: .style(.secondary), style: outline)
                ink = .style(.primary)
            }
            ctx.fill(Self.gear, with: ink, style: FillStyle(eoFill: true))
            // Below about 30 points the V is a smudge; the menu bar glyph leaves it out too.
            if size.height >= 30 {
                ctx.stroke(Self.v, with: ink, style: StrokeStyle(lineWidth: 30, lineCap: .round, lineJoin: .round))
            }
        }
        .aspectRatio(Self.box.width / Self.box.height, contentMode: .fit)
        .accessibilityHidden(true)
    }
}
