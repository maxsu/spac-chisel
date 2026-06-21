package spac.hw

import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

trait HasFwdTableIO { val io: FwdTableIO }
trait HasSchedulerIO { val io: SchedulerIO }

class SwitchTop(param: SwitchParams) extends Module {
  val io = IO(new Bundle {
    val rx = Vec(param.nPorts, Flipped(Decoupled(new AxisWord(param))))
    val tx = Vec(param.nPorts, Decoupled(new AxisWord(param)))
  })

  val scheduler = param.sched match {
    case RoundRobin => Module(new RoundRobinScheduler(param)): (Module with HasSchedulerIO)
    case ISLIP      => Module(new ISLIPScheduler(param)): (Module with HasSchedulerIO)
    case EDRRM      => Module(new EDRRMScheduler(param)): (Module with HasSchedulerIO)
  }
  val fwdTable = param.hash match {
    case FullLookup    => Module(new FullLookupTable(param)): (Module with HasFwdTableIO)
    case MultiBankHash => Module(new MultiBankHashEngine(param)): (Module with HasFwdTableIO)
  }

  case class Port(
    rx:      RxEngine,
    data:    Queue[AxisWord],
    metaRx:  Queue[Metadata],
    metaFwd: Queue[Metadata],
  )

  val ports = Seq.tabulate(param.nPorts) { i =>
    val d = 1 << param.qDepthLog
    val port = Port(
      rx      = Module(new RxEngine(param, i)),
      data    = Module(new Queue(new AxisWord(param), d)),
      metaRx  = Module(new Queue(new Metadata(param), d)),
      metaFwd = Module(new Queue(new Metadata(param), d)),
    )
    
    // Datapath
    
    // Output               -> Input                    // Note
    // ========================================================================
    
    // Ingress
    io.rx(i)                <> port.rx.io.dataIn        // Ingress AXIS stream
    
    // Parser
    port.rx.io.dataOut      <> port.data.io.enq         // Payload words buffered
    port.rx.io.metaOut      <> port.metaRx.io.enq       // Parsed header/metadata
    
    // Forwarding
    port.metaRx.io.deq      <> fwdTable.io.metaIn(i)    //  Lookup/learn destination port(s)
    fwdTable.io.metaOut(i)  <> port.metaFwd.io.enq      //  Resolved routing metadata buffered
    
    // Scheduler
    port.data.io.deq        <> scheduler.io.dataIn(i)   //  Fetch payload 
    port.metaFwd.io.deq     <> scheduler.io.metaIn(i)   //  Arbitrate routing decision

    // Egress
    scheduler.io.dataOut(i) <> io.tx(i)                 //  Egress AXIS stream

    port
  }
}

object SwitchTop extends App {
  val param = SwitchParams()
  ChiselStage.emitSystemVerilogFile(
    gen = new SwitchTop(param),
    args = Array("--target-dir", "generated"),
  )
  println("[SPAC] SystemVerilog emitted to generated/")
}
