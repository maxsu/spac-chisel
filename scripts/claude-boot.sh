#!/usr/bin/env bash
set -euo pipefail

echo Installing scala-cli...
mkdir -p "$HOME/.local/bin"
curl -fL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz |
	gzip -d >"$HOME/.local/bin/scala-cli.real"
chmod +x "$HOME/.local/bin/scala-cli.real"

echo Installing verilator...
apt install -y verilator >/tmp/err.log 2>&1 || {
	cat /tmp/err.log
	exit 1
}

echo Importing custom CA certificates into Java truststore...
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts | head -1)
for cert in /usr/local/share/ca-certificates/*.crt; do
	alias=$(basename "$cert" .crt | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | cut -c1-30)
	keytool -importcert -noprompt -trustcacerts \
		-keystore "$JAVA_CACERTS" -storepass changeit \
		-alias "$alias" -file "$cert" && echo "Imported cert: $alias"

done

echo Wrapping scala-cli to bake in JVM truststore flags...
cat >"$HOME/.local/bin/scala-cli" <<'WRAPPER'
#!/bin/sh
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts 2>/dev/null | head -1)
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=${JAVA_CACERTS} -Djavax.net.ssl.trustStorePassword=changeit"
exec ~/.local/bin/scala-cli.real "$@"
WRAPPER
chmod +x "$HOME/.local/bin/scala-cli"
echo "scala-cli $("$HOME/.local/bin/scala-cli" --version 2>&1 | grep 'Scala CLI') installed"

echo Boot complete.
