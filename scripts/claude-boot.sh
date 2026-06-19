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

echo Installing dependencies
shh apt-get update
shh apt-get install -y verilator
verilator --version

if keytool -list -keystore /etc/ssl/certs/java/cacerts -alias anthropic-egress-gateway -storepass changeit >/dev/null 2>&1; then
	echo "Certificate already present in cacerts, skipping import."
else
	echo "Importing Egress certificate into cacerts..."
	openssl s_client -connect repo1.maven.org:443 -servername repo1.maven.org -showcerts </dev/null 2>/dev/null |
		awk '/BEGIN/{c++} c==2,/END/' |
		keytool -importcert -noprompt -alias anthropic-egress-gateway -keystore /etc/ssl/certs/java/cacerts -storepass changeit
fi

cd /home/claude/.local/bin
curl -fsSL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d >scala-cli.real
chmod +x scala-cli.real

cat >scala-cli <<'WRAPPER'
#!/bin/sh
export JAVA_OPTS="-Djavax.net.ssl.trustStorePassword=changeit"
# Ensure graalvm native image finds correct cacerts
# It uses pre-bundled cacerts with no egress gateway cert, so use our patched version instead
scala-cli.real -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts "$@"
exit $?
WRAPPER
chmod +x scala-cli
scala-cli --version

echo Warming cache
cd /home/claude/spac-chisel
shh scala-cli compile .

echo "Boot complete. Suppressed ${chars} chars of output (~$((chars / 4)) tokens)."
