# AGENT.md — SPAC-Chisel

Chisel 7 hardware description of the SPAC network switch (arXiv 2604.21881v1).

## Boot

Run once at the start of each session:

```bash
bash scripts/claude-boot.sh
```

This installs scala-cli and Verilator
scaly is our wrapped version of scala-cli with patched cacerts

## Build & Test

```bash
# Emit Verilog
scaly compile .

# Run all tests
scaly test .

# Run a specific suite
scaly test . --test-only spac.hw.RxEngineTest

# Run tests matching a name pattern
scaly test . --test-only spac.hw.SwitchTopTest -- -z iSLIP
```

## Architecture

1. src/RxEngine.scala: (xN) parse raw AXI-Stream into payload + header.
2. src/FowardTable.scala: Header flows through a parser → forwarding table (FullLookup II=1 or MultiBankHash II≈3) to produce
a destination.
3. src/Schedulers.scala: An ISLIP/EDRRM/RR scheduler arbitrates between input queues and drives
the TX ports.

## Tech Stack

- Scala 2.13.18 →  Chisel 7.13.0 → CIRCT/firtool
- Testing via ChiselSim → svsim → Verilator

## Milestones

- **M1 (done):** Hardware core — RxEngine, ForwardTable, Schedulers, SwitchTop, 12 tests
- **M2:** DSE layer — StatSim, DSEEngine, FeatureExtractor
- **M3:** Protocol layer — ProtocolSpec, PacketHPPEmitter