package org.jitsi.rtp

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class RtpOneByteHeaderExtensionTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true
    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }

    init {
        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |       0xBE    |    0xDE       |           length=3            |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |  ID   | L=0   |     data      |  ID   |  L=1  |   data...
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //       ...data   |    0 (pad)    |    0 (pad)    |  ID   | L=3   |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                          data                                 |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-
        "parsing" {
            // Read past the cookie and length
            "an extension with length 0" {
                val length0Extension = ByteBuffer.wrap(byteArrayOf(
                    idLengthByte(1, 0), 0x42
                ))
                val ext = RtpOneByteHeaderExtension(length0Extension)
                should("have the correct id, size and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 1
                    ext.data.get() shouldBe 0x42.toByte()
                }
                should("put the buffer's position in the correct place") {
                    length0Extension.position() shouldBe 2
                }
                should("have the correct size") {
                    ext.size shouldBe 2
                }
                "and then serializing it" {
                    val buf = ext.getBuffer()
                    should("start at the beginning of the buffer") {
                        buf.position() shouldBe 0
                    }
                    should("have written the correct amount of data") {
                        buf.limit() shouldBe 2
                    }
                    should("have written the right id, size, and data") {
                        with(BitBuffer(buf)) {
                            // Id
                            getBits(4).toInt() shouldBe 1
                            // Length
                            getBits(4).toInt() shouldBe 0
                        }
                        // Data
                        buf.get() shouldBe 0x42.toByte()
                    }
                }
            }
            "an extension with padding" {
                val extensionWithPadding = ByteBuffer.wrap(byteArrayOf(
                    idLengthByte(1, 3), 0x42, 0x42, 0x42,
                    0x42, 0x00, 0x00
                ))
                val ext = RtpOneByteHeaderExtension(extensionWithPadding)
                should("parse to the end of the data") {
                    extensionWithPadding.position() shouldBe 5
                }
                should("have the right id, size, and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 4
                    repeat(ext.data.limit()) {
                        ext.data.get() shouldBe 0x42.toByte()
                    }
                    ext.size shouldBe 5
                }
                "and then serializing it" {
                    val buf = ext.getBuffer()
                    should("have written the correct amount of data") {
                        buf.limit() shouldBe 5
                    }
                    should("have written the right id, size, and data") {
                        with(BitBuffer(buf)) {
                            // Id
                            getBits(4).toInt() shouldBe 1
                            // Length
                            getBits(4).toInt() shouldBe 3
                        }
                        // Data
                        repeat(4) {
                            buf.get() shouldBe 0x42.toByte()
                        }
                    }
                }
            }
            "an extension with id 15" {
                //TODO: should throw so we know to stop parsing
            }
        }
    }
}
