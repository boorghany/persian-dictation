import Foundation

/// Client for Google's full-duplex streaming speech endpoint, the same one Chrome's
/// Web Speech API (`webkitSpeechRecognition`) talks to.
///
/// One session = one random `pair` id shared by two HTTPS requests:
///   * upstream:   chunked POST of raw 16 kHz / 16-bit / mono PCM (`audio/l16`)
///   * downstream: GET returning a stream of 4-byte big-endian length-prefixed
///                 `SpeechRecognitionEvent` protobuf messages.
///
/// Every session goes over one shared URLSession, i.e. one HTTP/2 connection to Google. A new TLS
/// connection through the user's VPN often took 1–7 s; with one URLSession per session every
/// handover and every freeze recovery paid that again (and the stall looked like a Google freeze).
final class SpeechStream: NSObject, URLSessionDataDelegate {
    static let baseURL = "https://www.google.com/speech-api/full-duplex/v1"
    static let sampleRate = 16000

    /// Called with the current (non-final) hypothesis whenever it changes.
    var onInterim: ((String) -> Void)?
    /// Called once per finalized utterance.
    var onFinal: ((String) -> Void)?
    /// Called when the session is over, with an error if it failed.
    var onEnd: ((Error?) -> Void)?
    /// Called once, when the first audio has actually been sent, i.e. the session is connected.
    var onConnected: (() -> Void)?
    /// Development mode: receives a copy of every byte uploaded in this session.
    var recorder: SessionRecorder?

    private let apiKey: String
    private let language: String
    private let pair = (0..<8).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()

    private var session: URLSession { SessionRouter.session }
    private var upTask: URLSessionUploadTask?
    private var downTask: URLSessionDataTask?
    private var bodyInput: InputStream?
    private var bodyOutput: OutputStream?
    private let writeQueue = DispatchQueue(label: "SpeechStream.write")
    private var downBuffer = Data()
    private var ended = false
    private var connected = false

    init(apiKey: String, language: String) {
        self.apiKey = apiKey
        self.language = language
        super.init()
    }

    func start() {
        var down = URLComponents(string: Self.baseURL + "/down")!
        down.queryItems = [
            URLQueryItem(name: "key", value: apiKey),
            URLQueryItem(name: "pair", value: pair),
            URLQueryItem(name: "output", value: "pb"),
        ]
        var downRequest = URLRequest(url: down.url!)
        downRequest.timeoutInterval = 120
        let downTask = session.dataTask(with: downRequest)
        self.downTask = downTask
        SessionRouter.register(downTask, self)
        downTask.resume()

        var up = URLComponents(string: Self.baseURL + "/up")!
        up.queryItems = [
            URLQueryItem(name: "key", value: apiKey),
            URLQueryItem(name: "pair", value: pair),
            URLQueryItem(name: "output", value: "pb"),
            URLQueryItem(name: "lang", value: language),
            URLQueryItem(name: "pFilter", value: "0"),
            URLQueryItem(name: "app", value: "chromium"),
        ]
        // Chrome sends these as bare flags without a value.
        let upURL = URL(string: up.url!.absoluteString + "&continuous&interim")!
        var upRequest = URLRequest(url: upURL)
        upRequest.httpMethod = "POST"
        upRequest.timeoutInterval = 120
        upRequest.setValue("audio/l16; rate=\(Self.sampleRate)", forHTTPHeaderField: "Content-Type")

        var input: InputStream?
        var output: OutputStream?
        // Large enough to hold a whole replayed utterance (see ContinuousRecognizer).
        Stream.getBoundStreams(withBufferSize: 2 * 1024 * 1024, inputStream: &input, outputStream: &output)
        bodyInput = input
        bodyOutput = output
        output?.open()

        let upTask = session.uploadTask(withStreamedRequest: upRequest)
        self.upTask = upTask
        SessionRouter.register(upTask, self)
        upTask.resume()
    }

    /// Queue PCM audio (16 kHz, Int16 little-endian, mono) for upload.
    func send(_ pcm: Data) {
        writeQueue.async { [weak self] in
            guard let out = self?.bodyOutput, out.streamStatus == .open else { return }
            self?.recorder?.append(pcm)
            pcm.withUnsafeBytes { raw in
                guard var p = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                var remaining = pcm.count
                while remaining > 0 {
                    let n = out.write(p, maxLength: remaining)
                    if n <= 0 { return }
                    p += n
                    remaining -= n
                }
            }
        }
    }

    /// Stop sending audio. The server then returns the last final result and closes the downstream.
    func finish() {
        writeQueue.async { [weak self] in
            self?.bodyOutput?.close()
            self?.bodyOutput = nil
            self?.closeRecorder()
        }
    }

    /// Must run on `writeQueue`.
    private func closeRecorder() {
        recorder?.close()
        recorder = nil
    }

    func cancel() {
        writeQueue.async { [weak self] in
            self?.bodyOutput?.close()
            self?.bodyOutput = nil
            self?.closeRecorder()
        }
        upTask?.cancel()
        downTask?.cancel()
        ended = true
    }

    // MARK: URLSession delegate

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        completionHandler(bodyInput)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard task === upTask, !connected, !ended else { return }
        connected = true
        onConnected?()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard dataTask === downTask else { return }
        downBuffer.append(data)
        while downBuffer.count >= 4 {
            let b = [UInt8](downBuffer.prefix(4))
            let length = Int(b[0]) << 24 | Int(b[1]) << 16 | Int(b[2]) << 8 | Int(b[3])
            guard downBuffer.count >= 4 + length else { break }
            let message = downBuffer.subdata(in: downBuffer.startIndex + 4 ..< downBuffer.startIndex + 4 + length)
            downBuffer.removeFirst(4 + length)
            handleEvent(message)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if task === upTask {
            if let http = task.response as? HTTPURLResponse, http.statusCode != 200 {
                finishSession(SpeechError.http(http.statusCode))
            } else if let error, (error as NSError).code != NSURLErrorCancelled {
                finishSession(error)
            }
            return
        }
        guard task === downTask else { return }
        if let http = task.response as? HTTPURLResponse, http.statusCode != 200 {
            finishSession(SpeechError.http(http.statusCode))
        } else if let error, (error as NSError).code != NSURLErrorCancelled {
            finishSession(error)
        } else {
            finishSession(nil)
        }
    }

    private func finishSession(_ error: Error?) {
        guard !ended else { return }
        ended = true
        upTask?.cancel()
        writeQueue.async { [weak self] in
            self?.bodyOutput?.close()
            self?.bodyOutput = nil
            self?.closeRecorder()
        }
        onEnd?(error)
    }

    // MARK: Protobuf decoding
    //
    // SpeechRecognitionEvent { 1: status (enum, 0 = success)  2: repeated SpeechRecognitionResult }
    // SpeechRecognitionResult { 1: repeated Alternative  2: final (bool)  3: stability (float) }
    // Alternative { 1: transcript (string)  2: confidence (float) }

    private func handleEvent(_ message: Data) {
        var interim = ""
        for field in ProtoReader.fields(message) {
            switch (field.number, field.value) {
            case (1, .varint(let status)) where status != 0:
                finishSession(SpeechError.status(Int(status)))
                return
            case (2, .bytes(let result)):
                var transcript = ""
                var isFinal = false
                for f in ProtoReader.fields(result) {
                    switch (f.number, f.value) {
                    case (1, .bytes(let alt)) where transcript.isEmpty:
                        for a in ProtoReader.fields(alt) {
                            if a.number == 1, case .bytes(let s) = a.value {
                                transcript = String(decoding: s, as: UTF8.self)
                            }
                        }
                    case (2, .varint(let v)):
                        isFinal = v != 0
                    default:
                        break
                    }
                }
                if isFinal {
                    onFinal?(transcript.trimmingCharacters(in: .whitespaces))
                } else {
                    interim += transcript
                }
            default:
                break
            }
        }
        onInterim?(interim)
    }
}

enum SpeechError: LocalizedError {
    case http(Int)
    case status(Int)

    var errorDescription: String? {
        switch self {
        case .http(let code): return "Google speech server returned HTTP \(code)"
        case .status(let code): return "Google speech server error (status \(code))"
        }
    }
}

/// Minimal protobuf wire-format reader: enough for the handful of fields we need.
enum ProtoReader {
    enum Value {
        case varint(UInt64)
        case fixed32(UInt32)
        case fixed64(UInt64)
        case bytes(Data)
    }

    struct Field {
        let number: Int
        let value: Value
    }

    static func fields(_ data: Data) -> [Field] {
        let bytes = [UInt8](data)
        var i = 0
        var result: [Field] = []

        func varint() -> UInt64? {
            var shift: UInt64 = 0
            var value: UInt64 = 0
            while i < bytes.count {
                let b = bytes[i]
                i += 1
                value |= UInt64(b & 0x7f) << shift
                if b < 0x80 { return value }
                shift += 7
                if shift > 63 { return nil }
            }
            return nil
        }

        while i < bytes.count {
            guard let key = varint() else { break }
            let number = Int(key >> 3)
            switch key & 7 {
            case 0:
                guard let v = varint() else { return result }
                result.append(Field(number: number, value: .varint(v)))
            case 1:
                guard i + 8 <= bytes.count else { return result }
                var v: UInt64 = 0
                for k in 0..<8 { v |= UInt64(bytes[i + k]) << (8 * UInt64(k)) }
                i += 8
                result.append(Field(number: number, value: .fixed64(v)))
            case 2:
                guard let n = varint(), i + Int(n) <= bytes.count else { return result }
                result.append(Field(number: number, value: .bytes(Data(bytes[i..<i + Int(n)]))))
                i += Int(n)
            case 5:
                guard i + 4 <= bytes.count else { return result }
                var v: UInt32 = 0
                for k in 0..<4 { v |= UInt32(bytes[i + k]) << (8 * UInt32(k)) }
                i += 4
                result.append(Field(number: number, value: .fixed32(v)))
            default:
                return result
            }
        }
        return result
    }
}

/// The one URLSession shared by all `SpeechStream`s; forwards each task's callbacks to its stream.
private final class SessionRouter: NSObject, URLSessionDataDelegate {
    static let session: URLSession = {
        let queue = OperationQueue()
        queue.maxConcurrentOperationCount = 1
        return URLSession(configuration: .ephemeral, delegate: SessionRouter(), delegateQueue: queue)
    }()

    private static let lock = NSLock()
    private static var streams: [Int: SpeechStream] = [:]

    static func register(_ task: URLSessionTask, _ stream: SpeechStream) {
        lock.lock(); defer { lock.unlock() }
        streams[task.taskIdentifier] = stream
    }

    private static func stream(for task: URLSessionTask, remove: Bool = false) -> SpeechStream? {
        lock.lock(); defer { lock.unlock() }
        return remove ? streams.removeValue(forKey: task.taskIdentifier) : streams[task.taskIdentifier]
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        guard let stream = Self.stream(for: task) else { return completionHandler(nil) }
        stream.urlSession(session, task: task, needNewBodyStream: completionHandler)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        Self.stream(for: task)?.urlSession(session, task: task, didSendBodyData: bytesSent,
                                           totalBytesSent: totalBytesSent,
                                           totalBytesExpectedToSend: totalBytesExpectedToSend)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        Self.stream(for: dataTask)?.urlSession(session, dataTask: dataTask, didReceive: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        Self.stream(for: task, remove: true)?.urlSession(session, task: task, didCompleteWithError: error)
    }
}
