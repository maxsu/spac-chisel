#!/usr/bin/env bash
set -euo pipefail

chars=0
shh() {
	log=$(mktemp)
	if ! "$@" >"$log" 2>&1; then
		echo "ERROR! See $log for details!"
		return 1
	fi
	chars=$((chars + $(wc -c <"$log")))
}

echo Copying Java cacerts
CERTS=/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts
rm -f $CERTS
cp /etc/ssl/certs/java/cacerts $CERTS

echo Installing dependencies
shh apt-get update
shh apt-get install -y verilator
verilator --version

cd /home/claude/.local/bin
curl -fsSL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d >scala-cli.real
chmod +x scala-cli.real
cat >scala-cli <<WRAPPER
#!/bin/sh
export JAVA_OPTS="-Djavax.net.ssl.trustStore=$CERTS -Djavax.net.ssl.trustStorePassword=changeit"
scala-cli.real "\$@"
WRAPPER
chmod +x scala-cli
scala-cli --version

echo Warming cache
cd /home/claude/spac-chisel
shh scala-cli compile .

echo "Boot complete. Suppressed ${chars} chars of output (~$((chars / 4)) tokens)."
