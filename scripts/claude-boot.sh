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

cd /home/claude/.local/bin
curl -fsSL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz | gzip -d >scala-cli
chmod +x scala-cli
scala-cli --version

echo Warming cache
cd /home/claude/spac-chisel
shh scala-cli compile .

echo "Boot complete. Suppressed ${chars} chars of output (~$((chars / 4)) tokens)."
