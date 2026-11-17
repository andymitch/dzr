// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "dzr",
    platforms: [.macOS("26.0")],
    targets: [
        .executableTarget(name: "dzr", path: "Sources/dzr"),
        .testTarget(name: "dzrTests", dependencies: ["dzr"], path: "Tests/dzrTests"),
    ]
)
