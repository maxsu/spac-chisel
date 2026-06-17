# AGENT.md — SPAC-Chisel

Chisel 7 hardware description of the SPAC network switch (arXiv 2604.21881v1).

## Deps

```bash
curl -fL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d > scala-cli
chmod +x scala-cli
mv scala-cli /home/$USER/.local/bin/scala-cli
```

## Build & Test

```bash
# Compile
scala-cli build .

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

### Note for Claude (AI assistant) running in a sandboxed environment

scala-cli downloads dependencies via Coursier, which uses the JVM's truststore. In sandboxed environments the JVM truststore does not include the host's CA certificates, causing SSL handshake failures. Fix by importing system certs:

```bash
# Find your Java cacerts (path may vary)
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts | head -1)

# Import all system certs
for cert in /etc/ssl/certs/*.crt; do
  alias=$(basename "$cert" .crt | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | cut -c1-30)
  keytool -importcert -noprompt -trustcacerts \
    -keystore "$JAVA_CACERTS" -storepass changeit \
    -alias "$alias" -file "$cert" 2>/dev/null
done

# Then run tests with the truststore explicitly set
JAVA_OPTS="-Djavax.net.ssl.trustStore=$JAVA_CACERTS -Djavax.net.ssl.trustStorePassword=changeit" \
  scala-cli test . --server=false
```

Also pass `--server=false` to skip Bloop (which requires downloading via SSL before any project deps are fetched).


## Project Layout

```
project.scala         # scala-cli deps
SPAC_chisel_spec.md   # V1-Spec
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
- ChiselSim (svsim) + ScalaTest for tests
- Verilator required for simulation backend

## Milestones

- **M1 (done):** Hardware core — RxEngine, ForwardTable, Schedulers, SwitchTop, 12 tests
- **M2:** DSE layer — StatSim, DSEEngine, FeatureExtractor
- **M3:** Protocol layer — ProtocolSpec, PacketHPPEmitter