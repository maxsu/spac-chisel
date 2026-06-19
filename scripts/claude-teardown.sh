#!/usr/bin/env bash
apt-get remove -y verilator scala-cli >/dev/null 2>&1
/home/claude/.local/bin/scaly bloop exit >/dev/null 2>&1
pkill -f bloop 2>/dev/null
rm -f /root/.local/bin/scaly
rm -rf /root/.cache/{scala-cli,scalacli,coursier}
rm -rf /home/claude/spac-chisel/{.scala-build,.bloop}
echo "Teardown complete."
