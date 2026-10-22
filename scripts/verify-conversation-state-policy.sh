#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  "$ROOT/app/src/main/java/com/jarvis/assistant/data/model/ConversationState.kt" \
  "$ROOT/app/src/main/java/com/jarvis/assistant/data/model/ConversationStatePolicy.kt" \
  "$ROOT/scripts/state-policy-harness/ConversationStatePolicyHarness.kt" \
  -include-runtime -d "$TMP/state-policy.jar"
java -jar "$TMP/state-policy.jar"
