package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class GenericNackTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        "Creating a GenericNack" {
            "from values" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val genericNack = GenericNack(missingSeqNums)
                should("set the missing seq nums correctly") {
                    genericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
                "and then serializing it" {
                    val buf = genericNack.getBuffer()
                    should("write the data correctly") {
                        //TODO: we should test these GenericNack static helpers too
                        GenericNack.getPacketId(buf) shouldBe 10
                        GenericNack.getBlp(buf).lostPacketOffsets shouldContainExactly listOf(1, 3, 5, 7, 9, 11, 13, 16)
                    }
                }
            }
            "from a buffer" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val genericNack = GenericNack(missingSeqNums)
                val buf = genericNack.getBuffer()
                val parsedGenericNack = GenericNack(buf)
                should("parse the values correctly") {
                    parsedGenericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
            }
        }
    }
}
