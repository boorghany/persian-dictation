// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "PersianDictation",
    platforms: [.macOS(.v13)],
    targets: [
        .executableTarget(
            name: "PersianDictation",
            path: "Sources/PersianDictation"
        ),
    ]
)
