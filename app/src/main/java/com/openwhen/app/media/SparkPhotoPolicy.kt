package com.openwhen.app.media

import java.util.Base64

/** The inline marker is an in-memory draft value, never a public URL or dashboard field. */
object SparkPhotoPolicy {
    const val MAX_BYTES = 245_760
    const val MAX_EDGE = 1024
    const val MIME_TYPE = "image/jpeg"
    const val DATA_PREFIX = "data:image/jpeg;base64,"
    private const val MAX_BASE64_CHARS = ((MAX_BYTES + 2) / 3) * 4

    fun encode(bytes: ByteArray): String {
        validate(bytes)
        return DATA_PREFIX + Base64.getEncoder().encodeToString(bytes)
    }

    fun decode(value: String): ByteArray {
        require(value.startsWith(DATA_PREFIX) && value.length <= DATA_PREFIX.length + MAX_BASE64_CHARS) {
            "Choose the photo again before sealing this letter."
        }
        val encoded = value.substring(DATA_PREFIX.length)
        require(encoded.length % 4 == 0) { "This photo could not be read. Choose it again." }
        val decoded = Base64.getDecoder().decode(encoded)
        require(Base64.getEncoder().encodeToString(decoded) == encoded) { "This photo could not be read. Choose it again." }
        validate(decoded)
        return decoded
    }

    /** Bounds dimensions before an image decoder sees data supplied by another client. */
    fun validate(bytes: ByteArray) {
        require(bytes.size in 20..MAX_BYTES && unsigned(bytes, 0) == 0xff && unsigned(bytes, 1) == 0xd8 &&
            unsigned(bytes, bytes.lastIndex - 1) == 0xff && unsigned(bytes, bytes.lastIndex) == 0xd9) {
            "This photo is not a supported JPEG memory."
        }
        var offset = 2
        var foundDimensions = false
        while (offset + 3 < bytes.size) {
            require(unsigned(bytes, offset) == 0xff) { "This photo has an invalid image header." }
            while (offset < bytes.size && unsigned(bytes, offset) == 0xff) offset++
            require(offset + 2 < bytes.size) { "This photo has an incomplete image header." }
            val marker = unsigned(bytes, offset++)
            require(marker !in setOf(0x00, 0x01, 0xd8, 0xd9) && marker !in 0xd0..0xd7) {
                "This photo has an invalid image header."
            }
            val length = word(bytes, offset)
            require(length >= 2 && offset + length <= bytes.size - 2) { "This photo has an incomplete image header." }
            if (marker == 0xc0 || marker == 0xc2) {
                require(!foundDimensions && length >= 8 && unsigned(bytes, offset + 2) == 8) {
                    "This photo uses an unsupported image format."
                }
                val height = word(bytes, offset + 3)
                val width = word(bytes, offset + 5)
                val components = unsigned(bytes, offset + 7)
                require(width in 1..MAX_EDGE && height in 1..MAX_EDGE && components in setOf(1, 3) &&
                    length == 8 + 3 * components) { "This photo needs to be resized before sending." }
                foundDimensions = true
            } else if (marker in 0xc0..0xcf && marker !in setOf(0xc4, 0xc8, 0xcc)) {
                throw IllegalArgumentException("This photo uses an unsupported image format.")
            }
            if (marker == 0xda) {
                require(foundDimensions && length >= 6) { "This photo has no valid image dimensions." }
                return
            }
            offset += length
        }
        throw IllegalArgumentException("This photo has an incomplete image header.")
    }

    private fun unsigned(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xff
    private fun word(bytes: ByteArray, index: Int): Int = (unsigned(bytes, index) shl 8) or unsigned(bytes, index + 1)
}
