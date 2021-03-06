package org.jitsi.rtp.util

import com.sun.javaws.exceptions.InvalidArgumentException
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class BitBufferTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private fun createBitBufferWithData(vararg bytes: Byte): BitBuffer {
        return BitBuffer(ByteBuffer.wrap(bytes))
    }

    init {
        "getBits" {
            val buffer = ByteBuffer.wrap(byteArrayOf(
                0xFF.toByte(),  0x01,           0x02,           0x03,
                0x04,           0x05,           0x06,           0x06
            ))
            val bitBuffer = BitBuffer(buffer)
            should("not advance to the next byte until its time") {
                repeat(8) {
                    bitBuffer.getBits(1).toInt() shouldBe 1
                }
                bitBuffer.getBits(1).toInt() shouldBe 0
            }
            should("throw when trying to cross a byte boundary") {
                shouldThrow<IllegalArgumentException> {
                    bitBuffer.getBits(10)
                }
            }
        }
        "getBitAsBoolean" {
            should("correctly interpret a bit as boolean") {
                createBitBufferWithData(0b10000000.toByte()).getBitAsBoolean() shouldBe true
                createBitBufferWithData(0b00000000.toByte()).getBitAsBoolean() shouldBe false
            }
            "boundary cases" {
                val buf = createBitBufferWithData(0x01, 0x00)
                should("work fine on the last bit of a byte") {
                    buf.getBits(7)
                    buf.getBitAsBoolean() shouldBe true
                }
                should("work fine on the first bit of a new byte") {
                    buf.getBits(7)
                    buf.getBitAsBoolean()
                    buf.getBitAsBoolean() shouldBe false
                }
            }
        }
        "putBits" {
            val buffer = ByteBuffer.wrap(byteArrayOf(
                0x00,   0x00,   0x00, 0x00
            ))
            val bitBuffer = BitBuffer(buffer)
            "in a single byte" {
                should("set them correctly") {
                    bitBuffer.putBits(0b1, 1)
                    buffer.get(0) shouldBe 0b10000000.toByte()

                    bitBuffer.putBits(0b11.toByte(), 2)
                    buffer.get(0) shouldBe 0b11100000.toByte()
                }
            }
            "to fill one byte and then the next" {
                should("set them correctly") {
                    bitBuffer.putBits(0b11111111.toByte(), 8)
                    bitBuffer.putBits(0b1.toByte(), 1)
                    buffer.get(1) shouldBe 0b10000000.toByte()
                }
            }
            "across byte boundaries" {
                should("throw an exception") {
                    bitBuffer.putBits(0b1111, 4)
                    shouldThrow<IllegalArgumentException> {
                        bitBuffer.putBits(0b11111, 5)
                    }
                }
            }
        }
    }
}
