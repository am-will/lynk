// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "LynkLlamaRuntime",
    platforms: [
        .iOS(.v17),
    ],
    products: [
        .library(
            name: "LynkLlamaRuntime",
            targets: ["LynkLlamaRuntime"]
        ),
    ],
    targets: [
        .binaryTarget(
            name: "llama",
            url: "https://github.com/PrismML-Eng/llama.cpp/releases/download/prism-b9591-62061f9/llama-prism-b9591-62061f9-xcframework.zip",
            checksum: "b2e1108cc60f49e4b929edac582ed81b0523327b189b0110f8114926706ccb22"
        ),
        .target(
            name: "LynkLlamaRuntime",
            dependencies: ["llama"]
        ),
    ]
)
