package spac.hw

import chisel3._
import chisel3.util._

sealed trait HashType
case object FullLookup extends HashType
case object MultiBankHash extends HashType

sealed trait BufType
case object NxNVOQ extends BufType
case object SharedVOQ extends BufType 

sealed trait SchedType
case object RoundRobin extends SchedType
case object ISLIP extends SchedType
case object EDRRM extends SchedType

case class SwitchParams(
 nPorts : Int = 4,
 addrBits : Int = 8, // width of src/dst address fields
 dataBits : Int = 512, // AXI-Stream word width
 qDepthLog : Int = 3, // VOQ depth = 2^qDepthLog
 hashBits : Int = 7, // MultiBankHash table index bits
 hash : HashType = FullLookup,
 buf : BufType = NxNVOQ,
 sched : SchedType = ISLIP,
 // field offsets within 512-bit word
 dstOffBits : Int = 128,
 srcOffBits : Int = 136,
 lenOffBits : Int = 176,
) {
 require(isPow2(1 << qDepthLog))
 require(nPorts >= 2)
 require(addrBits >= 1 && addrBits <= 16)
 require(dataBits >= 64)
 val qDepth : Int = 1 << qDepthLog
 val portBits : Int = log2Ceil(nPorts)
 val pktLenBits: Int = 32
}

class AxisWord(p: SwitchParams) extends Bundle {
 val data = UInt(p.dataBits.W)
 val last = Bool()
}

class Metadata(p: SwitchParams) extends Bundle {
  val srcAddr   = UInt(p.addrBits.W)
  val dstAddr   = UInt(p.addrBits.W)
  val dstPort   = UInt(p.portBits.W)
  val pktLen    = UInt(p.pktLenBits.W)
  val broadcast = Bool()
  val valid     = Bool()
}

class SchedulerIO(p: SwitchParams) extends Bundle {
  val dataIn  = Vec(p.nPorts, Flipped(Decoupled(new AxisWord(p))))
  val metaIn  = Vec(p.nPorts, Flipped(Decoupled(new Metadata(p))))
  val dataOut = Vec(p.nPorts, Decoupled(new AxisWord(p)))
}

class NxNVOQIO(p: SwitchParams) extends Bundle {
  val enqData = Input(Vec(p.nPorts, new AxisWord(p)))
  val enqMask = Input(Vec(p.nPorts, UInt(p.nPorts.W)))
  val enqEn   = Input(Vec(p.nPorts, Bool()))
  val deqSp   = Input(Vec(p.nPorts, UInt(p.portBits.W)))
  val deqEn   = Input(Vec(p.nPorts, Bool()))
  val deqData = Output(Vec(p.nPorts, new AxisWord(p)))
  val deqOk   = Output(Vec(p.nPorts, Bool()))
  val empty   = Output(Vec(p.nPorts, Vec(p.nPorts, Bool())))
  val full    = Output(Vec(p.nPorts, Vec(p.nPorts, Bool())))
}

class DigestIO(p: SwitchParams) extends Bundle {
  val dataIn  = Flipped(Decoupled(new AxisWord(p)))
  val metaIn  = Flipped(Decoupled(new Metadata(p)))
  val word    = Output(new AxisWord(p))
  val mask    = Output(UInt(p.nPorts.W))
  val enq     = Output(Bool())
  val anyFull = Input(Bool())
}