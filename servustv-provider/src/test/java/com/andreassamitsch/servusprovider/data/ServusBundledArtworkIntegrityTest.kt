package com.andreassamitsch.servusprovider.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Inflater

class ServusBundledArtworkIntegrityTest {
    @Test
    fun ninetySecondLogoIsCompleteDecodablePng() {
        val file = sequenceOf(
            File("servustv-provider/src/main/res/drawable-nodpi/servus_news_90_logo.png"),
            File("src/main/res/drawable-nodpi/servus_news_90_logo.png"),
        ).firstOrNull(File::isFile)
            ?: error("servus_news_90_logo.png not found")

        val bytes = file.readBytes()
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue("PNG is too small", bytes.size > signature.size)
        assertArrayEquals(signature, bytes.copyOfRange(0, signature.size))

        var offset = signature.size
        var sawIend = false
        val idat = ByteArrayOutputStream()
        while (offset < bytes.size) {
            assertTrue("Truncated PNG chunk header at $offset", offset + 12 <= bytes.size)
            val length = readInt(bytes, offset)
            assertTrue("Invalid PNG chunk length $length", length >= 0)
            val chunkEnd = offset + 12 + length
            assertTrue("Truncated PNG chunk at $offset", chunkEnd <= bytes.size)

            val type = bytes.copyOfRange(offset + 4, offset + 8)
            val dataStart = offset + 8
            val dataEnd = dataStart + length
            val data = bytes.copyOfRange(dataStart, dataEnd)
            val expectedCrc = readInt(bytes, dataEnd).toLong() and 0xffffffffL
            val crc = CRC32().apply {
                update(type)
                update(data)
            }.value
            assertEquals("Bad PNG CRC for ${String(type, Charsets.US_ASCII)}", expectedCrc, crc)

            when (String(type, Charsets.US_ASCII)) {
                "IDAT" -> idat.write(data)
                "IEND" -> sawIend = true
            }
            offset = chunkEnd
        }

        assertTrue("PNG is missing IEND", sawIend)
        assertEquals("Unexpected bytes after final PNG chunk", bytes.size, offset)
        assertFalse("PNG has no IDAT payload", idat.size() == 0)

        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            val buffer = ByteArray(8192)
            while (!inflater.finished() && !inflater.needsInput()) {
                inflater.inflate(buffer)
            }
            assertTrue("PNG IDAT stream is truncated", inflater.finished())
        } finally {
            inflater.end()
        }
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
