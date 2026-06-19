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

echo Updating Java Cacerts
keytool -list -keystore /etc/ssl/certs/java/cacerts -alias anthropic-egress-production -storepass changeit >/dev/null 2>&1 ||
	keytool -importcert -noprompt -alias anthropic-egress-production -keystore /etc/ssl/certs/java/cacerts -storepass changeit -file /usr/local/share/ca-certificates/egress-gateway-ca-production.crt

echo Installing Verilator and Scala-Cli
curl -sS "https://virtuslab.github.io/scala-cli-packages/scala-cli-archive-keyring.gpg" >/etc/apt/keyrings/scala-cli-archive-keyring.gpg
curl -s --compressed -o /etc/apt/sources.list.d/scala_cli_packages.list https://virtuslab.github.io/scala-cli-packages/debian/scala_cli_packages.list
shh apt-get update
shh apt-get install -y verilator scala-cli
verilator --version
scala-cli --version

echo Installing Scaly 🐉
mkdir -p /root/.local/bin
cat >/root/.local/bin/scaly <<'WRAPPER'
#!/bin/sh
scala-cli -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts "$@"
exit $?
WRAPPER
chmod +x /root/.local/bin/scaly
scaly --version

echo Warming cache
cd /home/claude/spac-chisel
shh scaly compile .
shh scaly test . --test-only spac.hw.RxEngineTest
echo "Boot complete. Suppressed ${chars} chars of output (~$((chars / 4)) tokens)."
