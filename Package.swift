// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "AWGBar",
    platforms: [.macOS("26.0")],
    targets: [
        .target(name: "AWGShared"),
        .executableTarget(name: "AWGBar", dependencies: ["AWGShared"]),
        .executableTarget(name: "awgbar-helper", dependencies: ["AWGShared"]),
    ]
)
