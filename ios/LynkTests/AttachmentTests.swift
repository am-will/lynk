import Foundation
import XCTest
@testable import Lynk

final class AttachmentTests: XCTestCase {
    func testPolicyEnforcesCountItemAndAggregateLimits() throws {
        let small = attachment(size: 1)
        XCTAssertNoThrow(try AttachmentPolicy.validate(existing: Array(repeating: small, count: 7), adding: 1, name: "ok"))
        XCTAssertThrowsError(try AttachmentPolicy.validate(existing: Array(repeating: small, count: 8), adding: 1, name: "many"))
        XCTAssertThrowsError(try AttachmentPolicy.validate(existing: [], adding: 0, name: "empty"))
        XCTAssertThrowsError(try AttachmentPolicy.validate(existing: [], adding: AttachmentPolicy.maximumItemBytes + 1, name: "large"))
        let fiftyMiB = attachment(size: AttachmentPolicy.maximumItemBytes)
        XCTAssertThrowsError(try AttachmentPolicy.validate(existing: [fiftyMiB, fiftyMiB], adding: 1, name: "aggregate"))
    }

    func testImporterCopiesPrivatelyAndCalculatesLowercaseSHA256() throws {
        let source = FileManager.default.temporaryDirectory.appending(path: "lynk-attachment-\(UUID().uuidString).txt")
        try Data("hello".utf8).write(to: source)
        defer { try? FileManager.default.removeItem(at: source) }

        let imported = try AttachmentImporter().importFile(at: source, existing: [])
        defer { try? FileManager.default.removeItem(at: imported.localURL) }

        XCTAssertNotEqual(imported.localURL, source)
        XCTAssertEqual(imported.sizeBytes, 5)
        XCTAssertEqual(imported.mimeType, "text/plain")
        XCTAssertEqual(imported.sha256, "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824")
    }

    func testUploadRequestUsesRawPutContractAndOwnerHeaders() throws {
        let item = attachment(size: 5)
        let request = try AttachmentUploader.makeRequest(
            attachment: item,
            endpoint: try BridgeEndpoint("wss://bridge.example.test/custom"),
            token: "token",
            deviceID: "iphone",
            sessionKey: "codex:session"
        )

        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.scheme, "https")
        XCTAssertEqual(request.url?.path, "/api/blobs/\(item.id)")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer token")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Lynk-Device-Id"), "iphone")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Lynk-Session-Key"), "codex:session")
        XCTAssertNil(request.httpBody)
        let query = URLComponents(url: try XCTUnwrap(request.url), resolvingAgainstBaseURL: false)?.queryItems
        XCTAssertEqual(query?.first(where: { $0.name == "sha256" })?.value, item.sha256)
    }

    func testUploadResponseMustMatchMetadata() throws {
        let item = attachment(size: 5)
        let response = try XCTUnwrap(HTTPURLResponse(
            url: URL(string: "https://bridge.test")!,
            statusCode: 201,
            httpVersion: nil,
            headerFields: nil
        ))
        let valid = try JSONSerialization.data(withJSONObject: [
            "ok": true,
            "blob": ["id": item.id, "sha256": item.sha256, "sizeBytes": item.sizeBytes]
        ])
        XCTAssertNoThrow(try AttachmentUploader.validate(data: valid, response: response, attachment: item))

        let invalid = try JSONSerialization.data(withJSONObject: [
            "ok": true,
            "blob": ["id": item.id, "sha256": item.sha256, "sizeBytes": 4]
        ])
        XCTAssertThrowsError(try AttachmentUploader.validate(data: invalid, response: response, attachment: item))
    }

    func testReferenceContainsNoLocalPathOrBytes() throws {
        let data = try JSONEncoder().encode(attachment(size: 5).reference.json)
        let text = try XCTUnwrap(String(data: data, encoding: .utf8))
        XCTAssertFalse(text.contains("localURL"))
        XCTAssertFalse(text.contains("localPath"))
        XCTAssertFalse(text.contains("contentBase64"))
    }

    private func attachment(size: Int64) -> StoredChatAttachment {
        StoredChatAttachment(
            id: "blob_12345678",
            kind: "file",
            displayName: "notes.txt",
            mimeType: "text/plain",
            sizeBytes: size,
            localURL: URL(fileURLWithPath: "/tmp/notes.txt"),
            sha256: String(repeating: "a", count: 64)
        )
    }
}
