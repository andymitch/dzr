// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "dzr",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "dzr", path: "Sources/dzr")
    ]
)
