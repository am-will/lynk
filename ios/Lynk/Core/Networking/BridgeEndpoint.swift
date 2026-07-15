import Foundation

enum BridgeEndpointError: LocalizedError, Equatable {
    case missingHost
    case unsupportedScheme

    var errorDescription: String? {
        switch self {
        case .missingHost: "Enter a Lynk bridge URL."
        case .unsupportedScheme: "Bridge URL must use ws:// or wss://."
        }
    }
}

struct BridgeEndpoint: Equatable, Sendable {
    let webSocketURL: URL

    init(_ rawValue: String) throws {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, var components = URLComponents(string: trimmed) else {
            throw BridgeEndpointError.missingHost
        }
        guard components.scheme == "ws" || components.scheme == "wss" else {
            throw BridgeEndpointError.unsupportedScheme
        }
        if components.path.isEmpty || components.path == "/" { components.path = "/phone" }
        guard components.host != nil, let url = components.url else { throw BridgeEndpointError.missingHost }
        self.webSocketURL = url
    }

    var httpBaseURL: URL {
        var components = URLComponents(url: webSocketURL, resolvingAgainstBaseURL: false)!
        components.scheme = components.scheme == "wss" ? "https" : "http"
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.url!
    }
}

