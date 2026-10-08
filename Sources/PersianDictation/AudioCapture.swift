import AVFoundation
import CoreAudio

/// Captures a microphone and delivers 16 kHz / 16-bit / mono PCM in 100 ms chunks.
///
/// Uses AVCaptureSession, not AVAudioEngine: AVAudioEngine always follows the system input and went
/// silent with a Bluetooth headset (soundcore Liberty 5): it switches to its call profile when its mic
/// opens, and even after restarting the engine on the configuration change no audio arrived.
/// AVCaptureSession records from a specific device, converts to 16 kHz itself, and lets us switch:
///  - to the user's choice (menu Microphone), or follow the macOS default input when "System Default";
///  - when the default input changes or the device in use disconnects, also mid-dictation;
///  - to the built-in mic if the device in use sends no audio (or only digital silence) for 3 s.
final class AudioCapture: NSObject, AVCaptureAudioDataOutputSampleBufferDelegate {
    /// Called on the capture queue with PCM data and an RMS level in 0...1.
    var onAudio: ((Data, Float) -> Void)?
    /// Called on the main thread when capture moves to another microphone: (device name, reason).
    var onDeviceChange: ((String, String) -> Void)?

    private let session = AVCaptureSession()
    private let output = AVCaptureAudioDataOutput()
    private let queue = DispatchQueue(label: "AudioCapture")
    // Below: only touched on `queue`.
    private var input: AVCaptureDeviceInput?
    private var pending = Data()
    private var heardSound = false
    private var silenceCheck: DispatchWorkItem?
    private var running = false
    private var loggedFormat = false
    private var observers: [NSObjectProtocol] = []
    private var defaultInputListener: AudioObjectPropertyListenerBlock?

    private static let chunkBytes = SpeechStream.sampleRate / 10 * 2 // 100 ms
    private static let silenceTimeout: TimeInterval = 3

    /// The device in use (or nil before `start`).
    var deviceName: String { queue.sync { input?.device.localizedName ?? "none" } }

    // MARK: Devices

    static func microphones() -> [AVCaptureDevice] {
        let types: [AVCaptureDevice.DeviceType]
        if #available(macOS 14.0, *) {
            types = [.microphone]
        } else {
            types = [.builtInMicrophone, .externalUnknown]
        }
        return AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .audio, position: .unspecified).devices
    }

    /// The macOS default input (System Settings → Sound → Input).
    static func systemDefault() -> AVCaptureDevice? { AVCaptureDevice.default(for: .audio) }

    static func builtIn() -> AVCaptureDevice? {
        microphones().first { $0.transportType == Int32(bitPattern: kAudioDeviceTransportTypeBuiltIn) }
    }

    /// The user's choice if it is connected, else the system default.
    static func preferred() -> AVCaptureDevice? {
        if let id = Settings.microphoneID, let device = microphones().first(where: { $0.uniqueID == id }) {
            return device
        }
        return systemDefault()
    }

    static func requestPermission(_ completion: @escaping (Bool) -> Void) {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            completion(true)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .audio) { granted in
                DispatchQueue.main.async { completion(granted) }
            }
        default:
            completion(false)
        }
    }

    // MARK: Start / stop

    func start() throws {
        guard let device = Self.preferred() ?? Self.builtIn() else {
            throw NSError(domain: "AudioCapture", code: 1, userInfo: [NSLocalizedDescriptionKey: "No microphone found."])
        }
        output.audioSettings = [
            AVFormatIDKey: kAudioFormatLinearPCM,
            AVSampleRateKey: SpeechStream.sampleRate,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false,
            AVLinearPCMIsNonInterleaved: false,
        ]
        output.setSampleBufferDelegate(self, queue: queue)
        try queue.sync {
            guard session.canAddOutput(output) else {
                throw NSError(domain: "AudioCapture", code: 2, userInfo: [NSLocalizedDescriptionKey: "Cannot capture audio."])
            }
            session.addOutput(output)
            try switchTo(device, reason: "start")
            running = true
        }
        observeDevices()
        // startRunning blocks until the device is open (slow for Bluetooth): keep it off the main thread.
        queue.async { self.session.startRunning() }
    }

    func stop() {
        removeObservers()
        queue.sync {
            running = false
            silenceCheck?.cancel()
            silenceCheck = nil
        }
        queue.async { self.session.stopRunning() }
    }

    /// Re-read the user's choice (menu) and switch if it differs from the device in use.
    func preferenceChanged() {
        queue.async { self.follow(Self.preferred(), reason: "microphone menu") }
    }

    // MARK: Switching (on `queue`)

    private func switchTo(_ device: AVCaptureDevice, reason: String) throws {
        let newInput = try AVCaptureDeviceInput(device: device)
        session.beginConfiguration()
        if let input { session.removeInput(input) }
        guard session.canAddInput(newInput) else {
            if let input { session.addInput(input) }
            session.commitConfiguration()
            throw NSError(domain: "AudioCapture", code: 3,
                          userInfo: [NSLocalizedDescriptionKey: "Cannot use the microphone \(device.localizedName)."])
        }
        session.addInput(newInput)
        session.commitConfiguration()
        input = newInput
        pending.removeAll()
        armSilenceCheck()
        let name = device.localizedName
        DiagnosticsLog.write("microphone: \(name) (\(reason))")
        if reason != "start" {
            DispatchQueue.main.async { self.onDeviceChange?(name, reason) }
        }
    }

    /// Switch to `device` unless it is already in use. Falls back to the built-in mic on failure.
    private func follow(_ device: AVCaptureDevice?, reason: String) {
        guard running, let device, device.uniqueID != input?.device.uniqueID else { return }
        do {
            try switchTo(device, reason: reason)
        } catch {
            DiagnosticsLog.write("microphone switch to \(device.localizedName) failed: \(error.localizedDescription)")
            if let builtIn = Self.builtIn(), builtIn.uniqueID != device.uniqueID {
                try? switchTo(builtIn, reason: "\(device.localizedName) failed")
            }
        }
    }

    /// A device that sends nothing (or only zeros) for a few seconds is useless: use the built-in mic.
    private func armSilenceCheck() {
        silenceCheck?.cancel()
        heardSound = false
        let check = DispatchWorkItem { [weak self] in
            guard let self, self.running, !self.heardSound, let current = self.input?.device,
                  let builtIn = Self.builtIn(), builtIn.uniqueID != current.uniqueID else { return }
            DiagnosticsLog.write("microphone \(current.localizedName): no audio for \(Int(Self.silenceTimeout)) s")
            try? self.switchTo(builtIn, reason: "no audio from \(current.localizedName)")
        }
        silenceCheck = check
        queue.asyncAfter(deadline: .now() + Self.silenceTimeout, execute: check)
    }

    // MARK: Device notifications

    private func observeDevices() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: AVCaptureDevice.wasDisconnectedNotification, object: nil,
                                            queue: nil) { [weak self] note in
            guard let self, let gone = note.object as? AVCaptureDevice else { return }
            self.queue.async {
                guard gone.uniqueID == self.input?.device.uniqueID else { return }
                self.follow(Self.preferred() ?? Self.builtIn(), reason: "\(gone.localizedName) disconnected")
            }
        })
        observers.append(center.addObserver(forName: AVCaptureDevice.wasConnectedNotification, object: nil,
                                            queue: nil) { [weak self] note in
            // The user's chosen mic came back.
            guard let self, let device = note.object as? AVCaptureDevice,
                  device.hasMediaType(.audio), device.uniqueID == Settings.microphoneID else { return }
            self.queue.async { self.follow(device, reason: "\(device.localizedName) connected") }
        })
        observers.append(center.addObserver(forName: AVCaptureSession.runtimeErrorNotification, object: session,
                                            queue: nil) { [weak self] note in
            let error = note.userInfo?[AVCaptureSessionErrorKey] as? Error
            DiagnosticsLog.write("capture error: \(error?.localizedDescription ?? "unknown")")
            guard let self else { return }
            self.queue.async {
                if let builtIn = Self.builtIn() { self.follow(builtIn, reason: "capture error") }
                if !self.session.isRunning { self.session.startRunning() }
            }
        })

        // Follow the macOS default input while "System Default" is selected.
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            guard let self, Settings.microphoneID == nil else { return }
            self.follow(Self.systemDefault(), reason: "system default changed")
        }
        defaultInputListener = listener
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, queue, listener)
    }

    private func removeObservers() {
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        if let listener = defaultInputListener {
            var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyDefaultInputDevice,
                                                     mScope: kAudioObjectPropertyScopeGlobal,
                                                     mElement: kAudioObjectPropertyElementMain)
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, queue, listener)
            defaultInputListener = nil
        }
    }

    // MARK: Samples (on `queue`)

    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard running, let block = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        if !loggedFormat, let format = CMSampleBufferGetFormatDescription(sampleBuffer),
           let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee {
            loggedFormat = true
            DiagnosticsLog.write("capture format: \(Int(asbd.mSampleRate)) Hz, \(asbd.mChannelsPerFrame) ch, \(asbd.mBitsPerChannel) bit")
        }
        let length = CMBlockBufferGetDataLength(block)
        var data = Data(count: length)
        let status = data.withUnsafeMutableBytes {
            CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: length, destination: $0.baseAddress!)
        }
        guard status == kCMBlockBufferNoErr else { return }
        if !heardSound, data.contains(where: { $0 != 0 }) {
            heardSound = true
            silenceCheck?.cancel()
        }
        pending.append(data)
        while pending.count >= Self.chunkBytes {
            let chunk = pending.prefix(Self.chunkBytes)
            pending.removeFirst(Self.chunkBytes)
            emit(Data(chunk))
        }
    }

    private func emit(_ chunk: Data) {
        let rms: Float = chunk.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            var sum: Float = 0
            for v in samples { let s = Float(Int16(littleEndian: v)) / 32768; sum += s * s }
            return (sum / Float(max(1, samples.count))).squareRoot()
        }
        onAudio?(chunk, rms)
    }
}
