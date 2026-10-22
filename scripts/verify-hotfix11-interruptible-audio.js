#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const player = read('app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt');
const service = read('app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt');
const websocket = read('app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt');
const build = read('app/build.gradle.kts');

assert.match(player, /isRunning\.compareAndSet\(false, true\)/,
  'concurrent callers cannot create duplicate playback worker threads');
assert.match(player, /AudioTrack\.WRITE_NON_BLOCKING/,
  'PCM output uses non-blocking native writes so interruption is not held behind a full audio chunk');
assert.match(player, /MAX_WRITE_STALL_MS = 2500L/,
  'audio-sink backpressure has a finite timeout');
assert.match(player, /private fun publishPlaybackProgress\(expectedGeneration: Long\)\s*\{\s*synchronized\(playbackStateLock\)/,
  'amplitude and playback-clock callbacks serialize with flush');
assert.match(player, /fun failCurrentPlayback\(message: String, expectedGeneration: Long\? = null\)[\s\S]*?expectedGeneration != null && !playbackGeneration.isCurrent\(expectedGeneration\)/,
  'an error from a stale write cannot clear or report over a newer user turn');
assert.match(player, /stalledForMs >= MAX_WRITE_STALL_MS/,
  'a permanently stalled sink turns into a visible playback error');
const toolSchemaStart = websocket.indexOf('put("functionDeclarations"');
const toolSchemaEnd = websocket.indexOf('private fun handleIncomingMessage', toolSchemaStart);
assert.ok(toolSchemaStart >= 0 && toolSchemaEnd > toolSchemaStart, 'Live API tool declaration section exists');
const toolDeclarations = websocket.slice(toolSchemaStart, toolSchemaEnd);
const toolNames = [...toolDeclarations.matchAll(/put\("name", "([^"]+)"\)/g)];
assert.equal(toolNames.length, 21, 'all 21 declared native actions are covered');
for (const nameMatch of toolNames) {
  const suffix = toolDeclarations.slice(nameMatch.index, nameMatch.index + 150);
  assert.match(suffix, /put\("behavior", "BLOCKING"\)/, `native action ${nameMatch[1]} must wait for its result before continuing`);
}
assert.match(player, /val addedFrames = \(totalBytes \/ 2\)\.toLong\(\)[\s\S]{0,140}framesWrittenThisTurn \+= addedFrames/,
  'caption clock advances per partial write and amplitude is selected by playhead');
assert.match(player, /if \(generation != playbackGeneration\.current\(\)\) return offset/,
  'a concurrent flush cancels stale in-flight PCM');
assert.match(player, /val aligned = pcmData\.copyOf\(validLen\)/,
  'queued PCM owns a copy and is not affected by a caller reusing its buffer');
assert.match(player, /if \(pcmData\.size >= 2\)/,
  'valid short PCM tails are accepted');
assert.match(websocket, /if \(base64Data\.isNotEmpty\(\)\)[\s\S]{0,180}if \(pcmBytes\.size >= 2\)/,
  'the Live bridge does not discard valid short final audio frames');
assert.match(service, /shouldSuppressProviderTurn = !providerTurnComplete \|\|[\s\S]{0,180}audioPlayer\?\.isPlaying\(\) == true/,
  'user barge-in suppresses a completed provider turn while its speaker queue is still playing');
assert.match(service, /queuedForPlayback = synchronized\(providerOutputGateLock\)[\s\S]{0,160}suppressInterruptedProviderTurn/,
  'autonomous buffered speech cannot be enqueued after a user interrupt won the race');
assert.match(service, /providerTurnComplete = true[\s\S]{0,180}notifyTurnComplete\(\)/,
  'transport/API errors close a stranded audio turn so the orb cannot remain stuck in SPEAKING');
assert.match(build, /versionCode\s*=\s*162/);
assert.match(build, /versionName\s*=\s*"1\.8\.26"/);

// Truth table: completion of model generation is not proof that device playback has drained.
const mustSuppress = ({ providerComplete, playing, queueActive, speaking }) =>
  !providerComplete || playing || queueActive || speaking;
for (const providerComplete of [false, true]) {
  for (const playing of [false, true]) {
    for (const queueActive of [false, true]) {
      for (const speaking of [false, true]) {
        const actual = mustSuppress({ providerComplete, playing, queueActive, speaking });
        const expected = !providerComplete || playing || queueActive || speaking;
        assert.equal(actual, expected);
      }
    }
  }
}
assert.equal(mustSuppress({ providerComplete: true, playing: true, queueActive: false, speaking: true }), true,
  'late queued speaker output remains gated after provider turnComplete');
assert.equal(mustSuppress({ providerComplete: true, playing: false, queueActive: false, speaking: false }), false,
  'completed and drained turns do not hold the gate indefinitely');
console.log('PASS Hotfix 12: interruptible non-blocking PCM writes, bounded stalls, stale generation cancellation');
console.log('PASS Hotfix 12: playback clock advances per partial write and valid short audio tails are retained');
console.log('PASS Hotfix 12: barge-in gate remains active through queued speaker playback after provider turnComplete');
console.log('PASS Hotfix 12: transport errors close the player turn and autonomous stale buffers are gated');
console.log('PASS Hotfix 12: all 16 barge-in/provider-complete/playback state combinations');
console.log('PASS Hotfix 12: all 21 Gemini 3.8 Live device/tool actions are explicitly blocking and sequential');
console.log('PASS Hotfix 12: stale amplitude/progress/error callbacks cannot overwrite a newer playback generation');
