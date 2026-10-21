// swift-tools-version:5.9
// Pure Swift + Foundation (no Apple-only frameworks), so the tests also run on Linux.
import PackageDescription

let package = Package(
    name: "HRVCore",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "HRVCore", targets: ["HRVCore"]),
    ],
    targets: [
        .target(name: "HRVCore"),
        .testTarget(name: "HRVCoreTests", dependencies: ["HRVCore"]),
    ]
)
