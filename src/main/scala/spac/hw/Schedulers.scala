package spac.hw

import chisel3._
import chisel3.util._

// ── Shared scheduler IO ───────────────────────────────────────────────────

class SchedulerIO(p: SwitchParams) extends Bundle {
  val dataIn  = Vec(p.nPorts, Flipped(Decoupled(new AxisWord(p))))
  val metaIn  = Vec(p.nPorts, Flipped(Decoupled(new Metadata(p))))
  val dataOut = Vec(p.nPorts, Decoupled(new AxisWord(p)))
}

// ── NxNVOQ storage ────────────────────────────────────────────────────────

class NxNStorage(p: SwitchParams) extends Module {
  val N = p.nPorts; val D = p.qDepth
  val io = IO(new Bundle {
    val enqData = Input(Vec(N, new AxisWord(p)))
    val enqMask = Input(Vec(N, UInt(N.W)))
    val enqEn   = Input(Vec(N, Bool()))
    val deqSp   = Input(Vec(N, UInt(p.portBits.W)))
    val deqEn   = Input(Vec(N, Bool()))
    val deqData = Output(Vec(N, new AxisWord(p)))
    val deqOk   = Output(Vec(N, Bool()))
    val empty   = Output(Vec(N, Vec(N, Bool())))
    val full    = Output(Vec(N, Vec(N, Bool())))
  })

  val mem  = Reg(Vec(N, Vec(N, Vec(D, new AxisWord(p)))))
  val head = RegInit(VecInit.fill(N, N)(0.U(p.qDepthLog.W)))
  val tail = RegInit(VecInit.fill(N, N)(0.U(p.qDepthLog.W)))

  for (sp <- 0 until N; dp <- 0 until N) {
    io.empty(sp)(dp) := head(sp)(dp) === tail(sp)(dp)
    val tailNext = (tail(sp)(dp) +& 1.U)(p.qDepthLog-1,0)
    io.full(sp)(dp)  := tailNext === head(sp)(dp)
  }

  for (sp <- 0 until N) {
    when(io.enqEn(sp)) {
      for (dp <- 0 until N) {
        when(io.enqMask(sp)(dp) && !io.full(sp)(dp)) {
          mem(sp)(dp)(tail(sp)(dp)) := io.enqData(sp)
          tail(sp)(dp) := (tail(sp)(dp) +& 1.U)(p.qDepthLog-1,0)
        }
      }
    }
  }

  for (dp <- 0 until N) {
    // Use MuxLookup so deqSp (UInt) can index the register array
    io.deqData(dp) := MuxLookup(io.deqSp(dp), mem(0)(dp)(0),
      (0 until N).map(sp => sp.U -> mem(sp)(dp)(head(sp)(dp))))
    io.deqOk(dp)   := MuxLookup(io.deqSp(dp), false.B,
      (0 until N).map(sp => sp.U -> !io.empty(sp)(dp)))

    when(io.deqEn(dp) && io.deqOk(dp)) {
      for (sp <- 0 until N) {
        when(io.deqSp(dp) === sp.U) {
          head(sp)(dp) := (head(sp)(dp) +& 1.U)(p.qDepthLog-1,0)
        }
      }
    }
  }
}

// ── Shared digest FSM ─────────────────────────────────────────────────────

class DigestFSM(p: SwitchParams, sp: Int) extends Module {
  val io = IO(new Bundle {
    val dataIn  = Flipped(Decoupled(new AxisWord(p)))
    val metaIn  = Flipped(Decoupled(new Metadata(p)))
    val word    = Output(new AxisWord(p))
    val mask    = Output(UInt(p.nPorts.W))
    val enq     = Output(Bool())
    val anyFull = Input(Bool())
  })

  val sIdle :: sUnicast :: sBroadcast :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val dpReg = Reg(UInt(p.portBits.W))
  val bcMask = (~(1.U(p.nPorts.W) << sp.U))(p.nPorts-1,0)

  io.word := io.dataIn.bits
  io.mask := 0.U
  io.enq  := false.B
  io.dataIn.ready := false.B
  io.metaIn.ready := false.B

  switch(state) {
    is(sIdle) {
      when(io.metaIn.valid && io.dataIn.valid && !io.anyFull) {
        io.metaIn.ready := true.B
        io.dataIn.ready := true.B
        io.enq          := true.B
        val bc = io.metaIn.bits.broadcast
        io.mask := Mux(bc, bcMask, (1.U(p.nPorts.W) << io.metaIn.bits.dstPort)(p.nPorts-1,0))
        dpReg   := io.metaIn.bits.dstPort
        state   := Mux(io.dataIn.bits.last, sIdle, Mux(bc, sBroadcast, sUnicast))
      }
    }
    is(sUnicast) {
      when(io.dataIn.valid && !io.anyFull) {
        io.dataIn.ready := true.B
        io.enq          := true.B
        io.mask         := (1.U(p.nPorts.W) << dpReg)(p.nPorts-1,0)
        when(io.dataIn.bits.last) { state := sIdle }
      }
    }
    is(sBroadcast) {
      when(io.dataIn.valid && !io.anyFull) {
        io.dataIn.ready := true.B
        io.enq          := true.B
        io.mask         := bcMask
        when(io.dataIn.bits.last) { state := sIdle }
      }
    }
  }
}

// ── RoundRobinScheduler ───────────────────────────────────────────────────

class RRScheduler(p: SwitchParams) extends Module with HasSchedulerIO {
  val io = IO(new SchedulerIO(p))
  val N  = p.nPorts

  val storage = Module(new NxNStorage(p))
  val digest  = Seq.tabulate(N)(sp => Module(new DigestFSM(p, sp)))

  for (sp <- 0 until N) {
    digest(sp).io.dataIn <> io.dataIn(sp)
    digest(sp).io.metaIn <> io.metaIn(sp)
    digest(sp).io.anyFull :=
      (0 until N).map(dp => storage.io.full(sp)(dp)).reduce(_ || _)
    storage.io.enqData(sp) := digest(sp).io.word
    storage.io.enqMask(sp) := digest(sp).io.mask
    storage.io.enqEn(sp)   := digest(sp).io.enq
  }

  val txNext    = RegInit(VecInit.tabulate(N)(i => i.U(p.portBits.W)))
  val txBusy    = RegInit(VecInit.fill(N)(false.B))
  val txServing = Reg(Vec(N, UInt(p.portBits.W)))
  val spBusy    = RegInit(VecInit.fill(N)(false.B))

  for (dp <- 0 until N) {
    storage.io.deqSp(dp) := 0.U
    storage.io.deqEn(dp) := false.B
    io.dataOut(dp).valid := false.B
    io.dataOut(dp).bits  := storage.io.deqData(dp)
  }

  for (dp <- 0 until N) {
    val sp     = Mux(txBusy(dp), txServing(dp), txNext(dp))
    val spBusySp = MuxLookup(sp, false.B, (0 until N).map(i => i.U -> spBusy(i)))
    val emptySpDp = MuxLookup(sp, true.B, (0 until N).map(i => i.U -> storage.io.empty(i)(dp)))
    val canSend = (txBusy(dp) || !spBusySp) && !emptySpDp

    storage.io.deqSp(dp) := sp

    // Always rotate txNext (only when not busy, so we scan for next available src)
    when(!txBusy(dp)) {
      txNext(dp) := Mux(txNext(dp) === (N-1).U, 0.U, txNext(dp) + 1.U)
    }

    when(canSend && io.dataOut(dp).ready) {
      storage.io.deqEn(dp) := true.B
      io.dataOut(dp).valid := true.B
      val word = storage.io.deqData(dp)
      when(word.last) {
        txBusy(dp) := false.B
        for (i <- 0 until N) { when(sp === i.U) { spBusy(i) := false.B } }
      }.otherwise {
        txBusy(dp)    := true.B
        txServing(dp) := sp
        for (i <- 0 until N) { when(sp === i.U) { spBusy(i) := true.B } }
      }
    }
  }
}

// ── ISLIPScheduler ────────────────────────────────────────────────────────

class ISLIPScheduler(p: SwitchParams) extends Module with HasSchedulerIO {
  val io = IO(new SchedulerIO(p))
  val N  = p.nPorts

  val storage = Module(new NxNStorage(p))
  val digest  = Seq.tabulate(N)(sp => Module(new DigestFSM(p, sp)))

  for (sp <- 0 until N) {
    digest(sp).io.dataIn <> io.dataIn(sp)
    digest(sp).io.metaIn <> io.metaIn(sp)
    digest(sp).io.anyFull :=
      (0 until N).map(dp => storage.io.full(sp)(dp)).reduce(_ || _)
    storage.io.enqData(sp) := digest(sp).io.word
    storage.io.enqMask(sp) := digest(sp).io.mask
    storage.io.enqEn(sp)   := digest(sp).io.enq
  }

  val txNextSp  = RegInit(VecInit.fill(N)(0.U(p.portBits.W)))
  val spNextDp  = RegInit(VecInit.fill(N)(0.U(p.portBits.W)))
  val txBusy    = RegInit(VecInit.fill(N)(false.B))
  val txServing = Reg(Vec(N, UInt(p.portBits.W)))
  val spBusy    = RegInit(VecInit.fill(N)(false.B))

  // ── Grant: for each dp, pick a sp ────────────────────────────────────
  val grant = Wire(Vec(N, UInt((p.portBits+1).W)))   // N = no grant
  for (dp <- 0 until N) {
    grant(dp) := N.U
    when(txBusy(dp)) {
      val sp = txServing(dp)
      for (i <- 0 until N) {
        when(sp === i.U && !storage.io.empty(i)(dp)) { grant(dp) := i.U }
      }
    }.otherwise {
      for (step <- (N-1) to 0 by -1) {
        for (sp <- 0 until N) {
          when((txNextSp(dp) +& step.U)(p.portBits-1,0) === sp.U &&
               !spBusy(sp) &&
               !storage.io.empty(sp)(dp)) {
            grant(dp) := sp.U
          }
        }
      }
      txNextSp(dp) := (txNextSp(dp) +& 1.U)(p.portBits-1,0)
    }
  }

  // ── Accept: for each sp, pick one granted dp ─────────────────────────
  val accept = Wire(Vec(N, UInt((p.portBits+1).W)))
  for (sp <- 0 until N) {
    accept(sp) := N.U
    when(!spBusy(sp)) {
      for (step <- (N-1) to 0 by -1) {
        for (dp <- 0 until N) {
          when((spNextDp(sp) +& step.U)(p.portBits-1,0) === dp.U &&
               grant(dp) === sp.U) {
            accept(sp) := dp.U
          }
        }
      }
      spNextDp(sp) := (spNextDp(sp) +& 1.U)(p.portBits-1,0)
    }
  }

  // ── Transmit ─────────────────────────────────────────────────────────
  for (dp <- 0 until N) {
    storage.io.deqSp(dp) := 0.U
    storage.io.deqEn(dp) := false.B
    io.dataOut(dp).valid := false.B
    io.dataOut(dp).bits  := storage.io.deqData(dp)
  }

  for (sp <- 0 until N) {
    val dp = accept(sp)
    when(dp =/= N.U) {
      for (d <- 0 until N) {
        when(dp === d.U && io.dataOut(d).ready) {
          storage.io.deqSp(d) := sp.U
          storage.io.deqEn(d) := true.B
          io.dataOut(d).valid := true.B
          val word = storage.io.deqData(d)
          when(word.last) {
            txBusy(d)  := false.B
            spBusy(sp) := false.B
          }.otherwise {
            txBusy(d)     := true.B
            txServing(d)  := sp.U
            spBusy(sp)    := true.B
          }
        }
      }
    }
  }
}

// ── EDRRMScheduler ────────────────────────────────────────────────────────

class EDRRMScheduler(p: SwitchParams) extends Module with HasSchedulerIO {
  val io = IO(new SchedulerIO(p))
  val N  = p.nPorts

  val storage = Module(new NxNStorage(p))
  val digest  = Seq.tabulate(N)(sp => Module(new DigestFSM(p, sp)))

  for (sp <- 0 until N) {
    digest(sp).io.dataIn <> io.dataIn(sp)
    digest(sp).io.metaIn <> io.metaIn(sp)
    digest(sp).io.anyFull :=
      (0 until N).map(dp => storage.io.full(sp)(dp)).reduce(_ || _)
    storage.io.enqData(sp) := digest(sp).io.word
    storage.io.enqMask(sp) := digest(sp).io.mask
    storage.io.enqEn(sp)   := digest(sp).io.enq
  }

  val rrIn     = RegInit(VecInit.fill(N)(0.U(p.portBits.W)))
  val rrOut    = RegInit(VecInit.fill(N)(0.U(p.portBits.W)))
  val voqCount = RegInit(VecInit.fill(N)(VecInit.fill(N)(0.U((p.qDepthLog+1).W))))

  // ── Request ──────────────────────────────────────────────────────────
  val req = Wire(Vec(N, UInt((p.portBits+1).W)))
  for (sp <- 0 until N) {
    req(sp) := N.U
    for (step <- (N-1) to 0 by -1) {
      for (dp <- 0 until N) {
        when((rrIn(sp) +& step.U)(p.portBits-1,0) === dp.U &&
             !storage.io.empty(sp)(dp)) {
          req(sp) := dp.U
        }
      }
    }
  }

  // ── Grant ─────────────────────────────────────────────────────────────
  val grantEn  = Wire(Vec(N, Bool()))
  val grantSp  = Wire(Vec(N, UInt(p.portBits.W)))
  for (dp <- 0 until N) {
    grantEn(dp) := false.B
    grantSp(dp) := 0.U
    for (step <- (N-1) to 0 by -1) {
      for (sp <- 0 until N) {
        when((rrOut(dp) +& step.U)(p.portBits-1,0) === sp.U &&
             req(sp) === dp.U) {
          grantEn(dp) := true.B
          grantSp(dp) := sp.U
        }
      }
    }
  }

  // ── Transmit + pointer update ─────────────────────────────────────────
  for (dp <- 0 until N) {
    storage.io.deqSp(dp) := 0.U
    storage.io.deqEn(dp) := false.B
    io.dataOut(dp).valid := false.B
    io.dataOut(dp).bits  := storage.io.deqData(dp)
  }

  for (dp <- 0 until N) {
    when(grantEn(dp) && io.dataOut(dp).ready) {
      val sp = grantSp(dp)
      storage.io.deqSp(dp) := sp
      storage.io.deqEn(dp) := true.B
      io.dataOut(dp).valid := true.B

      rrOut(dp) := (sp +& 1.U)(p.portBits-1,0)

      // Exhaustive: lock rrIn if VOQ still has packets after dequeue
      for (s <- 0 until N) {
        when(sp === s.U) {
          when(voqCount(s)(dp) > 1.U) {
            rrIn(s) := dp.U
          }.otherwise {
            rrIn(s) := ((dp + 1) % p.nPorts).U
          }
        }
      }
    }
  }

  // voqCount maintenance
  for (sp <- 0 until N; dp <- 0 until N) {
    val enqHere = digest(sp).io.enq && digest(sp).io.mask(dp)
    val deqHere = storage.io.deqEn(dp) && (storage.io.deqSp(dp) === sp.U)
    when(enqHere && !deqHere) { voqCount(sp)(dp) := voqCount(sp)(dp) + 1.U }
    when(!enqHere && deqHere) { voqCount(sp)(dp) := voqCount(sp)(dp) - 1.U }
  }
}
