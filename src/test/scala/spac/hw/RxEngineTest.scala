package spac.hw

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RxEngineTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val p = SwitchParams(nPorts = 4, addrBits = 8, dataBits = 512,
                       dstOffBits = 128, srcOffBits = 136, lenOffBits = 176)

  def makeWord(src: Int, dst: Int, len: Int, last: Boolean = true): BigInt = {
    var w = BigInt(0)
    w |= BigInt(len) << 176
    w |= BigInt(src) << 136
    w |= BigInt(dst) << 128
    w
  }

  "RxEngine" should "emit metadata and forward the header word (single-word packet)" in {
    test(new RxEngine(p, 0)) { dut =>
      dut.io.dataOut.ready.poke(true.B)
      dut.io.metaOut.ready.poke(true.B)
      dut.io.dataIn.bits.data.poke(makeWord(src=1, dst=2, len=64).U)
      dut.io.dataIn.bits.last.poke(true.B)
      dut.io.dataIn.valid.poke(true.B)
      // Check combinationally before stepping — RxEngine is purely combinational in sHeader
      dut.io.metaOut.valid.expect(true.B)
      dut.io.metaOut.bits.srcAddr.expect(1.U)
      dut.io.metaOut.bits.dstAddr.expect(2.U)
      dut.io.metaOut.bits.pktLen.expect(64.U)
      dut.io.dataOut.valid.expect(true.B)
      dut.io.dataIn.ready.expect(true.B)
    }
  }

  it should "transition to CONSUME for multi-word packets" in {
    test(new RxEngine(p, 0)) { dut =>
      dut.io.dataOut.ready.poke(true.B)
      dut.io.metaOut.ready.poke(true.B)

      // Word 0: header (not last)
      val hdr = makeWord(src=3, dst=5, len=128, last=false)
      dut.io.dataIn.bits.data.poke(hdr.U)
      dut.io.dataIn.bits.last.poke(false.B)
      dut.io.dataIn.valid.poke(true.B)

      // Combinationally: both outputs valid
      dut.io.metaOut.valid.expect(true.B)
      dut.io.metaOut.bits.srcAddr.expect(3.U)
      dut.io.dataOut.valid.expect(true.B)
      dut.clock.step(1)   // consume header word, transition to sConsume

      // Word 1: payload (last) — in sConsume, no meta
      dut.io.dataIn.bits.data.poke(0xdeadbeefL.U)
      dut.io.dataIn.bits.last.poke(true.B)
      dut.clock.step(0)   // sample combinational outputs
      dut.io.metaOut.valid.expect(false.B)
      dut.io.dataOut.valid.expect(true.B)
      dut.clock.step(1)   // consume, return to sHeader

      // Back in sHeader
      dut.io.dataIn.bits.last.poke(true.B)
      dut.io.dataIn.bits.data.poke(makeWord(src=7, dst=8, len=32).U)
      dut.io.metaOut.valid.expect(true.B)
      dut.io.metaOut.bits.srcAddr.expect(7.U)
    }
  }

  it should "stall when dataOut is not ready" in {
    test(new RxEngine(p, 0)) { dut =>
      dut.io.dataOut.ready.poke(false.B)
      dut.io.metaOut.ready.poke(true.B)
      dut.io.dataIn.bits.data.poke(makeWord(1, 2, 64).U)
      dut.io.dataIn.bits.last.poke(true.B)
      dut.io.dataIn.valid.poke(true.B)
      dut.io.dataIn.ready.expect(false.B)
      dut.io.dataOut.valid.expect(false.B)
    }
  }
}
