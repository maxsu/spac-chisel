#!/usr/bin/env bash
set -euo pipefail

url=https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz
dest="$HOME/.local/bin/scala-cli.real"
peek=40

quiet() {
	local desc="$1"
	shift
	local log status
	log=$(mktemp)
	if "$@" >"$log" 2>&1; then
		status=0
	else
		status=$?
		echo "FAILED: $desc"
		echo "--- last $peek lines of output ---"
		tail -n "$peek" "$log"
		echo "See full output in $log"
	fi
	return "$status"
}

scala_download() {
	curl -fsSL $url | gzip -d >"$dest"
	chmod +x "$dest"
}

quiet "Installing scala-cli..." scala_download

quiet "Installing verilator..." apt-get install -qq verilator

echo "Importing custom CA certificates into Java truststore..."
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts | head -1)
cert_count=0
for cert in /usr/local/share/ca-certificates/*.crt; do
	alias=$(basename "$cert" .crt | tr '[:upper:]' '[:lower:]' | tr ' ' '_' | cut -c1-30)
	# On re-run, clear aliases to avoid "alias already exists" error.
	keytool -delete -noprompt -keystore "$JAVA_CACERTS" -storepass changeit -alias "$alias" >/dev/null 2>&1 || true
	echo "import cert $alias"
	keytool -importcert -noprompt -trustcacerts \
		-keystore "$JAVA_CACERTS" -storepass changeit -alias "$alias" -file "$cert" >/dev/null 2>&1
	cert_count=$((cert_count + 1))
done
echo "Imported $cert_count cert(s) into JVM truststore."

echo "Wrap scala-cli"

# Note: Claude's local sandbox PATH includes .local/bin.
# But we still need to create it 💪
mkdir -p "$HOME/.local/bin"

cat >"$HOME/.local/bin/scala-cli" <<'WRAPPER'
#!/bin/sh
NUISANCE_PATTERNS='^(Downloading|Downloaded|Failed to download) https?://'
JAVA_CACERTS=$(find /usr/lib/jvm -name cacerts 2>/dev/null | head -1)
export JAVA_OPTS="-Djavax.net.ssl.trustStore=${JAVA_CACERTS} -Djavax.net.ssl.trustStorePassword=changeit"
log=$(mktemp)
peek=60
if "$HOME/.local/bin/scala-cli.real" "$@" >"$log" 2>&1; then
    status=0
	show=$(grep -vE "$NUISANCE_PATTERNS" "$log" || true)
else
    status=$?
    echo "scala-cli $* failed (exit $status). Last $peek lines:" >&2
	show=$(tail -n "$peek" "$log")
fi
echo "$show"
count=$(wc -c <"$log")
kept=$(echo "$show" | wc -c)
this_many=$(( (count - kept) / 4 ))
echo "Saved about $this_many tokens!"
echo "Full output in $log"
exit "$status"
WRAPPER

chmod +x "$HOME/.local/bin/scala-cli"
echo "scala-cli $("$HOME/.local/bin/scala-cli.real" --version 2>&1 | grep 'Scala CLI') installed"
echo "Boot complete."
