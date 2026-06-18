#!/usr/bin/env bash
set -euo pipefail

CERTS=/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts
ln -sf /etc/ssl/certs/java/cacerts $CERTS

apt-get update -qq
apt-get install -qq verilator
verilator --version

mkdir -p "/root/.local/bin"
cd "/root/.local/bin"
curl -fsSL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d >scala-cli.real
chmod +x scala-cli.real
cat >scala-cli <<WRAPPER
#!/bin/sh
export JAVA_OPTS="-Djavax.net.ssl.trustStore=$CERTS -Djavax.net.ssl.trustStorePassword=changeit"
scala-cli.real "\$@"
WRAPPER
chmod +x scala-cli
scala-cli --version

cd /home/claude/spac-chisel
echo Warming cache
scala-cli compile . >/dev/null 2>&1

echo Boot complete.
