package dev.gaurav.dailyrhythm

import java.io.*
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Each record is independently authenticated before it leaves the reader. The empty final record
 * authenticates end of stream. Sequence numbers prevent truncation, duplication and reordering. */
internal object BackupEncryption {
    private val magic = "DRBACK01".toByteArray(Charsets.US_ASCII)
    private const val chunkSize = 65_536
    private const val tagSize = 16
    private const val maxRecords = 1_536

    private fun key(password: CharArray, header: ByteArray): SecretKeySpec {
        require(password.size in 8..128) { "Use a password of 8 to 128 characters." }
        val spec = PBEKeySpec(password, header.copyOfRange(magic.size, magic.size + 16), 600_000, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, index: Int, size: Int): Cipher {
        require(index in 0 until maxRecords)
        val nonce = ByteBuffer.allocate(12).put(header, header.size - 8, 8).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(tagSize * 8, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(8).putInt(index).putInt(size).array())
        }
    }

    fun writer(output: OutputStream, password: CharArray): OutputStream {
        val out = DataOutputStream(output)
        val header = magic + ByteArray(24).also(SecureRandom()::nextBytes)
        val key = key(password, header)
        out.write(header)
        return object : OutputStream() {
            private val bytes = ByteArray(chunkSize)
            private var size = 0
            private var index = 0
            private var closed = false

            private fun record() {
                val encrypted = cipher(Cipher.ENCRYPT_MODE, key, header, index++, size).doFinal(bytes, 0, size)
                out.writeInt(size)
                out.write(encrypted)
                size = 0
            }

            override fun write(value: Int) {
                check(!closed)
                bytes[size++] = value.toByte()
                if (size == chunkSize) record()
            }

            override fun write(source: ByteArray, offset: Int, length: Int) {
                check(!closed)
                var copied = 0
                while (copied < length) {
                    val count = minOf(length - copied, chunkSize - size)
                    source.copyInto(bytes, size, offset + copied, offset + copied + count)
                    size += count
                    copied += count
                    if (size == chunkSize) record()
                }
            }

            override fun close() {
                if (closed) return
                closed = true
                try {
                    if (size > 0) record()
                    record()
                } finally {
                    bytes.fill(0)
                    out.close()
                }
            }
        }
    }

    class Reader(source: InputStream, password: CharArray) : InputStream() {
        private val input = DataInputStream(source)
        private val header = ByteArray(magic.size + 24).also(input::readFully)
        private val key = key(password, header.also { require(it.copyOf(magic.size).contentEquals(magic)) })
        private var index = 0
        private var offset = 0
        private var bytes = ByteArray(0)
        private var finished = false

        private fun refill(): Boolean {
            if (offset < bytes.size) return true
            if (finished) return false
            val size = input.readInt()
            require(size in 0..chunkSize)
            val encrypted = ByteArray(size + tagSize).also(input::readFully)
            bytes = cipher(Cipher.DECRYPT_MODE, key, header, index++, size).doFinal(encrypted)
            offset = 0
            if (size == 0) {
                require(input.read() == -1)
                finished = true
            }
            return !finished
        }

        override fun read(): Int = if (refill()) bytes[offset++].toInt() and 255 else -1

        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (length == 0) return 0
            if (!refill()) return -1
            val copied = minOf(length, bytes.size - offset)
            bytes.copyInto(target, start, offset, offset + copied)
            offset += copied
            return copied
        }

        override fun available(): Int = bytes.size - offset
        override fun close() = input.close()
    }
}
