#!/usr/bin/env bash
set -euo pipefail

log=$(mktemp)
shh() {
	echo "#" "$@" >"$log"
	if ! "$@" >"$log" 2>&1; then
		echo "ERROR! See $log for details!"
		return 1
	fi
}

echo Updating Java Cacerts
shh keytool -importcert \
	-alias anthropic-egress-production \
	-keystore /etc/ssl/certs/java/cacerts \
	-storepass changeit \
	-file /usr/local/share/ca-certificates/egress-gateway-ca-production.crt \
	-noprompt

echo Installing verilator and scala-cli
cd /etc/apt
BASE=https://virtuslab.github.io/scala-cli-packages
curl -s $BASE/scala-cli-archive-keyring.gpg >./keyrings/scala-cli-archive-keyring.gpg
curl -s $BASE/debian/scala_cli_packages.list >./sources.list.d/scala_cli_packages.list
shh apt-get update
shh apt-get install -y verilator scala-cli
verilator --version
scala-cli --version

echo Warming cache
cd /home/claude/spac-chisel
shh scala-cli compile .
shh scala-cli test . --test-only spac.hw.RxEngineTest

chars=$(wc -c <"$log")
echo "Boot complete. Suppressed ~$((chars / 4)) tokens of output. See $log if necessary!"
echo "Enjoy your circuit design!"
