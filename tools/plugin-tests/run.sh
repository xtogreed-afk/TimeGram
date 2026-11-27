#!/usr/bin/env bash
# Compiles LuaJ + the plugin engines and runs all plugin tests on the host JVM (no Android needed).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
javac --release 8 -nowarn -d "$OUT" \
  $(find TMessagesProj/src/main/java/org/luaj TMessagesProj/src/main/java/org/telegram/messenger/timegram -name '*.java') \
  tools/plugin-tests/PluginEngineTests.java tools/plugin-tests/PgEngineTests.java
echo "== JSON rules engine"; java -cp "$OUT" org.telegram.messenger.timegram.plugins.PluginEngineTests
echo; echo "== Lua (.pg) sandbox"; java -cp "$OUT" org.telegram.messenger.timegram.plugins.PgEngineTests
