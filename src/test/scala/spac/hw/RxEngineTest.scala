package spac.hw

import chisel3._
import chisel3.simulator.scalatest.ChiselSim
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RxEngineTest extends AnyFlatSpec with ChiselSim with Matchers {
  val p = SwitchParams(nPorts=4, addrBits=8, dataBits=512,
                       dstOffBits=128, srcOffBits=136, lenOffBits=176)
  private var device: RxEngine = _

  def rxTest(body: => Unit): Unit =
    simulate(new RxEngine(p, 0)) { dut => 
      device = dut
      body 
    }

  private def hdrWord(src: Int, dst: Int, len: Int): BigInt = {
    var w = BigInt(0)
    w |= BigInt(len) << p.lenOffBits
    w |= BigInt(src) << p.srcOffBits
    w |= BigInt(dst) << p.dstOffBits
    w
  }

  def outputsReady = { device.io.dataOut.ready.poke(true.B);  device.io.metaOut.ready.poke(true.B) }
  def blockOutput  = { device.io.dataOut.ready.poke(false.B); device.io.metaOut.ready.poke(true.B) }

  def sendHeader(src: Int, dst: Int, len: Int, isLast: Boolean = true): Unit = {
    device.io.dataIn.bits.data.poke(hdrWord(src, dst, len).U)
    device.io.dataIn.bits.last.poke(isLast.B)
    device.io.dataIn.valid.poke(true.B)
  }
  def sendPayload(data: Long, isLast: Boolean): Unit = {
    device.io.dataIn.bits.data.poke(data.U)
    device.io.dataIn.bits.last.poke(isLast.B)
  }
  def step(n: Int = 1) = device.clock.step(n)

  def expectMeta(src: Int, dst: Int, len: Int): Unit = {
    device.io.metaOut.valid.expect(true.B)
    device.io.metaOut.bits.srcAddr.expect(src.U)
    device.io.metaOut.bits.dstAddr.expect(dst.U)
    device.io.metaOut.bits.pktLen.expect(len.U)
  }
  def expectNoMeta        = device.io.metaOut.valid.expect(false.B)
  def expectDataForwarded = { device.io.dataOut.valid.expect(true.B); device.io.dataIn.ready.expect(true.B) }
  def expectInputStalled  = { device.io.dataIn.ready.expect(false.B); device.io.dataOut.valid.expect(false.B) }

  "RxEngine" should "emit metadata and forward the header word" in rxTest {
    outputsReady
    sendHeader(src=1, dst=2, len=64)
    expectMeta(src=1, dst=2, len=64)
    expectDataForwarded
  }

  it should "pass payload words through in CONSUME state then reset" in rxTest {
    outputsReady
    sendHeader(src=3, dst=5, len=128, isLast=false)
    expectMeta(src=3, dst=5, len=128)
    expectDataForwarded
    step()

    sendPayload(0xdeadbeefL, isLast=true)
    step(0)
    expectNoMeta
    step()

    sendHeader(src=7, dst=8, len=32)
    expectMeta(src=7, dst=8, len=32)
  }

  it should "stall input when data output is back-pressured" in rxTest {
    blockOutput
    sendHeader(src=1, dst=2, len=64)
    expectInputStalled
  }
}
