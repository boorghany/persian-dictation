import Foundation
import Security

/// Appends lines to ~/Library/Logs/PersianDictation.log for troubleshooting.
enum DiagnosticsLog {
    static let url = FileManager.default.homeDirectoryForCurrentUser
        .appendingPathComponent("Library/Logs/PersianDictation.log")

    /// In development mode, also log raw recognizer text.
    static var isVerbose: Bool { Settings.developmentMode }

    static func verbose(_ message: @autoclosure () -> String) {
        if isVerbose { write(message()) }
    }

    static func write(_ message: String) {
        let line = "\(ISO8601DateFormatter().string(from: Date())) [\(getpid())] \(message)\n"
        guard let data = line.data(using: .utf8) else { return }
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            try? data.write(to: url)
        }
    }

    /// How macOS sees this process's code signature, which is what the Accessibility grant is tied to.
    static func signingSummary() -> String {
        var code: SecCode?
        guard SecCodeCopySelf([], &code) == errSecSuccess, let code else { return "no code object" }
        let validity = SecCodeCheckValidity(code, [], nil)
        var staticCode: SecStaticCode?
        SecCodeCopyStaticCode(code, [], &staticCode)
        var requirement: SecRequirement?
        var requirementText: CFString?
        if let staticCode, SecCodeCopyDesignatedRequirement(staticCode, [], &requirement) == errSecSuccess,
           let requirement {
            SecRequirementCopyString(requirement, [], &requirementText)
        }
        return "validity=\(validity) designated=\(requirementText.map { $0 as String } ?? "?")"
    }
}
