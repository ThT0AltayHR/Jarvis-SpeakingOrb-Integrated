#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HARNESS="$ROOT/scripts/audio-player-harness"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
kotlinc \
  "$HARNESS/android/media/AudioAttributes.kt" \
  "$HARNESS/android/media/AudioFormat.kt" \
  "$HARNESS/android/media/AudioTrack.kt" \
  "$HARNESS/android/os/Process.kt" \
  "$HARNESS/android/util/Log.kt" \
  "$HARNESS/com/jarvis/assistant/audio/AudioPlayerHarness.kt" \
  "$ROOT/app/src/main/java/com/jarvis/assistant/audio/AudioUtils.kt" \
  "$ROOT/app/src/main/java/com/jarvis/assistant/audio/PlaybackCompletionPolicy.kt" \
  "$ROOT/app/src/main/java/com/jarvis/assistant/audio/PlaybackGenerationGate.kt" \
  "$ROOT/app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt" \
  -include-runtime -d "$TMP/audio-player-tests.jar"
java -jar "$TMP/audio-player-tests.jar"
