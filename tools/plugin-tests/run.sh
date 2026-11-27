#!/usr/bin/env bash
# Compiles the plugin engine and runs its tests on the host JVM (no Android needed).
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=$(mktemp -d)
javac -d "$OUT" TMessagesProj/src/main/java/org/telegram/messenger/timegram/plugins/*.java tools/plugin-tests/PluginEngineTests.java
java -cp "$OUT" org.telegram.messenger.timegram.plugins.PluginEngineTests
