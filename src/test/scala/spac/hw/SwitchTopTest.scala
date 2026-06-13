package spac.hw

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SwitchTopTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  val baseP = SwitchParams(
    nPorts=4, addrBits=4, dataBits=64, qDepthLog=3,
    dstOffBits=0, srcOffBits=4, lenOffBits=8,
    hash=FullLookup, buf=NxNVOQ, sched=ISLIP,
  )
  private var device:  SwitchTop    = _
  private var activeP: SwitchParams = _

  def switchTest(params: SwitchParams = baseP)(body: => Unit): Unit =
    test(new SwitchTop(params)).withAnnotations(Seq(VerilatorBackendAnnotation)) {
      dut => activeP = params; device = dut; idleAll; body
    }

  def idleAll = for (port <- 0 until activeP.nPorts) {
    device.io.rx(port).valid.poke(false.B)
    device.io.tx(port).ready.poke(true.B)
  }

  def sendPacket(rxPort: Int, src: Int, dst: Int): Unit = {
    val addrMask = (1 << activeP.addrBits) - 1
    var word = BigInt(0)
    word |= BigInt(dst & addrMask) << activeP.dstOffBits
    word |= BigInt(src & addrMask) << activeP.srcOffBits
    word |= BigInt(1)              << activeP.lenOffBits
    device.io.rx(rxPort).bits.data.poke(word.U)
    device.io.rx(rxPort).bits.last.poke(true.B)
    device.io.rx(rxPort).valid.poke(true.B)
    device.clock.step(1)
    device.io.rx(rxPort).valid.poke(false.B)
  }

  def step(n: Int) = device.clock.step(n)

  def drain(cycles: Int): Map[Int, Int] = {
    val counts = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    for (_ <- 0 until cycles) {
      for (port <- 0 until activeP.nPorts)
        if (device.io.tx(port).valid.peek().litToBoolean) counts(port) += 1
      device.clock.step(1)
    }
    counts.toMap
  }

  "SwitchTop (ISLIP)" should "unicast after dst address is learned" in switchTest() {
    sendPacket(rxPort=2, src=2, dst=0xF)
    step(30)
    sendPacket(rxPort=0, src=0, dst=2)
    val hits = drain(60)
    hits.getOrElse(2, 0) should be >= 1
    hits.getOrElse(0, 0) shouldBe 0
    hits.getOrElse(1, 0) shouldBe 0
    hits.getOrElse(3, 0) shouldBe 0
  }

  it should "broadcast when dst address is unknown" in switchTest() {
    sendPacket(rxPort=0, src=0, dst=0xF)
    (1 to 3).map(drain(60).getOrElse(_, 0)).sum should be >= 1
  }

  it should "deliver all packets under head-of-line contention" in switchTest() {
    sendPacket(rxPort=0, src=0, dst=0xF)
    step(30)
    for (port <- 1 until baseP.nPorts) sendPacket(rxPort=port, src=port, dst=0)
    drain(120).getOrElse(0, 0) should be >= 3
  }

  "SwitchTop (RoundRobin)" should "unicast to learned port" in switchTest(baseP.copy(sched=RoundRobin)) {
    sendPacket(rxPort=3, src=3, dst=0xF)
    step(30)
    sendPacket(rxPort=0, src=0, dst=3)
    val hits = drain(60)
    hits.getOrElse(3, 0) should be >= 1
    hits.getOrElse(1, 0) shouldBe 0
    hits.getOrElse(2, 0) shouldBe 0
  }

  "SwitchTop (EDRRM)" should "deliver a burst to the correct port" in switchTest(baseP.copy(sched=EDRRM)) {
    sendPacket(rxPort=1, src=1, dst=0xF)
    step(40)
    for (_ <- 0 until 4) sendPacket(rxPort=0, src=0, dst=1)
    drain(120).getOrElse(1, 0) should be >= 4
  }

  "SwitchTop (8-port MultiBankHash/EDRRM)" should "elaborate without error" in {
    val mbP = SwitchParams(nPorts=8, addrBits=8, dataBits=64, qDepthLog=3,
                           dstOffBits=0, srcOffBits=8, lenOffBits=16,
                           hash=MultiBankHash, buf=NxNVOQ, sched=EDRRM)
    switchTest(mbP) { step(10) }
  }
}
