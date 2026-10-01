import AVFAudio
import Foundation
import Testing
import os

@Suite(.serialized)
struct GatewayUploadClientTests {
    @Test func clientUploadsCompressedAudioWithTheMatchingContentType() async throws {
        let source = try makeRecording()
        defer { try? FileManager.default.removeItem(at: source) }
        let original = try Data(contentsOf: source)
        UploadProtocol.state.withLock { $0 = .init(status: 200) }
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let client = GatewayClient(
            baseURL: URL(string: "https://gateway.invalid")!,
            token: String(repeating: "x", count: 32), session: session
        )
        let result = try await client.uploadAudio(sessionID: UUID(), fileURL: source)
        #expect(result.state == "uploaded")
        let requests = UploadProtocol.state.withLock { $0.requests }
        #expect(requests.count == 1)
        #expect(requests.first?.httpMethod == "PUT")
        #expect(requests.first?.value(forHTTPHeaderField: "Content-Type") == "audio/mp4")
        #expect(requests.first?.value(forHTTPHeaderField: "Authorization") == "Bearer " + String(repeating: "x", count: 32))
        #expect(try Data(contentsOf: source) == original)
    }

    @Test func failedUploadKeepsTheWAVAndDoesNotSendAnotherRequest() async throws {
        let source = try makeRecording()
        defer { try? FileManager.default.removeItem(at: source) }
        let original = try Data(contentsOf: source)
        UploadProtocol.state.withLock { $0 = .init(status: 503) }
        let session = makeSession()
        defer { session.invalidateAndCancel() }
        let client = GatewayClient(
            baseURL: URL(string: "https://gateway.invalid")!,
            token: String(repeating: "x", count: 32), session: session
        )
        await #expect(throws: GatewayError.self) {
            try await client.uploadAudio(sessionID: UUID(), fileURL: source)
        }
        #expect(UploadProtocol.state.withLock { $0.requests.count } == 1)
        #expect(try Data(contentsOf: source) == original)
    }

    private func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [UploadProtocol.self]
        return URLSession(configuration: configuration)
    }

    private func makeRecording() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathExtension("wav")
        let format = try #require(AVAudioFormat(
            commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false
        ))
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 16_000))
        buffer.frameLength = 16_000
        let samples = try #require(buffer.floatChannelData?[0])
        for index in 0..<16_000 { samples[index] = sin(Float(index) * 0.1) * 0.4 }
        let file = try AVAudioFile(
            forWriting: url, settings: AudioCapturePipeline.fileSettings(),
            commonFormat: .pcmFormatFloat32, interleaved: false
        )
        try file.write(from: buffer)
        return url
    }

    private final class UploadProtocol: URLProtocol, @unchecked Sendable {
        struct State {
            let status: Int
            var requests: [URLRequest] = []
        }
        static let state = OSAllocatedUnfairLock(initialState: State(status: 200))

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func stopLoading() {}

        override func startLoading() {
            let status = Self.state.withLock {
                $0.requests.append(request)
                return $0.status
            }
            let response = HTTPURLResponse(
                url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            let body = status == 200
                ? """
                {"session_id":"00000000-0000-0000-0000-000000000001",
                 "job_id":"test","state":"uploaded"}
                """
                : "{\"error\":{\"code\":\"unavailable\"}}"
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
}
