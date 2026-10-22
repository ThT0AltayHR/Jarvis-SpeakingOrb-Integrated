#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '..');
const player = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt'), 'utf8');
const policy = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/audio/PlaybackCompletionPolicy.kt'), 'utf8');
const service = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt'), 'utf8');
const html = fs.readFileSync(path.join(root, 'app/src/main/assets/orb.html'), 'utf8');
const renderer = fs.readFileSync(path.join(root, 'app/src/main/assets/speaking-orb.js'), 'utf8');

assert.match(player, /fun enqueueAudio\(pcmData: ByteArray\)[\s\S]*?synchronized\(playbackStateLock\)\s*\{\s*synchronized\(completionLock\)\s*\{\s*isTurnComplete\.set\(false\)\s*audioQueue\.offer\(QueuedPcm\(playbackGeneration\.current\(\), aligned\)\)/,
  'new chunks must reset completion and enqueue atomically against playback-state transitions');
assert.match(player, /fun notifyTurnComplete\(\)\s*\{\s*synchronized\(completionLock\)\s*\{\s*isTurnComplete\.set\(true\)/,
  'turn completion signal must use the same lock as queue operations');
assert.doesNotMatch(player, /fun notifyTurnInterrupted\(/,
  'there is no drain-only interruption API that lets stale PCM play after a barge-in');
const interruptedStart = service.indexOf('override fun onInterrupted()');
const interruptedEnd = service.indexOf('override fun onTurnCompleted()', interruptedStart);
assert.ok(interruptedStart >= 0 && interruptedEnd > interruptedStart, 'server interruption callback must exist');
const interruptedHandler = service.slice(interruptedStart, interruptedEnd);
assert.ok(interruptedHandler.indexOf('providerTurnComplete = true') >= 0, 'interruption marks the provider turn complete');
assert.ok(interruptedHandler.indexOf('audioPlayer?.flush()') < interruptedHandler.indexOf('cancelAllActiveTools()'),
  'server interruption flushes the speaker before tool cancellation/cleanup can yield an IDLE race');
assert.doesNotMatch(interruptedHandler, /notifyTurnInterrupted\(/, 'interruption must flush, not drain stale queued PCM');
const dequeueStart = player.indexOf('val firstPacket = audioQueue.poll');
const dequeueEnd = player.indexOf('val written = writeToTrack(firstChunk, firstPacket.generation, chunkAmplitude)', dequeueStart);
assert.ok(dequeueStart >= 0 && dequeueEnd > dequeueStart, 'first-chunk playback path must exist');
assert.doesNotMatch(player.slice(dequeueStart, dequeueEnd), /isTurnComplete\.set\(false\)/,
  'dequeue must not erase a turn-complete signal already received by the worker');
assert.doesNotMatch(player, /silenceCounter\s*>=\s*40/,
  'an 800ms network gap must not forcibly end speech');
assert.match(player, /PlaybackCompletionPolicy\.shouldFinish\(/,
  'the audio worker must use the tested completion gate');
assert.match(policy, /turnComplete\s*&&\s*queueEmpty\s*&&\s*allSamplesPlayed\s*&&\s*idlePolls\s*>=\s*8/,
  'finish requires provider completion, an empty queue, hardware drain and a short drain window');
assert.match(player, /val transitionedToFinished = synchronized\(playbackStateLock\)\s*\{[\s\S]{0,700}synchronized\(completionLock\)\s*\{[\s\S]{0,400}queueEmpty = audioQueue\.isEmpty\(\)/,
  'terminal playback decision, callback, and queue check are serialized with new enqueue');
assert.match(player, /fun flush\(\)\s*\{\s*synchronized\(playbackStateLock\)\s*\{\s*synchronized\(completionLock\)\s*\{\s*playbackGeneration\.advance\(\)\s*audioQueue\.clear\(\)[\s\S]{0,160}isTurnComplete\.set\(true\)/,
  'a deliberate flush advances the generation and atomically clears queued/in-flight stale samples');
assert.match(player, /writeResult == 0[\s\S]{0,1200}Thread\.sleep\(2L\)/,
  'temporary AudioTrack backpressure retries rather than dropping a partial PCM chunk');
assert.match(player, /MAX_WRITE_STALL_MS = 2500L/, 'stalled-write bound is explicitly defined');
assert.match(player, /stalledForMs >= MAX_WRITE_STALL_MS/,
  'a permanently stalled sink must produce a bounded, visible failure instead of hanging in SPEAKING');
assert.match(player, /if \(generation != playbackGeneration\.current\(\)\) return offset/,
  'generation changes abort a native write before stale progress or more PCM writes');
assert.match(player, /if \(pcmData\.size >= 2\)/,
  'small but valid final PCM payloads are not silently dropped');
assert.match(service, /audioPlayer\?\.isPlaying\(\) == true \|\| _audioPlaybackActive\.value/,
  'barge-in suppression remains active while old response audio is still queued/playing even after provider turnComplete');
assert.doesNotMatch(player, /track\.write\(chunk, offset, chunk\.size - offset\)/,
  'no long blocking AudioTrack write may hold the lock needed by flush');
assert.match(service, /finalizeTurn\(preserveCaptionTail\s*=\s*true\)/,
  'normal playback end must preserve the caption during the dust tail');
assert.match(service, /delay\(LIVE_CAPTION_TAIL_MS\)/,
  'final caption clear must be delayed beyond the dust animation');
assert.match(renderer, /playbackHasEnded\s*=\s*!L\.externalSync\s*\|\|\s*\(!this\.externalAudioActive\s*&&\s*this\.externalAudioEndedAt\s*!==\s*null\)/,
  'dust timing must recognize a completed AudioTrack');

// Truth-table simulation for long network stalls and genuine end-of-turn drain.
const shouldFinish = (turnComplete, queueEmpty, allSamplesPlayed, idlePolls) => turnComplete && queueEmpty && allSamplesPlayed && idlePolls >= 8;
for (let gapPolls = 0; gapPolls <= 10000; gapPolls++) {
  assert.equal(shouldFinish(false, true, true, gapPolls), false, `gap ${gapPolls} must not cut off speech`);
  assert.equal(shouldFinish(true, false, true, gapPolls), false, `queued audio at ${gapPolls} must not finish`);
  assert.equal(shouldFinish(true, true, false, gapPolls), false, `undrained frames at ${gapPolls} must not finish`);
  assert.equal(shouldFinish(false, false, false, gapPolls), false, `missing completion signal at ${gapPolls} must not finish`);
}
assert.equal(shouldFinish(true, true, true, 7), false, 'short drain guard must elapse');
assert.equal(shouldFinish(true, true, true, 8), true, 'complete, empty and drained response should finish');
console.log('PASS 40,006 playback completion checks across 0–10,000 idle polls: no network-gap cutoff');
console.log('PASS server turn-complete + hardware drain + short drain window policy');
console.log('PASS completion flag reset occurs on enqueue, not first dequeue');
console.log('PASS final caption tail is retained for the complete dust animation');
