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
package org.jitsi.rtp.rtcp

import org.jitsi.rtp.extensions.get3Bytes
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.ByteBufferUtils
import toUInt
import unsigned.toUBigInt
import unsigned.toUByte
import unsigned.toUInt
import unsigned.toULong
import unsigned.toUShort
import java.nio.ByteBuffer
import kotlin.properties.Delegates

/**
 * [buf] is a buffer which points to the start of a report
 * block
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * |                 SSRC_1 (SSRC of first source)                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | fraction lost |       cumulative number of packets lost       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           extended highest sequence number received           |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      interarrival jitter                      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         last SR (LSR)                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                   delay since last SR (DLSR)                  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtcpReportBlock {
    private var buf: ByteBuffer? = null
    /**
     * SSRC_n (source identifier): 32 bits
     *     The SSRC identifier of the source to which the information in this
     *     reception report block pertains.
     */
    var ssrc: Long
    /**
     * fraction lost: 8 bits
     *     The fraction of RTP data packets from source SSRC_n lost since the
     *     previous SR or RR packet was sent, expressed as a fixed point
     *     number with the binary point at the left edge of the field.  (That
     *     is equivalent to taking the integer part after multiplying the
     *     loss fraction by 256.)  This fraction is defined to be the number
     *     of packets lost divided by the number of packets expected, as
     *     defined in the next paragraph.  An implementation is shown in
     *     Appendix A.3.  If the loss is negative due to duplicates, the
     *     fraction lost is set to zero.  Note that a receiver cannot tell
     *     whether any packets were lost after the last one received, and
     *     that there will be no reception report block issued for a source
     *     if all packets from that source sent during the last reporting
     *     interval have been lost.
     */
    var fractionLost: Int
    /**
     * cumulative number of packets lost: 24 bits
     *     The total number of RTP data packets from source SSRC_n that have
     *     been lost since the beginning of reception.  This number is
     *     defined to be the number of packets expected less the number of
     *     packets actually received, where the number of packets received
     *     includes any which are late or duplicates.  Thus, packets that
     *     arrive late are not counted as lost, and the loss may be negative
     *     if there are duplicates.  The number of packets expected is
     *     defined to be the extended last sequence number received, as
     *     defined next, less the initial sequence number received.  This may
     *     be calculated as shown in Appendix A.3.
     */
    var cumulativePacketsLost: Int
    /**
     * extended highest sequence number received: 32 bits
     *     The low 16 bits contain the highest sequence number received in an
     *     RTP data packet from source SSRC_n, and the most significant 16
     *     bits extend that sequence number with the corresponding count of
     *     sequence number cycles, which may be maintained according to the
     *     algorithm in Appendix A.1.  Note that different receivers within
     *     the same session will generate different extensions to the
     *     sequence number if their start times differ significantly.
     */
    var seqNumCycles: Int
    var seqNum: Int
    /**
     * interarrival jitter: 32 bits
     *     An estimate of the statistical variance of the RTP data packet
     *     interarrival time, measured in timestamp units and expressed as an
     *     unsigned integer.  The interarrival jitter J is defined to be the
     *     mean deviation (smoothed absolute value) of the difference D in
     *     packet spacing at the receiver compared to the sender for a pair
     *     of packets.  As shown in the equation below, this is equivalent to
     *     the difference in the "relative transit time" for the two packets;
     *     the relative transit time is the difference between a packet's RTP
     *     timestamp and the receiver's clock at the time of arrival,
     *     measured in the same units.

     *     If Si is the RTP timestamp from packet i, and Ri is the time of
     *     arrival in RTP timestamp units for packet i, then for two packets
     *     i and j, D may be expressed as

     *     D(i,j) = (Rj - Ri) - (Sj - Si) = (Rj - Sj) - (Ri - Si)

     *     The interarrival jitter SHOULD be calculated continuously as each
     *     data packet i is received from source SSRC_n, using this
     *     difference D for that packet and the previous packet i-1 in order
     *     of arrival (not necessarily in sequence), according to the formula

     *     J(i) = J(i-1) + (|D(i-1,i)| - J(i-1))/16

     *     Whenever a reception report is issued, the current value of J is
     *     sampled.

     *     The jitter calculation MUST conform to the formula specified here
     *     in order to allow profile-independent monitors to make valid
     *     interpretations of reports coming from different implementations.
     *     This algorithm is the optimal first-order estimator and the gain
     *     parameter 1/16 gives a good noise reduction ratio while
     *     maintaining a reasonable rate of convergence [22].  A sample
     *     implementation is shown in Appendix A.8.  See Section 6.4.4 for a
     *     discussion of the effects of varying packet duration and delay
     *     before transmission.
     */
    var interarrivalJitter: Long

    /**
     * last SR timestamp (LSR): 32 bits
     *     The middle 32 bits out of 64 in the NTP timestamp (as explained in
     *     Section 4) received as part of the most recent RTCP sender report
     *     (SR) packet from source SSRC_n.  If no SR has been received yet,
     *     the field is set to zero.
     */
    var lastSrTimestamp: Long

    /**
     * delay since last SR (DLSR): 32 bits
     *     The delay, expressed in units of 1/65536 seconds, between
     *     receiving the last SR packet from source SSRC_n and sending this
     *     reception report block.  If no SR packet has been received yet
     *     from SSRC_n, the DLSR field is set to zero.
     *
     *     Let SSRC_r denote the receiver issuing this receiver report.
     *     Source SSRC_n can compute the round-trip propagation delay to
     *     SSRC_r by recording the time A when this reception report block is
     *     received.  It calculates the total round-trip time A-LSR using the
     *     last SR timestamp (LSR) field, and then subtracting this field to
     *     leave the round-trip propagation delay as (A - LSR - DLSR).  This
     *     is illustrated in Fig. 2.  Times are shown in both a hexadecimal
     *     representation of the 32-bit fields and the equivalent floating-
     *     point decimal representation.  Colons indicate a 32-bit field
     *     divided into a 16-bit integer part and 16-bit fraction part.
     *
     *     This may be used as an approximate measure of distance to cluster
     *     receivers, although some links have very asymmetric delays.
     *
     *     n                 SR(n)              A=b710:8000 (46864.500 s)
     *     ---------------------------------------------------------------->
     *                        v                 ^
     *     ntp_sec =0xb44db705 v               ^ dlsr=0x0005:4000 (    5.250s)
     *     ntp_frac=0x20000000  v             ^  lsr =0xb705:2000 (46853.125s)
     *       (3024992005.125 s)  v           ^
     *     r                      v         ^ RR(n)
     *     ---------------------------------------------------------------->
     *                            |<-DLSR->|
     *                            (5.250 s)
     *
     *     A     0xb710:8000 (46864.500 s)
     *     DLSR -0x0005:4000 (    5.250 s)
     *     LSR  -0xb705:2000 (46853.125 s)
     *     -------------------------------
     *     delay 0x0006:2000 (    6.125 s)
     *
     *             Figure 2: Example for round-trip time computation
     */
    var delaySinceLastSr: Long
    companion object {
        const val SIZE_BYTES = 24

        fun getSsrc(buf: ByteBuffer): Long = buf.getInt(0).toULong()
        fun setSsrc(buf: ByteBuffer, ssrc: Long) {
            buf.putInt(0, ssrc.toUInt())
        }

        fun getFractionLost(buf: ByteBuffer): Int = buf.get(4).toUInt()
        fun setFractionLost(buf: ByteBuffer, fractionLost: Int) {
            buf.put(4, fractionLost.toByte())
        }

        fun getCumulativeLost(buf: ByteBuffer): Int = buf.get3Bytes(5)
        fun setCumulativeLost(buf: ByteBuffer, cumulativeLost: Int) {
            buf.put3Bytes(5, cumulativeLost)
        }

        fun getSeqNumCycles(buf: ByteBuffer): Int = buf.getShort(8).toUInt()
        fun setSeqNumCycles(buf: ByteBuffer, seqNumCycles: Int) {
            buf.putShort(8, seqNumCycles.toUShort())
        }

        fun getSeqNum(buf: ByteBuffer): Int = buf.getShort(10).toUInt()
        fun setSeqNum(buf: ByteBuffer, seqNum: Int) {
            buf.putShort(10, seqNum.toUShort())
        }

        fun getInterarrivalJitter(buf: ByteBuffer): Long = buf.getInt(12).toULong()
        fun setInterarrivalJitter(buf: ByteBuffer, interarrivalJitter: Long) = buf.putInt(12, interarrivalJitter.toUInt())

        fun getLastSrTimestamp(buf: ByteBuffer): Long = buf.getInt(16).toULong()
        fun setLastSrTimestamp(buf: ByteBuffer, lastSrTimestamp: Long) = buf.putInt(16, lastSrTimestamp.toUInt())

        fun getDelaySinceLastSr(buf: ByteBuffer): Long = buf.getInt(20).toULong()
        fun setDelaySinceLastSr(buf: ByteBuffer, delaySinceLastSr: Long) = buf.putInt(20, delaySinceLastSr.toUInt())
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.subBuffer(0, RtcpReportBlock.SIZE_BYTES)
        this.ssrc = RtcpReportBlock.getSsrc(buf)
        this.fractionLost = RtcpReportBlock.getFractionLost(buf)
        this.cumulativePacketsLost = RtcpReportBlock.getCumulativeLost(buf)
        this.seqNumCycles = RtcpReportBlock.getSeqNumCycles(buf)
        this.seqNum = RtcpReportBlock.getSeqNum(buf)
        this.interarrivalJitter = RtcpReportBlock.getInterarrivalJitter(buf)
        this.lastSrTimestamp = RtcpReportBlock.getLastSrTimestamp(buf)
        this.delaySinceLastSr = RtcpReportBlock.getDelaySinceLastSr(buf)
    }

    constructor(
        ssrc: Long = 0,
        fractionLost: Int = 0,
        cumulativePacketsLost: Int = 0,
        seqNumCycles: Int = 0,
        seqNum: Int = 0,
        interarrivalJitter: Long = 0,
        lastSrTimestamp: Long = 0,
        delaySinceLastSr: Long = 0
    ) {
        this.ssrc = ssrc
        this.fractionLost = fractionLost
        this.cumulativePacketsLost = cumulativePacketsLost
        this.seqNumCycles = seqNumCycles
        this.seqNum = seqNum
        this.interarrivalJitter = interarrivalJitter
        this.lastSrTimestamp = lastSrTimestamp
        this.delaySinceLastSr = delaySinceLastSr
    }

    fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, RtcpReportBlock.SIZE_BYTES)
        b.rewind()
        b.limit(RtcpReportBlock.SIZE_BYTES)

        RtcpReportBlock.setSsrc(b, this.ssrc)
        RtcpReportBlock.setFractionLost(b, this.fractionLost)
        RtcpReportBlock.setCumulativeLost(b, this.cumulativePacketsLost)
        RtcpReportBlock.setSeqNumCycles(b, this.seqNumCycles)
        RtcpReportBlock.setSeqNum(b, this.seqNum)
        RtcpReportBlock.setInterarrivalJitter(b, this.interarrivalJitter)
        RtcpReportBlock.setLastSrTimestamp(b, this.lastSrTimestamp)
        RtcpReportBlock.setDelaySinceLastSr(b, this.delaySinceLastSr)

        b.rewind()
        buf = b

        return b
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("Report block")
            appendln("  source ssrc: $ssrc")
            appendln("  seq num cycles: $seqNumCycles")
            appendln("  max seq num: $seqNum")
            appendln("  fraction lost: $fractionLost")
            appendln("  cumulative lost: $cumulativePacketsLost")
            appendln("  interarrival jitter: $interarrivalJitter")
            appendln("  last sr timestamp: $lastSrTimestamp")
            appendln("  delay since last sr: $delaySinceLastSr")

            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as RtcpReportBlock
        return (ssrc == other.ssrc &&
                fractionLost == other.fractionLost &&
                cumulativePacketsLost == other.cumulativePacketsLost &&
                seqNumCycles == other.seqNumCycles &&
                seqNum == other.seqNum &&
                interarrivalJitter == other.interarrivalJitter &&
                lastSrTimestamp == other.lastSrTimestamp &&
                delaySinceLastSr == other.delaySinceLastSr)
    }

    override fun hashCode(): Int {
        return ssrc.hashCode() + fractionLost.hashCode() + cumulativePacketsLost.hashCode() +
                seqNumCycles.hashCode() + seqNum.hashCode() + interarrivalJitter.hashCode() +
                lastSrTimestamp.hashCode() + delaySinceLastSr.hashCode()
    }
}
