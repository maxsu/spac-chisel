#!/usr/bin/env bash
set -euo pipefail

echo "Installing scala-cli..."
mkdir -p "$HOME/.local/bin"
curl -fL https://github.com/Virtuslab/scala-cli/releases/latest/download/scala-cli-x86_64-pc-linux.gz |
	gzip -d >"$HOME/.local/bin/scala-cli"
chmod +x "$HOME/.local/bin/scala-cli"
echo "$(scala-cli --version) installed"

echo "Installing verilator..."
apt install -y verilator >/tmp/err.log 2>&1 || {
	cat /tmp/err.log
	exit 1
}

echo "Boot complete."
