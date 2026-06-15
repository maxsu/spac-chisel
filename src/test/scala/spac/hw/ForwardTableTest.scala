package spac.hw

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ForwardTableTest extends AnyFlatSpec with ChiselSim with Matchers {
  val params = SwitchParams(nPorts = 4, addrBits = 4)
  private var device: FullLookupTable = _

  def tableTest(body: => Unit): Unit =
    simulate(new FullLookupTable(p)) { dut =>
      device = dut
      silence
      readyAll
      body
    }

  def silence  = for (port <- 0 until params.nPorts) device.io.metaIn(port).valid.poke(false.B)
  def readyAll = for (port <- 0 until params.nPorts) device.io.metaOut(port).ready.poke(true.B)
  def step     = device.clock.step(1)

  def send(port: Int, src: Int, dst: Int): Unit = {
    val slot = device.io.metaIn(port)
    slot.bits.srcAddr.poke(src.U)
    slot.bits.dstAddr.poke(dst.U)
    slot.bits.dstPort.poke(0.U)
    slot.bits.pktLen.poke(64.U)
    slot.bits.broadcast.poke(false.B)
    slot.bits.valid.poke(true.B)
    slot.valid.poke(true.B)
  }

  def expectValid(port: Int)                      = device.io.metaOut(port).valid.expect(true.B)
  def expectBroadcast(port: Int): Unit            = { expectValid(port); device.io.metaOut(port).bits.broadcast.expect(true.B) }
  def expectUnicast(port: Int, toPort: Int): Unit = {
    expectValid(port)
    device.io.metaOut(port).bits.dstPort.expect(toPort.U)
    device.io.metaOut(port).bits.broadcast.expect(false.B)
  }

  behavior of "FullLookupTable"

  it should "broadcast on unknown dst" in tableTest {
    send(port=0, src=1, dst=7)
    step
    expectBroadcast(port=0)
  }

  it should "learn a source, then unicast back to it" in tableTest {
    send(port=2, src=5, dst=9)
    step
    silence
    send(port=0, src=3, dst=5)
    step
    expectUnicast(port=0, toPort=2)
  }

  it should "let every port learn in the same cycle" in tableTest {
    for (port <- 0 until params.nPorts) send(port, src=port, dst=(port+1) % params.nPorts)
    step
    for (port <- 0 until params.nPorts) expectValid(port)
    silence
    send(port=1, src=99 % 16, dst=0)
    step
    expectUnicast(port=1, toPort=0)
  }
}
