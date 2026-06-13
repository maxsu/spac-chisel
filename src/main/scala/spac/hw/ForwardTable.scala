package spac.hw

import chisel3._
import chisel3.util._

class FwdTableIO(p: SwitchParams) extends Bundle {
  val metaIn  = Vec(
    p.nPorts,
    Flipped(Decoupled(new Metadata(p)))
  )
  val metaOut = Vec(
    p.nPorts,
    Decoupled(new Metadata(p))
  )
}

class FullLookupTable(p: SwitchParams) extends Module with HasFwdTableIO {
  val io = IO(new FwdTableIO(p))
 
  val tableEntryBits = 1 + p.portBits  // each entry is a `valid` bit (MSB) & a port id
  val tableSize      = 1 << p.addrBits // table is 2^addrBits deep

  // persistent forwarding table 
  val table = RegInit(VecInit(
    Seq.fill(tableSize)(0.U(tableEntryBits.W)) // reset to 0 (all invalid)
  ))

  // write port per slot => default idle
  val writeEn  = Wire(Vec(tableSize, Bool()))
  val writeVal = Wire(Vec(tableSize, UInt(tableEntryBits.W)))
  for (i <- 0 until tableSize) { 
    writeEn(i) := false.B
    writeVal(i) := 0.U
  }

  // read-before-write snapshot
  val snapshot = Wire(Vec(tableSize, UInt(tableEntryBits.W)))
  snapshot := table

  for (sp <- 0 until p.nPorts) {
    // defaults: no output, bits pass through, ready = downstream ready
    io.metaOut(sp).valid := false.B
    io.metaOut(sp).bits  := io.metaIn(sp).bits
    io.metaIn(sp).ready  := io.metaOut(sp).ready

    // fire only on valid/ready handshake
    when(io.metaIn(sp).valid && io.metaOut(sp).ready) {
      val meta   = io.metaIn(sp).bits
      val srcIdx = meta.srcAddr
      val dstIdx = meta.dstAddr

      // learn: src reachable via this port
      writeEn(srcIdx)  := true.B
      writeVal(srcIdx) := Cat(1.U(1.W), sp.U(p.portBits.W))

      // lookup dst against snapshot
      val entry  = snapshot(dstIdx)
      val hit    = entry(tableEntryBits - 1)

      // hit -> unicast to stored port; miss -> flood
      val outMeta = Wire(new Metadata(p))
      outMeta           := meta
      outMeta.dstPort   := Mux(hit, entry(p.portBits - 1, 0), 0.U)
      outMeta.broadcast := !hit

      io.metaOut(sp).valid := true.B
      io.metaOut(sp).bits  := outMeta
      io.metaIn(sp).ready  := true.B
    }
  }

  // commit learns into the register table
  for (i <- 0 until tableSize) {
    when(writeEn(i)) { table(i) := writeVal(i) }
  }
}

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
    // write (src learn): priority mux from savePtr
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

    // read (dst lookup): issue read this cycle, commit next cycle
    val readGrant   = Wire(UInt(p.portBits.W))
    readGrant := 0.U
    val readGrantEn = Wire(Bool())
    readGrantEn := false.B
    for (step <- (p.nPorts - 1) to 0 by -1) {
      for (sp <- 0 until p.nPorts) {
        when((readPtr(b) +& step.U)(p.portBits-1,0) === sp.U &&
             !readDone(sp) &&
             buf(sp).dstAddr(bankBits-1,0) === b.U) {
          readGrant := sp.U
          readGrantEn := true.B
        }
      }
    }
    val dstKey = MuxLookup(readGrant, 0.U,
      (0 until p.nPorts).map(i => i.U -> buf(i).dstAddr(p.hashBits-1,0)))
    val memOut         = mem(b).read(dstKey)
    val readGrantReg   = RegNext(readGrant, 0.U)
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
