package com.openwhen.app.media

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SparkPhotoPolicyTest {
    @Test fun `real JPEG survives the in-memory draft round trip unchanged`() {
        val jpeg = jpeg()
        val value = SparkPhotoPolicy.encode(jpeg)
        assertTrue(value.startsWith(SparkPhotoPolicy.DATA_PREFIX))
        assertArrayEquals(jpeg, SparkPhotoPolicy.decode(value))
    }

    @Test fun `frame policy accepts its maximum permitted dimensions`() {
        SparkPhotoPolicy.validate(withFrameDimensions(SparkPhotoPolicy.MAX_EDGE, SparkPhotoPolicy.MAX_EDGE))
    }

    @Test fun `only the exact private JPEG marker is accepted`() {
        val encoded = Base64.getEncoder().encodeToString(jpeg())
        listOf("data:image/png;base64,", "https://example.test/", "content://photos/", "file:///private/", "DATA:image/jpeg;base64,")
            .forEach { prefix -> rejects { SparkPhotoPolicy.decode(prefix + encoded) } }
    }

    @Test fun `oversized encoded input is rejected before it can become image bytes`() {
        val encoded = "A".repeat(((SparkPhotoPolicy.MAX_BYTES + 2) / 3) * 4 + 4)
        rejects { SparkPhotoPolicy.decode(SparkPhotoPolicy.DATA_PREFIX + encoded) }
        rejects { SparkPhotoPolicy.encode(ByteArray(SparkPhotoPolicy.MAX_BYTES + 1)) }
    }

    @Test fun `malformed unpadded and whitespace base64 do not enter the decoder`() {
        val value = SparkPhotoPolicy.encode(jpeg())
        rejects { SparkPhotoPolicy.decode(SparkPhotoPolicy.DATA_PREFIX + "!!!=") }
        rejects { SparkPhotoPolicy.decode(value + "\n") }
        rejects { SparkPhotoPolicy.decode(value.dropLast(1)) }
        rejects { SparkPhotoPolicy.decode(SparkPhotoPolicy.DATA_PREFIX) }
    }

    @Test fun `invalid signatures and incomplete JPEG data are rejected`() {
        val original = jpeg()
        val wrongStart = original.copyOf().apply { this[0] = 0 }
        rejects { SparkPhotoPolicy.validate(wrongStart) }
        rejects { SparkPhotoPolicy.validate(original.copyOf(original.size - 1)) }
        rejects { SparkPhotoPolicy.validate(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())) }
    }

    @Test fun `small payloads cannot claim huge decoded dimensions`() {
        rejects { SparkPhotoPolicy.validate(withFrameDimensions(SparkPhotoPolicy.MAX_EDGE + 1, 1)) }
        rejects { SparkPhotoPolicy.validate(withFrameDimensions(1, SparkPhotoPolicy.MAX_EDGE + 1)) }
        val invalid = jpeg().copyOf()
        val frame = frameMarker(invalid)
        invalid[frame + 5] = 0
        invalid[frame + 6] = 0
        rejects { SparkPhotoPolicy.validate(invalid) }
    }

    @Test fun `invalid segment lengths and unsupported frame precision are rejected`() {
        val invalidLength = jpeg().copyOf().apply { this[4] = 0; this[5] = 1 }
        rejects { SparkPhotoPolicy.validate(invalidLength) }
        val invalidPrecision = jpeg().copyOf()
        invalidPrecision[frameMarker(invalidPrecision) + 4] = 16
        rejects { SparkPhotoPolicy.validate(invalidPrecision) }
    }

    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java) { block() } }

    private fun jpeg(): ByteArray = Base64.getDecoder().decode(TEST_JPEG)

    // These mutations exercise the header policy only, not image-decoder validity.
    private fun withFrameDimensions(width: Int, height: Int): ByteArray = jpeg().apply {
        val frame = frameMarker(this)
        this[frame + 5] = (height ushr 8).toByte()
        this[frame + 6] = height.toByte()
        this[frame + 7] = (width ushr 8).toByte()
        this[frame + 8] = width.toByte()
    }

    private fun frameMarker(bytes: ByteArray): Int = (0 until bytes.size - 1).first {
        bytes[it].toInt() and 0xff == 0xff && bytes[it + 1].toInt() and 0xff == 0xc0
    }

    private companion object {
        // Generated 2 x 2 JPEG shared with the disposable cloud smoke test; no personal photo.
        const val TEST_JPEG = "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAMCAgMCAgMDAwMEAwMEBQgFBQQEBQoHBwYIDAoMDAsKCwsNDhIQDQ4RDgsLEBYQERMUFRUVDA8XGBYUGBIUFRT/2wBDAQMEBAUEBQkFBQkUDQsNFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBT/wAARCAACAAIDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD751bVr611W9ggvbiGGOZ0SOOVlVVDEAAA8ADtRRRWB9Sf/9k="
    }
}
