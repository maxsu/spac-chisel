#!/usr/bin/env bash
apt-get remove -y verilator >/dev/null 2>&1
/home/claude/.local/bin/scala-cli bloop exit >/dev/null 2>&1
pkill -f bloop 2>/dev/null
rm -f /usr/lib/jvm/java-21-openjdk-amd64/lib/security/cacerts
rm -f /home/claude/.local/bin/scala-cli{,.real}
rm -rf /home/claude/spac-chisel/{.scala-build,.bloop}
rm -rf /home/claude/.cache/{scala-cli,scalacli,coursier}
echo "Teardown complete."
