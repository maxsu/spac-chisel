# SPAC Chisel/Scala Implementation Spec

Target: ~2100 lines. One repo, one build tool (sbt). Three layers: hardware (Chisel), DSE (Scala), protocol (Scala). No Python, no HLS, no net-blocks.

---

## 0. Build Layout

```
build.sbt
src/main/scala/
  spac/hw/          ← Chisel RTL
  spac/dse/         ← DSE + simulation
  spac/proto/       ← protocol codegen
src/test/scala/
  spac/hw/          ← ChiselSim tests
```

`build.sbt` deps: `chisel3 3.6`, `chisel-iotesters`, `breeze 2.1`, `scala-csv 1.3`.

---

## 1. Shared Types — `spac/hw/Types.scala` (~40 lines)

```scala
sealed trait HashType
case object FullLookup    extends HashType
case object MultiBankHash extends HashType

sealed trait BufType
case object NxNVOQ    extends BufType   // N×N queues, data copied per-dst
case object SharedVOQ extends BufType   // shared data buf + pointer queues + bitmap

sealed trait SchedType
case object RoundRobin extends SchedType
case object ISLIP      extends SchedType
case object EDRRM      extends SchedType

case class SwitchParams(
  nPorts      : Int       = 4,
  addrBits    : Int       = 8,   // width of src/dst address fields
  dataBits    : Int       = 512, // AXI-Stream word width
  qDepthLog   : Int       = 3,   // VOQ depth = 2^qDepthLog = 8
  hashBits    : Int       = 7,   // MultiBankHash table index bits
  hash        : HashType  = FullLookup,
  buf         : BufType   = NxNVOQ,
  sched       : SchedType = ISLIP,
) {
  val qDepth   = 1 << qDepthLog
  val portBits = log2Ceil(nPorts)
  val pktLenBits = 32
}
```

**`AxisWord`**: `data: UInt(dataBits.W)`, `last: Bool`.  
**`Metadata`**: `srcAddr`, `dstAddr` (addrBits), `dstPort` (portBits), `pktLen` (32), `broadcast: Bool`, `valid: Bool`.  
All inter-module streams: `Decoupled(AxisWord)` for data, `Decoupled(Metadata)` for meta.

---

## 2. Hardware Layer — `spac/hw/`

### 2.1 `RxEngine.scala` (~55 lines)

One instance per port. Two-state FSM: `sHeader` / `sConsume`.

- **sHeader**: when input valid, read word 0, extract `src=word(SRC_OFF+W-1,SRC_OFF)`, `dst=word(DST_OFF+W-1,DST_OFF)`, `len=word(31,0)`. Emit `Metadata` on `metaOut`. Forward word on `dataOut`. If `last`, stay in `sHeader`; else go to `sConsume`.
- **sConsume**: pass words through to `dataOut`. On `last`, return to `sHeader`.

Field offsets come from `SwitchParams` (set by protocol layer at elaboration time; defaults match `demo_clean` output: `DST_OFF=128`, `SRC_OFF=136`, `LEN_OFF=176` — in bits from LSB of the 512-bit word).

> ⚠️ Offset params must be `val`s in `SwitchParams`, populated by `ProtocolSpec` before `SwitchTop` is elaborated.

### 2.2 `ForwardTable.scala` (~120 lines total, two `Module`s)

**`FullLookupTable`**  
State: `fwd = RegInit(VecInit(Seq.fill(1<<addrBits)(0.U((1+portBits).W))))` — fully registered, fully partitioned.  
Each cycle, for all ports in parallel (unrolled):
- Write: `fwd(meta.srcAddr) := Cat(1.U(1.W), srcPort.U(portBits.W))`
- Read: entry = `fwd(meta.dstAddr)`. If valid bit set → `meta.dstPort = entry(portBits-1,0)`, `broadcast=false`; else `broadcast=true`.
- Emit updated `Metadata` on `metaOut` same cycle. II = 1.

**`MultiBankHashEngine`**  
`BANKS = nPorts` banks, each `SyncReadMem(1<<hashBits, UInt((1+addrBits+portBits).W))`.  
Per-bank: one RR arbiter across ports for write-port (src learn) and one for read-port (dst lookup). Pointers `savePtr(b)`, `readPtr(b)` advance each cycle.  
Buffer depth 1 per port: registers `buf(p): Metadata`, `saved(p): Bool`, `read(p): Bool`.  
When both `saved` and `read` true for port p: accept new meta from input. II = 3.

### 2.3 `VOQBuffer.scala` — instantiated inside scheduler (~embedded)

**NxNVOQ**: `data = Mem(nPorts, nPorts, qDepth, AxisWord)`. Heads/tails `Reg(Vec(nPorts, Vec(nPorts, UInt(qDepthLog.W))))`. Enqueue writes data directly to `data(sp)(dp)(tail)`, increments tail. Dequeue reads `data(sp)(dp)(head)`, increments head. No free list needed (data duplicated per dst).

**SharedVOQ**: `data = Mem(nPorts, qDepth, AxisWord)`, `bitmap = Mem(nPorts, qDepth, UInt(nPorts.W))`, `freeHead/freeTail` per port, `freeList = Mem(nPorts, qDepth, UInt(qDepthLog.W))`. VOQ pointers: `voqHead/voqTail(sp)(dp)` index into `freeList`. Enqueue: pop free slot, write data+bitmap, push slot index into all requested dst VOQs. Dequeue: pop slot from VOQ, XOR bitmap; if bitmap==0 push slot back to free list.

### 2.4 Schedulers — `Schedulers.scala` (~400 lines, three classes)

All schedulers share the same IO: `dataIn(nPorts): Decoupled(AxisWord)`, `metaIn(nPorts): Decoupled(Metadata)`, `dataOut(nPorts): Decoupled(AxisWord)`, `reset: Bool`.

Digest FSM per input port (shared across all three schedulers): states `sIdle / sUnicast / sBroadcast`. On sIdle: accept meta + first data word together (both must be valid). Unicast mask = `1 << dstPort`. Broadcast mask = `~(1 << sp)`. Subsequent words (sUnicast/sBroadcast): pass through until `last`.

**`RRScheduler`** (handles both NxNVOQ and SharedVOQ via `buf` param)  
TX side: per output port `dp`, rotating pointer `txNext(dp)`. Each cycle: pick `sp = if txBusy(dp) then txServing(dp) else txNext(dp)`. If `!voqEmpty(sp)(dp)` and (`txBusy(dp)` or `!spBusy(sp)`): dequeue word, emit on `dataOut(dp)`. On `last`: clear `txBusy(dp)`, clear `spBusy(sp)`, advance `txNext(dp)`. II = 1 target (pipeline the snapshot reads).

**`ISLIPScheduler`** (NxNVOQ or SharedVOQ)  
TX side two-phase per cycle:
1. **Grant**: for each `dp`: if `txBusy(dp)` → grant `txServing(dp)` if VOQ non-empty; else scan from `txNextSp(dp)` for first `!spBusy(sp) && !voqEmpty(sp)(dp)`. Advance `txNextSp(dp)`.
2. **Accept**: for each `sp`: if `!spBusy(sp)`, scan from `spNextDp(sp)` for first `dp` where `grant(dp)==sp`. Advance `spNextDp(sp)`.
3. **Transmit**: for each `(sp,dp)` accepted: dequeue, emit. Update busy flags and serving pointers.

II = 4 (matches HLS).

**`EDRRMScheduler`** (NxNVOQ)  
Single-iteration two-phase, exhaustive service:
1. **Request**: for each `sp`, scan from `rrIn(sp)` for first `dp` with non-empty VOQ. Record `req(sp) = dp`.
2. **Grant**: invert `req` to get `requesters(dp)`. For each `dp`, scan from `rrOut(dp)` among requesters. Grant first hit → match `(sp, dp)`. Advance `rrOut(dp)`. If `voqDepth(sp,dp) > 1`: keep `rrIn(sp) = dp` (exhaustive, drain before moving). Else: advance `rrIn(sp)`.
3. **Transmit**: dequeue and emit matched pairs.

II = 2 target.

### 2.5 `SwitchTop.scala` (~65 lines)

```scala
class SwitchTop(p: SwitchParams) extends Module {
  val io = IO(new Bundle {
    val rx    = Vec(p.nPorts, Flipped(Decoupled(new AxisWord(p))))
    val tx    = Vec(p.nPorts, Decoupled(new AxisWord(p)))
    val reset = Input(Bool())
  })

  val rxEngines = Seq.tabulate(p.nPorts)(i => Module(new RxEngine(p, i)))
  val fwdTable  = p.hash match {
    case FullLookup    => Module(new FullLookupTable(p))
    case MultiBankHash => Module(new MultiBankHashEngine(p))
  }
  val scheduler = p.sched match {
    case RoundRobin => Module(new RRScheduler(p))
    case ISLIP      => Module(new ISLIPScheduler(p))
    case EDRRM      => Module(new EDRRMScheduler(p))
  }
  // wire: rx → rxEngines → fwdTable → scheduler → tx
  // intermediate streams buffered with Queue(depth=64)
}
```

---

## 3. Test Layer — `spac/hw/SwitchTopTest.scala` (~120 lines)

Use `chiseltest` (`chisel3.tester`).

**Test 1 — Unicast loopback** (FullLookup, iSLIP, 4-port): inject packet src=0→dst=1. Verify emerges on tx(1) within 20 cycles, not on tx(0/2/3).

**Test 2 — Broadcast**: inject with unknown dst (not yet learned). Verify packet appears on all ports except source.

**Test 3 — Head-of-line blocking**: with iSLIP, inject 4 packets all targeting port 0. Verify all delivered, no deadlock within 50 cycles.

**Test 4 — EDRRM exhaustive service**: inject burst of 4 packets src=0→dst=1. Verify `rrIn(0)` stays locked to 1 until burst drained (observe `EDRRMScheduler` internal state via peeking).

**Test 5 — Parameterisation smoke**: elaborate `SwitchTop` for `(nPorts=8, MultiBankHash, SharedVOQ, EDRRM)`. No assertion errors during elaboration.

---

## 4. DSE Layer — `spac/dse/`

### 4.1 `TraceTypes.scala` (~20 lines)

```scala
case class TraceEntry(timeNs: Double, src: Int, dst: Int, sizeBytes: Int)
case class TraceFeatures(idc: Double, hAddr: Double, sMin: Int)
case class TopologyLink(nodeA: String, portA: Int, nodeB: String, portB: Int)
```

### 4.2 `FeatureExtractor.scala` (~70 lines)

```scala
object FeatureExtractor {
  def apply(trace: Seq[TraceEntry], windowNs: Double = 1e6): TraceFeatures = {
    // IDC: bucket by window, compute Var/Mean of counts
    // H_addr: Shannon entropy of dst distribution
    // sMin: minimum packet size
  }
}
```

All O(N). No external deps.

### 4.3 `ResourceModel.scala` (~90 lines)

Polynomial evaluators, one function per (module × resource type). Coefficients are `val`s — easy to replace when new synthesis data arrives.

```scala
object ResourceModel {
  // Source: Vitis HLS synthesis sweep at n∈{2,4,8,10,12,14,16,24,32}
  // Fit: quadratic least-squares (see FitCoeffs.scala for re-fit utility)
  def schedLUT(p: SwitchParams): Int = (p.sched, p.buf) match {
    case (ISLIP, SharedVOQ)  => poly(943.75,  -655.5,  137.0,  p.nPorts)
    case (ISLIP, NxNVOQ)     => poly(744.583, -1000.0, 1512.67, p.nPorts)
    case (EDRRM, _)          => (0.75 * poly(744.583, -1000.0, 1512.67, p.nPorts)).toInt
    case (RoundRobin, _)     => poly(744.583, -1000.0, 1512.67, p.nPorts)
  }
  def hashLUT(p: SwitchParams): Int = p.hash match {
    case FullLookup    => poly(16.0,   658.0, 129.0, p.nPorts)
    case MultiBankHash => poly(716.5, -900.0, 1296.0, p.nPorts)
  }
  def bufferBRAM(p: SwitchParams, voqSizes: Option[Seq[Int]] = None): Int = { ... }
  def totalLUT(p: SwitchParams)  = schedLUT(p) + hashLUT(p) + rxLUT(p)
  def totalBRAM(p: SwitchParams) = schedBRAM(p) + hashBRAM(p) + rxBRAM(p) + bufferBRAM(p)

  private def poly(a: Double, b: Double, c: Double, n: Int) =
    (a*n*n + b*n + c).toInt.max(0)
}
```

**`FitCoeffs.scala`** (~40 lines): given `Seq[(Int,Int)]` of `(nPorts, measuredValue)` pairs, uses Breeze to solve the 3×3 least-squares system and returns `(a,b,c)`. Called once offline; results pasted into `ResourceModel`.

```scala
import breeze.linalg._
def fitQuadratic(data: Seq[(Int, Int)]): (Double, Double, Double) = {
  val X = DenseMatrix(data.map { case (n,_) => Array(n*n.toDouble, n.toDouble, 1.0) }: _*)
  val y = DenseVector(data.map(_._2.toDouble): _*)
  val c = X \ y
  (c(0), c(1), c(2))
}
```

### 4.4 `LatencyModel.scala` (~60 lines)

Tables indexed by `(SchedType, HashType, nPorts)`. Lookup, then linear interpolation for off-table port counts.

```scala
object LatencyModel {
  // From estimation.txt (embedded as literals)
  private val islipLatency  = Map(2->6, 4->7, 8->9, 16->10, 32->15)
  private val rrLatency     = Map(2->2, 4->3, 8->4, 16->5,  32->6)
  private val edrrmLatency  = Map(2->4, 4->5, 8->7, 16->9,  32->13)
  private val islipII  = 4;  private val rrII  = 1;  private val edrrmII = 2
  private val flLat    = Map(2->2, 4->3, 8->5, 16->5); private val flII  = 1
  private val mbLat    = Map(2->4, 4->4, 8->6, 16->7); private val mbII  = 3

  def pipelineLatencyCycles(p: SwitchParams): Int   = rxLat + hashLat(p) + schedLat(p)
  def initiationIntervalCycles(p: SwitchParams): Int = schedII(p).max(hashII(p))
  def canAchieveLineRate(p: SwitchParams): Boolean =
    initiationIntervalCycles(p) <= transferCycles(p)
  private def transferCycles(p: SwitchParams) = // ceil(sMin*8 / dataBits)
}
```

### 4.5 `StatSim.scala` (~350 lines)

Cycle-accurate event-driven simulator. No external dep; uses Scala `mutable.PriorityQueue`.

**Key classes:**

```scala
case class SimPacket(id: Int, srcPort: Int, dstPort: Int,
                     words: Int, arrivalCycle: Long)

class SwitchSim(p: SwitchParams, voqSizes: Map[(Int,Int), Int]) {
  // State mirrors RTL exactly:
  val voq       = Array.ofDim[mutable.Queue[SimPacket]](p.nPorts, p.nPorts)
  val txBusy    = Array.fill(p.nPorts)(false)
  val spBusy    = Array.fill(p.nPorts)(false)
  val txServing = Array.fill(p.nPorts)(0)
  val txNext    = Array.tabulate(p.nPorts)(i => i)
  // + iSLIP: spNextDp, txNextSp
  // + EDRRM: rrIn, rrOut

  def injectPacket(pkt: SimPacket): Boolean   // false if VOQ full → drop
  def step(): Seq[CompletedPacket]            // one cycle, returns any tx completions
  def runUntilDrained(maxCycles: Int): SimStats
}

case class SimStats(
  received: Int, transmitted: Int, dropped: Int,
  avgLatencyNs: Double, maxLatencyNs: Double,
  throughputGbps: Double, dropRate: Double,
  peakVOQOccupancy: Map[(Int,Int), Int]     // for phase-2 VOQ sizing
)
```

Timing model: packet arrives, waits pipeline latency (from `LatencyModel`), then contends in scheduler. Scheduler step implements exact same grant/accept logic as RTL spec above. One `step()` = one clock cycle.

### 4.6 `DSEEngine.scala` (~180 lines)

```scala
object DSEEngine {

  case class Constraints(maxLUT: Int, maxBRAM: Int, targetDropRate: Double = 0.01)

  def run(trace: Seq[TraceEntry], topo: Seq[TopologyLink],
          constraints: Constraints, nPorts: Int): DSEResult = {

    val features = FeatureExtractor(trace)

    // Phase 1: prune + simulate all arch combos with infinite VOQ
    val candidates = for {
      hash  <- Seq(FullLookup, MultiBankHash)
      buf   <- Seq(NxNVOQ, SharedVOQ)
      sched <- Seq(RoundRobin, ISLIP, EDRRM)
      width <- Seq(256, 512, 640)
    } yield SwitchParams(nPorts=nPorts, dataBits=width, hash=hash, buf=buf, sched=sched)

    // Prune 1: timing feasibility (II ≤ transfer cycles for sMin)
    val feasible = candidates.filter { p =>
      val sMin = features.sMin
      val δ = 0.1
      LatencyModel.initiationIntervalCycles(p) <= (1+δ) * sMin * 8 / p.dataBits
    }

    // Prune 2: resource budget
    val resourceOk = feasible.filter { p =>
      ResourceModel.totalLUT(p) <= constraints.maxLUT &&
      ResourceModel.totalBRAM(p) <= constraints.maxBRAM
    }

    // Simulate each with large VOQ, collect peak occupancy
    val phase1Results = resourceOk.map { p =>
      val sim   = new SwitchSim(p, defaultLargeVOQ(p))
      val stats = runTrace(sim, trace, topo)
      (p, stats)
    }

    // Phase 2: VOQ right-sizing for top candidate(s)
    val best = phase1Results.filter(_._2.dropRate == 0)
                            .sortBy(_._2.avgLatencyNs).head
    val optVOQ = rightSizeVOQ(best._1, best._2.peakVOQOccupancy)

    DSEResult(best._1, optVOQ, best._2)
  }

  private def rightSizeVOQ(p: SwitchParams,
                            peaks: Map[(Int,Int), Int]): Map[(Int,Int), Int] =
    peaks.map { case (k, v) => k -> nextPow2(v, min=64) }
}
```

### 4.7 `TopologyParser.scala` + `TraceParser.scala` (~60 + 80 lines)

CSV readers. `TopologyParser` builds port-mapping from `single_switch_8hosts.csv` format (columns: `node_a,port_a,node_b,port_b`). `TraceParser` reads `time,src_addr,dst_addr,header_size,body_size,trace_id` format (exact SPAC trace schema).

---

## 5. Protocol Layer — `spac/proto/`

Replaces the SPAC C++ DSL + net-blocks for the hardware side. Does **not** generate a runtime C driver (that's net-blocks' job and out of scope for hardware replication).

### 5.1 `ProtocolSpec.scala` (~80 lines)

```scala
sealed trait SemanticTag
case object RoutingKeySrc extends SemanticTag
case object RoutingKeyDst extends SemanticTag
case object FlowId        extends SemanticTag
case object PktLen        extends SemanticTag
case object NoTag         extends SemanticTag

case class FieldSpec(name: String, widthBits: Int, tag: SemanticTag = NoTag)

class ProtocolSpec {
  private val fields = mutable.ListBuffer[FieldSpec]()
  def addField(name: String, widthBits: Int, tag: SemanticTag = NoTag): FieldSpec = ...
  def compile(): CompiledLayout  // assign bit offsets, return layout
}

case class FieldLayout(spec: FieldSpec, bitOffset: Int)
case class CompiledLayout(fields: Seq[FieldLayout], totalBits: Int) {
  def offsetOf(tag: SemanticTag): Int  // used by SwitchParams constructor
  def widthOf(tag: SemanticTag): Int
}
```

### 5.2 `PacketHPPEmitter.scala` (~60 lines)

Takes `CompiledLayout` + `SwitchParams`, writes `packet.hpp` with `constexpr` offsets and `get_src` / `get_dst` / `get_pkt_len` inlines. Output is byte-for-byte compatible with what `demo_clean` generates.

### 5.3 `CommonHPPPatcher.scala` (~40 lines)

Reads template `common.hpp`, regex-replaces `NUM_PORTS`, `HASH_MODULE_TYPE`, `BUFFER_TYPE`, `SCHEDULER_TYPE`, `MAX_QUEUE_DEPTH_LOG`, `HASH_BITS`. Same logic as `hls_copy.cpp` but in Scala. Output feeds into Vitis HLS synthesis unchanged.

---

## 6. Line Budget Summary

| File(s) | Est. lines |
|---------|----------:|
| `Types.scala` | 40 |
| `RxEngine.scala` | 55 |
| `ForwardTable.scala` | 120 |
| `Schedulers.scala` (RR + iSLIP + EDRRM) | 400 |
| `SwitchTop.scala` | 65 |
| `SwitchTopTest.scala` | 120 |
| `TraceTypes.scala` | 20 |
| `FeatureExtractor.scala` | 70 |
| `ResourceModel.scala` + `FitCoeffs.scala` | 130 |
| `LatencyModel.scala` | 60 |
| `StatSim.scala` | 350 |
| `DSEEngine.scala` | 180 |
| `TraceParser.scala` + `TopologyParser.scala` | 140 |
| `ProtocolSpec.scala` | 80 |
| `PacketHPPEmitter.scala` + `CommonHPPPatcher.scala` | 100 |
| **Total** | **~1930** |

---

## 7. Implementation Order

1. `Types.scala` — no deps, unblocks everything
2. `RxEngine.scala` + `ForwardTable.scala` — can test in isolation
3. `Schedulers.scala` (RR first, then iSLIP, then EDRRM)
4. `SwitchTop.scala` + `SwitchTopTest.scala`
5. `TraceParser` + `FeatureExtractor` + `ResourceModel` + `LatencyModel`
6. `StatSim.scala` (RR first; iSLIP and EDRRM drop in)
7. `DSEEngine.scala`
8. Protocol layer (can be last; only needed for HLS output)

Each step is independently testable. Steps 1–4 produce synthesisable Chisel. Steps 5–7 reproduce the paper's DSE results. Step 8 closes the loop to HLS output.
