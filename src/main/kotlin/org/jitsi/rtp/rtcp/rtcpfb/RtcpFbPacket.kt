/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.ByteBufferUtils
import toUInt
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer

abstract class FeedbackControlInformation {
    abstract val size: Int
    //TODO: get rid of this here.  it will be in the packet instead
    abstract val fmt: Int
    protected abstract var buf: ByteBuffer?
    abstract fun getBuffer(): ByteBuffer
}


/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 */
//class PayloadSpecificFeedbackInformation : FeedbackControlInformation() {
//
//}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *     0                   1                   2                   3
 *     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |V=2|P|   FMT   |       PT      |          length               |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of packet sender                        |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of media source                         |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    :            Feedback Control Information (FCI)                 :
 *    :                                                               :
 */
//TODO: this changes the common RTCP header (the FMT field in place of
// the RC field).  Should the header parse that field, but hold it
// generically?  Should it make it abstract?  Should it ignore it
// altogether?
abstract class RtcpFbPacket : RtcpPacket {
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    var mediaSourceSsrc: Long
    /**
     * The size of this packet, in bytes (including any padding)
     */
    override val size: Int
        get() = dataSizeBytes + paddingSizeBytes

    /**
     * The size of the data in this packet in bytes (not including padding)
     */
    private val dataSizeBytes: Int
        get() = RtcpHeader.SIZE_BYTES + 4 /* mediaSourceSsrc */ + getFci().size

    /**
     * The amount of padding bytes needed, based on word-alignment and [dataSizeBytes]
     */
    private val paddingSizeBytes: Int
        get() {
            var paddingSize = 0
            while ((dataSizeBytes + paddingSize) % 4 != 0) {
                paddingSize++
            }
            return paddingSize
        }

    abstract fun getFci(): FeedbackControlInformation

    companion object {
        const val FCI_OFFSET = RtcpHeader.SIZE_BYTES + 4
        val PACKET_TYPES = listOf(205, 206)
        /**
         * Although this should only be called if the given buffer was determined to
         * contain an RTCPFB packet already, the given buf should be at the start of
         * the RTCP packet and we'll parse it here.
         */
        fun fromBuffer(buf: ByteBuffer): RtcpFbPacket {
            val packetType = RtcpHeader.getPacketType(buf)
            val fmt = RtcpHeader.getReportCount(buf)
            return when (packetType) {
                TransportLayerFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbNackPacket.FMT -> RtcpFbNackPacket(buf)
                        RtcpFbTccPacket.FMT -> RtcpFbTccPacket(buf)
                        else -> throw Exception("Unrecognized RTCPFB format: pt $packetType, fmt $fmt")
                    }
                }
                PayloadSpecificFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbPliPacket.FMT -> RtcpFbPliPacket(buf)
                        RtcpFbFirPacket.FMT -> RtcpFbFirPacket(buf)
                        2 -> TODO("sli")
                        3 -> TODO("rpsi")
                        15 -> TODO("afb")
                        else -> throw Exception("Unrecognized RTCPFB format: pt $packetType, fmt $fmt")
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB payload type: $packetType")
            }
        }
        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) { buf.putInt(8, mediaSourceSsrc.toUInt()) }

        fun setFeedbackControlInformation(buf: ByteBuffer, fci: FeedbackControlInformation) {
            val fciBuf = buf.subBuffer(12)
            fciBuf.put(fci.getBuffer())
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.header = RtcpHeader(buf)
        this.mediaSourceSsrc = getMediaSourceSsrc(buf)
    }

    @JvmOverloads
    constructor(
        header: RtcpHeader = RtcpHeader(),
        mediaSourceSsrc: Long = 0
    ) : super() {
        this.header = header
        this.mediaSourceSsrc = mediaSourceSsrc
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        header.hasPadding = paddingSizeBytes > 0
        // We need to update the length in the header to match the current content
        // of the packet (which may have changed)
        header.length = lengthValue
        header.reportCount = getFci().fmt
        RtcpPacket.setHeader(b, header)
        RtcpFbPacket.setMediaSourceSsrc(b, mediaSourceSsrc)
        RtcpFbPacket.setFeedbackControlInformation(b, getFci())
        // Add any padding
        b.position(dataSizeBytes)
        //TODO: write the padding length in the last byte
        repeat(paddingSizeBytes) {
            b.put(0x00)
        }

        b.rewind()
        buf = b

        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCPFB packet")
            // TODO: the header may not have been "sync'd" at this point (e.g. length, fmt not set)
            append(header.toString())
            appendln("media source ssrc: $mediaSourceSsrc")
            appendln(getFci().toString())
            toString()
        }
    }
}
