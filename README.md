# spac-chisel

Chisel/Scala replication of the SPAC network switch paper (arXiv 2604.21881v1).  
Replaces the HLS switch core with a single-language, vendor-neutral hardware description
and adds EDRRM — the scheduler the paper describes but never implemented in RTL.

## Status: Milestone 1 — Hardware Core Complete

| Module | Status | Notes |
|--------|--------|-------|
| `Types.scala` — params, bundles | ✅ | `SwitchParams` case class; all axes selectable |
| `RxEngine.scala` — per-port parser FSM | ✅ | 2-state, II=1, back-pressures correctly |
| `ForwardTable.scala` — FullLookup + MultiBankHash | ✅ | FullLookup II=1; MultiBankHash II≈3 |
| `Schedulers.scala` — RR, iSLIP, EDRRM | ✅ | All three working; EDRRM is new RTL |
| `SwitchTop.scala` — dataflow composition | ✅ | 11 Verilog modules emitted |
| **Tests** — 13 passing across all components | ✅ | RxEngine, ForwardTable, SwitchTop×6 |
| DSE layer (StatSim, DSEEngine, FeatureExtractor) | 🔜 Milestone 2 | |
| Protocol layer (ProtocolSpec, PacketHPPEmitter) | 🔜 Milestone 3 | |

## Install Chisel
### Arch
```bash
sudo pacman -S jdk-openjdk sbt git
```
### Debian / Ubuntu
```bash
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" \
  | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
   | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/sbt.gpg
sudo apt update
sudo apt install -y openjdk-21-jdk git sbt
```


# Build & Test

```bash
# Requires JDK 11+; sbt fetches everything else (~2 min first run)
sbt test          # 13 tests, all pass
sbt "runMain spac.hw.SwitchTop"   # emit Verilog → generated/SwitchTop.v
```

## Generated Verilog

Default config: 4-port, FullLookup, NxNVOQ, iSLIP, 512-bit bus.

```
generated/SwitchTop.v   — 15793 lines, 11 modules
  RxEngine (×4)         — header parse FSM
  FullLookupTable       — direct-mapped forwarding table  
  ISLIPScheduler        — grant/accept with rotating pointers
  NxNStorage            — N×N VOQ buffer
  DigestFSM (×4)        — per-port ingress FSM
  Queue (×12)           — pipeline buffers between stages
  SwitchTop             — top-level composition
```

To elaborate other configurations:
```scala
// 8-port MultiBankHash + EDRRM
val p = SwitchParams(nPorts=8, hash=MultiBankHash, sched=EDRRM)
(new ChiselStage).emitVerilog(new SwitchTop(p), Array("--target-dir","generated"))
```

## Architecture

```
rx[0..N-1]
    │
    ▼
RxEngine × N          parse header word → emit Metadata sidecar
    │                       │
 Queue(64)             Queue(64)
    │                       │
    │               ForwardTable           FullLookup II=1
    │               (learn src,            MultiBankHash II=3
    │               lookup dst)
    │                       │
    │               Queue(64)
    │                       │
    └───────────────────────┤
                            ▼
                      Scheduler             RoundRobin / iSLIP / EDRRM
                            │
                      tx[0..N-1]
```

## Key differences from SPAC HLS

| SPAC HLS | This repo |
|----------|-----------|
| `constexpr` patching via C++ codegen DSL | `SwitchParams` case class — no codegen needed |
| EDRRM: Python-only, no synthesisable RTL | `EDRRMScheduler` — full Chisel, passing tests |
| `SPAC::Auto` — broken stub | DSE layer coming in Milestone 2 |
| HLS testbench — 402 lines, all commented out | 13 working ChiselSim tests |
| Vitis HLS + Alveo U45N required | Standard Verilog from `sbt runMain` |

## Bugs fixed during implementation

| Bug | Location | Fix |
|-----|----------|-----|
| `txNext` only advanced on successful send — never rotated when idle | `RRScheduler` | Rotate unconditionally while not busy |
| EDRRM `grantSp` never enabled output — same idle-rotation issue | `EDRRMScheduler` | Structural; same root cause |
| EDRRM bit-width: `(dp + 1) % N` used Int arithmetic on UInt register | `EDRRMScheduler` | `((dp + 1) % p.nPorts).U` |

## Spec reference

See `SPAC_chisel_spec.md` (in companion audit) for the full implementation specification.
