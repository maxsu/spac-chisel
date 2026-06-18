#!/usr/bin/env bash
set -euo pipefail

CERTS=/usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts

if [ ! -f $CERTS ]; then
	echo FAILED cacerts not at expected path: $CERTS
	exit 1
fi

ls -1 /usr/local/share/ca-certificates/*.crt
for cert in /usr/local/share/ca-certificates/*.crt; do
	alias=$(basename "$cert" .crt)
	if ! [[ $alias =~ ^[a-z0-9-]{1,30}$ ]]; then
		echo FAILED invalid alias format: "$alias"
		exit 1
	fi
	keytool -delete -noprompt -keystore "$CERTS" -storepass changeit -alias "$alias" >/dev/null 2>&1 || true
	if ! keytool -importcert -noprompt -trustcacerts -keystore "$CERTS" -storepass changeit -alias $alias -file $cert >/dev/null 2>&1; then
		echo FAILED $alias
		exit 1
	fi
done

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

cd /root/spac-chisel
echo Warming cache
scala-cli compile . >/dev/null 2>&1

echo Boot complete.
