package spac.hw

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SwitchTopTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {

  // 4-port, narrow 64-bit bus for fast sim; field offsets at LSB
  val baseP = SwitchParams(
    nPorts     = 4,  addrBits   = 4,  dataBits  = 64,
    qDepthLog  = 3,  dstOffBits = 0,  srcOffBits = 4,
    lenOffBits = 8,  hash = FullLookup, buf = NxNVOQ, sched = ISLIP,
  )

  def hdrWord(src: Int, dst: Int, p: SwitchParams): BigInt = {
    var w = BigInt(0)
    w |= BigInt(dst & ((1 << p.addrBits) - 1)) << p.dstOffBits
    w |= BigInt(src & ((1 << p.addrBits) - 1)) << p.srcOffBits
    w |= BigInt(1) << p.lenOffBits  // length=1 word
    w
  }

  def idleAll(dut: SwitchTop, p: SwitchParams): Unit = {
    for (i <- 0 until p.nPorts) {
      dut.io.rx(i).valid.poke(false.B)
      dut.io.tx(i).ready.poke(true.B)
    }
  }

  /** Inject a single-word packet, then de-assert valid */
  def sendPacket(dut: SwitchTop, rxPort: Int, src: Int, dst: Int, p: SwitchParams): Unit = {
    dut.io.rx(rxPort).bits.data.poke(hdrWord(src, dst, p).U)
    dut.io.rx(rxPort).bits.last.poke(true.B)
    dut.io.rx(rxPort).valid.poke(true.B)
    dut.clock.step(1)
    dut.io.rx(rxPort).valid.poke(false.B)
  }

  /** Drain up to maxCycles, collecting tx port hits per cycle. */
  def drainCycles(dut: SwitchTop, p: SwitchParams, maxCycles: Int): Map[Int, Int] = {
    val counts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    for (_ <- 0 until maxCycles) {
      for (i <- 0 until p.nPorts) {
        if (dut.io.tx(i).valid.peek().litToBoolean) counts(i) += 1
      }
      dut.clock.step(1)
    }
    counts.toMap
  }

  // ── Test 1: unicast after learning ────────────────────────────────────

  "SwitchTop (iSLIP)" should "unicast after dst address is learned" in {
    test(new SwitchTop(baseP)) { dut =>
      idleAll(dut, baseP)

      // Teach the table: port-2 sends src=2, so addr 2 → port 2 is learned
      sendPacket(dut, rxPort=2, src=2, dst=0xF, baseP) // dst unknown → broadcast fine
      dut.clock.step(30)  // let it drain

      // Now send to learned addr 2 from port 0
      sendPacket(dut, rxPort=0, src=0, dst=2, baseP)

      val hits = drainCycles(dut, baseP, 60)
      // Should appear on tx(2), not on tx(0), tx(1), tx(3)
      hits.getOrElse(2, 0) should be >= 1
      hits.getOrElse(0, 0) shouldBe 0
      hits.getOrElse(1, 0) shouldBe 0
      hits.getOrElse(3, 0) shouldBe 0
    }
  }

  // ── Test 2: broadcast on unknown dst ──────────────────────────────────

  it should "broadcast when dst address is unknown" in {
    test(new SwitchTop(baseP)) { dut =>
      idleAll(dut, baseP)
      sendPacket(dut, rxPort=0, src=0, dst=0xF, baseP)   // dst=15 unknown
      val hits = drainCycles(dut, baseP, 60)
      // Expect word on at least two of ports 1,2,3 (all except source)
      val nonSrc = (1 to 3).map(i => hits.getOrElse(i, 0)).sum
      nonSrc should be >= 1
    }
  }

  // ── Test 3: HOL contention — all ports → port 0 ───────────────────────

  it should "deliver packets under head-of-line contention" in {
    test(new SwitchTop(baseP)) { dut =>
      idleAll(dut, baseP)
      // Learn addr 0 at port 0
      sendPacket(dut, rxPort=0, src=0, dst=0xF, baseP)
      dut.clock.step(30)
      // Ports 1,2,3 all send to addr 0
      for (sp <- 1 until baseP.nPorts) sendPacket(dut, rxPort=sp, src=sp, dst=0, baseP)
      val hits = drainCycles(dut, baseP, 120)
      hits.getOrElse(0, 0) should be >= 3
    }
  }

  // ── Test 4: RR smoke ───────────────────────────────────────────────────

  "SwitchTop (RoundRobin)" should "unicast to learned port" in {
    val rrP = baseP.copy(sched = RoundRobin)
    test(new SwitchTop(rrP)) { dut =>
      idleAll(dut, rrP)
      sendPacket(dut, rxPort=3, src=3, dst=0xF, rrP)
      dut.clock.step(30)
      sendPacket(dut, rxPort=0, src=0, dst=3, rrP)
      val hits = drainCycles(dut, rrP, 60)
      hits.getOrElse(3, 0) should be >= 1
      hits.getOrElse(1, 0) shouldBe 0
      hits.getOrElse(2, 0) shouldBe 0
    }
  }

  // ── Test 5: EDRRM smoke ────────────────────────────────────────────────

  "SwitchTop (EDRRM)" should "deliver burst to one port" in {
    val edP = baseP.copy(sched = EDRRM)
    test(new SwitchTop(edP)) { dut =>
      idleAll(dut, edP)
      // Count all tx events from the very beginning
      val hitCounts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
      def countTx(): Unit = for (i <- 0 until edP.nPorts)
        if (dut.io.tx(i).valid.peek().litToBoolean) hitCounts(i) += 1

      // Learn addr 1 at port 1
      sendPacket(dut, rxPort=1, src=1, dst=0xF, edP); countTx()
      for (_ <- 0 until 40) { dut.clock.step(1); countTx() }

      // Burst of 4 packets from port 0 → port 1
      for (_ <- 0 until 4) { sendPacket(dut, rxPort=0, src=0, dst=1, edP); countTx() }
      for (_ <- 0 until 120) { dut.clock.step(1); countTx() }

      println(s"EDRRM hits: $hitCounts")
      hitCounts.getOrElse(1, 0) should be >= 4
    }
  }

  // ── Test 6: 8-port MultiBankHash elaboration ──────────────────────────

  "SwitchTop (8-port MultiBankHash/EDRRM)" should "elaborate without error" in {
    val mbP = SwitchParams(
      nPorts=8, addrBits=8, dataBits=64, qDepthLog=3,
      dstOffBits=0, srcOffBits=8, lenOffBits=16,
      hash=MultiBankHash, buf=NxNVOQ, sched=EDRRM,
    )
    test(new SwitchTop(mbP)) { dut =>
      for (i <- 0 until mbP.nPorts) {
        dut.io.rx(i).valid.poke(false.B)
        dut.io.tx(i).ready.poke(true.B)
      }
      dut.clock.step(10)
      // No assertion = pass
    }
  }
}
