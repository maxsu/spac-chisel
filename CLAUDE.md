# AGENT.md — SPAC-Chisel

Chisel 7 hardware description of the SPAC network switch (arXiv 2604.21881v1).

## Boot

Run once at the start of each session:

```bash
bash scripts/claude-boot.sh
```

This installs scala-cli and Verilator. 
Note: $HOME/.local/bin is already on your path

## Build & Test

```bash
# Emit Verilog
scala-cli compile .

# Run all tests
scala-cli test .

# Run a specific suite
scala-cli test . --test-only spac.hw.RxEngineTest

# Run tests matching a name pattern
scala-cli test . --test-only spac.hw.SwitchTopTest -- -z iSLIP
```

## Known Issues

### Legacy test runner warning

Running tests produces:

```
[warn] Scala 2.13.18 is no longer supported by the test-runner module.
[warn] Defaulting to a legacy test-runner module version: 1.9.1.
[warn] To use the latest test-runner, upgrade Scala to at least 3.3.
```

This is because Chisel 7 currently targets Scala 2.13. This warning may be ignored.

### JVM SSL/PKIX errors during scala-cli compile or test

If you see `PKIX path building failed` when scala-cli tries to resolve dependencies,
the JVM's truststore (separate from the OS one) doesn't trust this environment's
egress proxy CA. `claude-boot.sh` already imports it — if the error persists, check
whether `/usr/local/share/ca-certificates/*.crt` actually contains the proxy's cert.

## Project Layout

```
AGENT.md              # Agent quick start
README.md             # Full project readme
SPAC_chisel_spec.md   # V1-Spec
project.scala         # scala-cli project deps
scripts/
  claude-boot.sh      # Session setup
src/
  Types.scala         # SwitchParams, AXI bundles, enums
  RxEngine.scala      # Per-port 2-state parser FSM
  ForwardTable.scala  # FullLookupTable, MultiBankHash
  Schedulers.scala    # RoundRobin, ISLIPScheduler, EDRRMScheduler
  SwitchTop.scala     # Top-level composition
  *Test.scala         # ChiselSim suites
generated/            # Emitted SystemVerilog
```

## Elaborating SystemVerilog

```scala
import spac.hw._
import _root_.circt.stage.ChiselStage

ChiselStage.emitSystemVerilogFile(
  gen  = new SwitchTop(SwitchParams(nPorts=8, hash=MultiBankHash, sched=EDRRM)),
  args = Array("--target-dir", "generated"),
)
```

## Architecture

1. RxEngines (xN) parse raw AXI-Stream into payload + header.
2. Header flows through a parser → forwarding table (FullLookup II=1 or MultiBankHash II≈3) to produce
a destination.
3. An ISLIP/EDRRM/RR scheduler arbitrates between input queues and drives
the TX ports.

## Tech Stack

- Scala 2.13.18 →  Chisel 7.13.0 → CIRCT/firtool
- Testing via ChiselSim → svsim → Verilator

## Milestones

- **M1 (done):** Hardware core — RxEngine, ForwardTable, Schedulers, SwitchTop, 12 tests
- **M2:** DSE layer — StatSim, DSEEngine, FeatureExtractor
- **M3:** Protocol layer — ProtocolSpec, PacketHPPEmitter