import Foundation

/// Development mode: saves the exact audio sent to one Google session as a 16 kHz mono WAV file,
/// so a recognition problem can be reproduced by replaying it (`PersianDictation --transcribe`).
final class SessionRecorder {
    static let directory = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/PersianDictation/recordings")

    let url: URL
    private let handle: FileHandle
    private var dataBytes: UInt32 = 0

    init?(name: String) {
        try? FileManager.default.createDirectory(at: Self.directory, withIntermediateDirectories: true)
        url = Self.directory.appendingPathComponent(name + ".wav")
        guard FileManager.default.createFile(atPath: url.path, contents: Self.header(dataBytes: 0)),
              let handle = try? FileHandle(forWritingTo: url) else { return nil }
        handle.seekToEndOfFile()
        self.handle = handle
    }

    /// Append 16-bit little-endian PCM. Not thread-safe: call from one queue.
    func append(_ pcm: Data) {
        handle.write(pcm)
        dataBytes += UInt32(pcm.count)
    }

    /// Fix up the WAV header sizes and close the file.
    func close() {
        handle.seek(toFileOffset: 0)
        handle.write(Self.header(dataBytes: dataBytes))
        try? handle.close()
    }

    /// Delete recordings older than `days`, so development mode doesn't fill the disk.
    static func pruneOld(days: Double = 7) {
        let cutoff = Date().addingTimeInterval(-days * 86400)
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: [.contentModificationDateKey])) ?? []
        for file in files {
            let modified = (try? file.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
            if let modified, modified < cutoff { try? FileManager.default.removeItem(at: file) }
        }
    }

    private static func header(dataBytes: UInt32) -> Data {
        let rate = UInt32(SpeechStream.sampleRate)
        var d = Data()
        func u32(_ v: UInt32) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { withUnsafeBytes(of: v.littleEndian) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8)); u32(36 + dataBytes)
        d.append(contentsOf: Array("WAVE".utf8))
        d.append(contentsOf: Array("fmt ".utf8)); u32(16)
        u16(1); u16(1); u32(rate); u32(rate * 2); u16(2); u16(16) // PCM, mono, 16-bit
        d.append(contentsOf: Array("data".utf8)); u32(dataBytes)
        return d
    }
}
