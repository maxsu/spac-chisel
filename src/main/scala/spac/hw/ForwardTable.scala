package spac.hw

import chisel3._
import chisel3.util._

// ── Common IO ─────────────────────────────────────────────────────────────

class FwdTableIO(p: SwitchParams) extends Bundle {
  val metaIn  = Vec(p.nPorts, Flipped(Decoupled(new Metadata(p))))
  val metaOut = Vec(p.nPorts, Decoupled(new Metadata(p)))
}

// ── FullLookupTable ───────────────────────────────────────────────────────

class FullLookupTable(p: SwitchParams) extends Module with HasFwdTableIO {
  val io = IO(new FwdTableIO(p))

  val tableEntryBits = 1 + p.portBits
  val tableSize      = 1 << p.addrBits

  val fwd = RegInit(VecInit(Seq.fill(tableSize)(0.U(tableEntryBits.W))))

  // Snapshot at start of cycle
  val fwdSnap = Wire(Vec(tableSize, UInt(tableEntryBits.W)))
  fwdSnap := fwd

  val writeEn  = Wire(Vec(tableSize, Bool()))
  val writeVal = Wire(Vec(tableSize, UInt(tableEntryBits.W)))
  for (i <- 0 until tableSize) { writeEn(i) := false.B; writeVal(i) := 0.U }

  for (sp <- 0 until p.nPorts) {
    io.metaOut(sp).valid := false.B
    io.metaOut(sp).bits  := io.metaIn(sp).bits
    io.metaIn(sp).ready  := io.metaOut(sp).ready

    when(io.metaIn(sp).valid && io.metaOut(sp).ready) {
      val meta   = io.metaIn(sp).bits
      val srcIdx = meta.srcAddr
      val dstIdx = meta.dstAddr

      writeEn(srcIdx)  := true.B
      writeVal(srcIdx) := Cat(1.U(1.W), sp.U(p.portBits.W))

      val entry  = fwdSnap(dstIdx)
      val hit    = entry(tableEntryBits - 1)

      val outMeta = Wire(new Metadata(p))
      outMeta           := meta
      outMeta.dstPort   := Mux(hit, entry(p.portBits - 1, 0), 0.U)
      outMeta.broadcast := !hit

      io.metaOut(sp).valid := true.B
      io.metaOut(sp).bits  := outMeta
      io.metaIn(sp).ready  := true.B
    }
  }

  for (i <- 0 until tableSize) {
    when(writeEn(i)) { fwd(i) := writeVal(i) }
  }
}

// ── MultiBankHashEngine ────────────────────────────────────────────────────

class MultiBankHashEngine(p: SwitchParams) extends Module with HasFwdTableIO {
  val io = IO(new FwdTableIO(p))

  val BANKS      = p.nPorts
  val bankBits   = p.portBits
  val entryBits  = 1 + p.addrBits + p.portBits

  val mem = Seq.fill(BANKS)(SyncReadMem(1 << p.hashBits, UInt(entryBits.W)))

  val buf      = Reg(Vec(p.nPorts, new Metadata(p)))
  val saved    = RegInit(VecInit(Seq.fill(p.nPorts)(true.B)))
  val readDone = RegInit(VecInit(Seq.fill(p.nPorts)(true.B)))

  val savePtr = RegInit(VecInit(Seq.fill(BANKS)(0.U(p.portBits.W))))
  val readPtr = RegInit(VecInit(Seq.fill(BANKS)(0.U(p.portBits.W))))

  for (sp <- 0 until p.nPorts) {
    io.metaIn(sp).ready := saved(sp) && readDone(sp)
    when(io.metaIn(sp).valid && io.metaIn(sp).ready) {
      buf(sp)      := io.metaIn(sp).bits
      saved(sp)    := false.B
      readDone(sp) := false.B
    }
  }

  for (sp <- 0 until p.nPorts) {
    io.metaOut(sp).valid := false.B
    io.metaOut(sp).bits  := buf(sp)
  }

  for (b <- 0 until BANKS) {
    // ---- write (src learn): priority mux from savePtr ----
    val saveGrant   = Wire(UInt(p.portBits.W)); saveGrant := 0.U
    val saveGrantEn = Wire(Bool());             saveGrantEn := false.B
    for (step <- (p.nPorts - 1) to 0 by -1) {
      for (sp <- 0 until p.nPorts) {
        when((savePtr(b) +& step.U)(p.portBits-1,0) === sp.U &&
             !saved(sp) &&
             buf(sp).srcAddr(bankBits-1,0) === b.U) {
          saveGrant   := sp.U
          saveGrantEn := true.B
        }
      }
    }
    when(saveGrantEn) {
      val srcKey = MuxLookup(saveGrant, 0.U,
        (0 until p.nPorts).map(i => i.U -> buf(i).srcAddr(p.hashBits-1,0)))
      val srcAddr = MuxLookup(saveGrant, 0.U,
        (0 until p.nPorts).map(i => i.U -> buf(i).srcAddr))
      mem(b).write(srcKey, Cat(1.U(1.W), srcAddr, saveGrant(p.portBits-1,0)))
      for (sp <- 0 until p.nPorts) {
        when(saveGrant === sp.U) { saved(sp) := true.B }
      }
      savePtr(b) := (saveGrant +& 1.U)(p.portBits-1,0)
    }

    // ---- read (dst lookup): issue read this cycle, commit next cycle ----
    val readGrant   = Wire(UInt(p.portBits.W)); readGrant := 0.U
    val readGrantEn = Wire(Bool());             readGrantEn := false.B
    for (step <- (p.nPorts - 1) to 0 by -1) {
      for (sp <- 0 until p.nPorts) {
        when((readPtr(b) +& step.U)(p.portBits-1,0) === sp.U &&
             !readDone(sp) &&
             buf(sp).dstAddr(bankBits-1,0) === b.U) {
          readGrant   := sp.U
          readGrantEn := true.B
        }
      }
    }
    val dstKey = MuxLookup(readGrant, 0.U,
      (0 until p.nPorts).map(i => i.U -> buf(i).dstAddr(p.hashBits-1,0)))
    val memOut         = mem(b).read(dstKey)
    val readGrantReg   = RegNext(readGrant,   0.U)
    val readGrantEnReg = RegNext(readGrantEn, false.B)

    when(readGrantEnReg) {
      val entry      = memOut
      val hit        = entry(entryBits-1)
      val storedAddr = entry(entryBits-2, p.portBits)
      val storedPort = entry(p.portBits-1, 0)
      for (sp <- 0 until p.nPorts) {
        when(readGrantReg === sp.U) {
          val realHit = hit && (storedAddr === buf(sp).dstAddr)
          val outMeta = Wire(new Metadata(p))
          outMeta           := buf(sp)
          outMeta.dstPort   := Mux(realHit, storedPort, 0.U)
          outMeta.broadcast := !realHit
          io.metaOut(sp).valid := true.B
          io.metaOut(sp).bits  := outMeta
          readDone(sp)         := true.B
        }
      }
      readPtr(b) := (readGrantReg +& 1.U)(p.portBits-1,0)
    }
  }
}
