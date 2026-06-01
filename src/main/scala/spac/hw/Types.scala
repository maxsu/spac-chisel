package spac.hw

import chisel3._
import chisel3.util._

// ── Architecture selection ADTs ───────────────────────────────────────────

sealed trait HashType
case object FullLookup    extends HashType
case object MultiBankHash extends HashType

sealed trait BufType
/** N×N queues; data word copied once per destination. */
case object NxNVOQ    extends BufType
/** Shared data buffer + pointer queues + refcount bitmap. */
case object SharedVOQ extends BufType

sealed trait SchedType
case object RoundRobin extends SchedType
case object ISLIP      extends SchedType
case object EDRRM      extends SchedType

// ── Switch parameters ─────────────────────────────────────────────────────

/**
 * All hardware parameters in one place.
 *
 * Field-offset parameters (dstOffBits, srcOffBits, lenOffBits) are the
 * bit positions of the routing fields within the 512-bit AXI word,
 * measured from bit-0.  Defaults match the demo_clean-generated packet.hpp
 * (uint8_t src/dst, net-blocks standard layout).
 */
case class SwitchParams(
  nPorts      : Int      = 4,
  addrBits    : Int      = 8,    // width of src/dst address fields
  dataBits    : Int      = 512,  // AXI-Stream word width in bits
  qDepthLog   : Int      = 3,    // VOQ depth = 2^qDepthLog
  hashBits    : Int      = 7,    // MultiBankHash table index bits
  hash        : HashType = FullLookup,
  buf         : BufType  = NxNVOQ,
  sched       : SchedType = ISLIP,
  // ── protocol-layer field offsets (bits from LSB in 512-bit word) ──
  dstOffBits  : Int      = 128,
  srcOffBits  : Int      = 136,
  lenOffBits  : Int      = 176,
) {
  require(isPow2(1 << qDepthLog))
  require(nPorts >= 2)
  require(addrBits >= 1 && addrBits <= 16)
  require(dataBits >= 64)

  val qDepth   : Int = 1 << qDepthLog
  val portBits : Int = log2Ceil(nPorts)
  val pktLenBits: Int = 32
}

// ── AXI-Stream bundle ─────────────────────────────────────────────────────

class AxisWord(p: SwitchParams) extends Bundle {
  val data = UInt(p.dataBits.W)
  val last = Bool()
}

// ── Metadata sidecar ─────────────────────────────────────────────────────

class Metadata(p: SwitchParams) extends Bundle {
  val srcAddr   = UInt(p.addrBits.W)
  val dstAddr   = UInt(p.addrBits.W)
  val dstPort   = UInt(p.portBits.W)
  val pktLen    = UInt(p.pktLenBits.W)
  val broadcast = Bool()
  val valid     = Bool()
}

// ── Convenience constructors ──────────────────────────────────────────────

object Metadata {
  def apply(p: SwitchParams)(
    src: UInt, dst: UInt, port: UInt,
    len: UInt, bc: Bool, v: Bool
  ): Metadata = {
    val m = Wire(new Metadata(p))
    m.srcAddr   := src
    m.dstAddr   := dst
    m.dstPort   := port
    m.pktLen    := len
    m.broadcast := bc
    m.valid     := v
    m
  }
}
