package local.voice2text.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Development mode: saves the exact audio sent to one Google session as a 16 kHz mono WAV file,
 * so a recognition problem can be reproduced by replaying it.
 */
class WavRecorder(val file: File) {
    private val out = RandomAccessFile(file, "rw").apply { setLength(0); write(header(0)) }
    private var dataBytes = 0
    private var closed = false

    @Synchronized
    fun append(pcm: ByteArray) {
        if (closed) return
        out.write(pcm)
        dataBytes += pcm.size
    }

    /** Fix up the WAV header sizes and close the file. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        out.seek(0)
        out.write(header(dataBytes))
        out.close()
    }

    companion object {
        /** Delete recordings older than [days], so development mode doesn't fill the disk. */
        fun pruneOld(dir: File, days: Int = 7) {
            val cutoff = System.currentTimeMillis() - days * 86_400_000L
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }

        private fun header(dataBytes: Int): ByteArray {
            val rate = SpeechStream.SAMPLE_RATE
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16)
            b.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16) // PCM, mono, 16-bit
            b.put("data".toByteArray()).putInt(dataBytes)
            return b.array()
        }
    }
}
