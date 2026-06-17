#!/usr/bin/env bash
set -euo pipefail

echo "Installing scala-cli..."
mkdir -p "$HOME/.local/bin"
curl -fL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz |
	gzip -d >"$HOME/.local/bin/scala-cli"
chmod +x "$HOME/.local/bin/scala-cli"
echo "scala-cli $(scala-cli --version) installed"

echo "Installing verilator..."
apt install -y verilator 2>&1 | tail -1

echo "Importing custom CA certificates into Java truststore..."
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts | head -1)
for cert in /usr/local/share/ca-certificates/*.crt; do
	alias=$(basename "$cert" .crt | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | cut -c1-30)
	keytool -importcert -noprompt -trustcacerts \
		-keystore "$JAVA_CACERTS" -storepass changeit \
		-alias "$alias" -file "$cert" 2>/dev/null && echo "Imported cert: $alias" || true
done

export JAVA_OPTS="-Djavax.net.ssl.trustStore=$JAVA_CACERTS -Djavax.net.ssl.trustStorePassword=changeit"
echo "JAVA_OPTS set for truststore: $JAVA_CACERTS"
echo "Boot complete."
