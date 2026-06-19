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

mkdir -p /etc/apt/keyrings
curl -sS "https://virtuslab.github.io/scala-cli-packages/scala-cli-archive-keyring.gpg" >/etc/apt/keyrings/scala-cli-archive-keyring.gpg

curl -s --compressed -o /etc/apt/sources.list.d/scala_cli_packages.list https://virtuslab.github.io/scala-cli-packages/debian/scala_cli_packages.list
sudo apt update
sudo apt install scala-cli

echo Installing dependencies
shh apt-get update
shh apt-get install -y verilator scala-cli
verilator --version
scala-cli --version

# Update Java Cacerts with anthropic's egress certificates. This prevents SSL errors when running Scala CLI.
if keytool -list -keystore /etc/ssl/certs/java/cacerts -alias anthropic-egress-0 -storepass changeit >/dev/null 2>&1; then
	echo "Certificates already present in cacerts, skipping import."
else
	echo "Importing Egress certificates into cacerts..."
	rm -rf /tmp/certs && mkdir /tmp/certs
	csplit -s -z -f /tmp/certs/cert_ -b '%03d.pem' /etc/ssl/certs/ca-certificates.crt '/-----BEGIN CERTIFICATE-----/' '{*}'
	i=0
	for f in /tmp/certs/*.pem; do
		if openssl x509 -noout -subject -in "$f" 2>/dev/null | grep -qi "egress"; then
			keytool -importcert -noprompt -alias "anthropic-egress-$i" -keystore /etc/ssl/certs/java/cacerts -storepass changeit <"$f"
			i=$((i + 1))
		fi
	done
fi

cd /home/claude/.local/bin
curl -fsSL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d >scala-cli.real
chmod +x scala-cli.real

cat >scala-cli <<'WRAPPER'
#!/bin/sh
# Set trustStore before declaring scala-cli command, so that graalvm native-image picks up the correct cacerts file.
scala-cli.real -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts "$@"
exit $?
WRAPPER
chmod +x scala-cli
scala-cli --version

echo Warming cache
cd /home/claude/spac-chisel
shh scala-cli compile .

echo "Boot complete. Suppressed ${chars} chars of output (~$((chars / 4)) tokens)."
