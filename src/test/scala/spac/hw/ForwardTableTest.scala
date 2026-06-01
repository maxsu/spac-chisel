package spac.hw

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ForwardTableTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  val p = SwitchParams(nPorts = 4, addrBits = 4)  // 4-bit addr → 16-entry table

  def makeMeta(dut: FullLookupTable, port: Int,
               src: Int, dst: Int, bc: Boolean = false): Unit = {
    dut.io.metaIn(port).bits.srcAddr.poke(src.U)
    dut.io.metaIn(port).bits.dstAddr.poke(dst.U)
    dut.io.metaIn(port).bits.dstPort.poke(0.U)
    dut.io.metaIn(port).bits.pktLen.poke(64.U)
    dut.io.metaIn(port).bits.broadcast.poke(bc.B)
    dut.io.metaIn(port).bits.valid.poke(true.B)
    dut.io.metaIn(port).valid.poke(true.B)
  }

  "FullLookupTable" should "broadcast on unknown dst" in {
    test(new FullLookupTable(p)) { dut =>
      for (i <- 0 until p.nPorts) {
        dut.io.metaIn(i).valid.poke(false.B)
        dut.io.metaOut(i).ready.poke(true.B)
      }
      // Port 0 sends to unknown dst=7
      makeMeta(dut, 0, src = 1, dst = 7)
      dut.clock.step(1)
      dut.io.metaOut(0).valid.expect(true.B)
      dut.io.metaOut(0).bits.broadcast.expect(true.B)
    }
  }

  it should "learn and then unicast" in {
    test(new FullLookupTable(p)) { dut =>
      for (i <- 0 until p.nPorts) {
        dut.io.metaIn(i).valid.poke(false.B)
        dut.io.metaOut(i).ready.poke(true.B)
      }

      // Step 1: Port 2 sends src=5, dst=anything → learns that addr 5 is on port 2
      makeMeta(dut, 2, src = 5, dst = 9)
      dut.clock.step(1)
      dut.io.metaIn(2).valid.poke(false.B)

      // Step 2: Port 0 sends to dst=5 → should hit and return port 2
      makeMeta(dut, 0, src = 3, dst = 5)
      dut.clock.step(1)
      dut.io.metaOut(0).valid.expect(true.B)
      dut.io.metaOut(0).bits.broadcast.expect(false.B)
      dut.io.metaOut(0).bits.dstPort.expect(2.U)
    }
  }

  it should "handle all ports learning simultaneously" in {
    test(new FullLookupTable(p)) { dut =>
      for (i <- 0 until p.nPorts) dut.io.metaOut(i).ready.poke(true.B)

      // All 4 ports send a packet in the same cycle → all learn
      for (sp <- 0 until p.nPorts) makeMeta(dut, sp, src = sp, dst = (sp + 1) % p.nPorts)
      dut.clock.step(1)
      // Each port's src is learned; dst may or may not hit depending on order
      for (sp <- 0 until p.nPorts) dut.io.metaOut(sp).valid.expect(true.B)
      // Next cycle: port 0 sends to src=0 (which port 0 just learned itself)
      for (i <- 0 until p.nPorts) dut.io.metaIn(i).valid.poke(false.B)
      makeMeta(dut, 1, src = 99 % 16, dst = 0)  // dst=0 was learned at port 0
      dut.clock.step(1)
      dut.io.metaOut(1).bits.broadcast.expect(false.B)
      dut.io.metaOut(1).bits.dstPort.expect(0.U)
    }
  }
}
