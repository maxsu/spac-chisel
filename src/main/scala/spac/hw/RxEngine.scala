package spac.hw

import chisel3._
import chisel3.util._

/**
 * RxEngine — one instance per ingress port.
 *
 * Two-state FSM:
 *   sHeader  : consume word-0, extract src/dst/len, emit Metadata + forward word
 *   sConsume : pass remaining words through until `last`
 *
 * The dataOut stream carries the raw AXI word (including the header word)
 * so the scheduler can buffer full packets.  metaOut is emitted exactly
 * once per packet, concurrent with the first data word.
 *
 * Back-pressure: if either output is not ready we stall (input not consumed).
 */
class RxEngine(p: SwitchParams, portIdx: Int) extends Module {
  val io = IO(new Bundle {
    val dataIn  = Flipped(Decoupled(new AxisWord(p)))
    val dataOut = Decoupled(new AxisWord(p))
    val metaOut = Decoupled(new Metadata(p))
  })

  // ── FSM states ────────────────────────────────────────────────────────
  val sHeader :: sConsume :: Nil = Enum(2)
  val state = RegInit(sHeader)

  // ── Extract routing fields from word-0 ───────────────────────────────
  // Bit-slice helpers; offsets from SwitchParams
  def extractField(word: UInt, offBits: Int, widthBits: Int): UInt =
    word(offBits + widthBits - 1, offBits)

  val word    = io.dataIn.bits.data
  val srcAddr = extractField(word, p.srcOffBits, p.addrBits)
  val dstAddr = extractField(word, p.dstOffBits, p.addrBits)
  val pktLen  = extractField(word, p.lenOffBits,  p.pktLenBits)

  // ── Default output wiring ─────────────────────────────────────────────
  io.dataOut.bits  := io.dataIn.bits
  io.dataOut.valid := false.B
  io.metaOut.valid := false.B
  io.dataIn.ready  := false.B

  // Build metadata wire (combinational, only meaningful in sHeader)
  val metaWire = Wire(new Metadata(p))
  metaWire.srcAddr   := srcAddr
  metaWire.dstAddr   := dstAddr
  metaWire.dstPort   := 0.U          // filled in by ForwardTable
  metaWire.pktLen    := pktLen
  metaWire.broadcast := false.B      // ForwardTable may set true
  metaWire.valid     := true.B
  io.metaOut.bits    := metaWire

  switch(state) {
    is(sHeader) {
      // Stall until both outputs are ready
      when(io.dataIn.valid && io.dataOut.ready && io.metaOut.ready) {
        io.dataOut.valid := true.B
        io.metaOut.valid := true.B
        io.dataIn.ready  := true.B
        state := Mux(io.dataIn.bits.last, sHeader, sConsume)
      }
    }
    is(sConsume) {
      // Pass-through; no metadata
      io.dataOut.valid := io.dataIn.valid
      io.dataIn.ready  := io.dataOut.ready
      when(io.dataIn.valid && io.dataOut.ready && io.dataIn.bits.last) {
        state := sHeader
      }
    }
  }
}
