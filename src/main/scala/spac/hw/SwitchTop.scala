package spac.hw

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

// Traits to allow structural typing for the two module variants
trait HasFwdTableIO { val io: FwdTableIO }
trait HasSchedulerIO { val io: SchedulerIO }

class SwitchTop(p: SwitchParams) extends Module {
  val io = IO(new Bundle {
    val rx = Vec(p.nPorts, Flipped(Decoupled(new AxisWord(p))))
    val tx = Vec(p.nPorts, Decoupled(new AxisWord(p)))
  })

  //   RX engines  
  val rxEngines = Seq.tabulate(p.nPorts)(i => Module(new RxEngine(p, i)))
  for (i <- 0 until p.nPorts) rxEngines(i).io.dataIn <> io.rx(i)

  //   Data path queues (RX → scheduler)  
  val dataQueues = Seq.fill(p.nPorts)(Module(new Queue(new AxisWord(p), 64)))
  for (i <- 0 until p.nPorts) dataQueues(i).io.enq <> rxEngines(i).io.dataOut

  //   Meta queues (RX → ForwardTable)  
  val metaRxQ = Seq.fill(p.nPorts)(Module(new Queue(new Metadata(p), 64)))
  for (i <- 0 until p.nPorts) metaRxQ(i).io.enq <> rxEngines(i).io.metaOut

  //   Forward table  
  val fwdTable = p.hash match {
    case FullLookup    => Module(new FullLookupTable(p)): (Module with HasFwdTableIO)
    case MultiBankHash => Module(new MultiBankHashEngine(p)): (Module with HasFwdTableIO)
  }
  for (i <- 0 until p.nPorts) fwdTable.io.metaIn(i) <> metaRxQ(i).io.deq

  //   Meta queues (ForwardTable → scheduler)  
  val metaFwdQ = Seq.fill(p.nPorts)(Module(new Queue(new Metadata(p), 64)))
  for (i <- 0 until p.nPorts) metaFwdQ(i).io.enq <> fwdTable.io.metaOut(i)

  //   Scheduler  
  val scheduler = p.sched match {
    case RoundRobin => Module(new RoundRobinScheduler(p)): (Module with HasSchedulerIO)
    case ISLIP      => Module(new ISLIPScheduler(p)): (Module with HasSchedulerIO)
    case EDRRM      => Module(new EDRRMScheduler(p)): (Module with HasSchedulerIO)
  }
  for (i <- 0 until p.nPorts) {
    scheduler.io.dataIn(i) <> dataQueues(i).io.deq
    scheduler.io.metaIn(i) <> metaFwdQ(i).io.deq
  }
  for (i <- 0 until p.nPorts) io.tx(i) <> scheduler.io.dataOut(i)
}

object SwitchTop extends App {
  val p = SwitchParams()
  ChiselStage.emitSystemVerilogFile(
    gen = new SwitchTop(p),
    args = Array("--target-dir", "generated"),
  )
  println("[SPAC] SystemVerilog emitted to generated/")
}
