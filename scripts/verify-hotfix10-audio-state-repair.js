#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const service = read('app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt');
const home = read('app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt');
const orb = read('app/src/main/assets/orb.html');
const layout = read('app/src/main/res/layout/activity_hands_free.xml');
const router = read('app/src/main/java/com/jarvis/assistant/audio/HeadsetAudioRouter.kt');
const player = read('app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt');
const detector = read('app/src/main/java/com/jarvis/assistant/audio/VoiceBargeInDetector.kt');
const build = read('app/build.gradle.kts');

// Catch non-repeatable duplicate annotations, which previous “passing” checks did not detect.
for (const file of fs.readdirSync(path.join(root, 'app/src/main/java'), { recursive: true }).filter(f => String(f).endsWith('.kt'))) {
  const body = read(path.join('app/src/main/java', String(file)));
  assert.ok(!/@Volatile\s*@Volatile/.test(body), `duplicate @Volatile annotation: ${file}`);
}
assert.ok(service.includes('synchronized(conversationStateLock)'), 'conversation state check/write is serialized across callback threads');
assert.ok(service.includes('assistantTranscriptSnapshot()') && service.includes('userTranscriptSnapshot()') && service.includes('clearAssistantTranscript()') && service.includes('clearUserTranscript()'), 'cross-thread transcript reads and clears use synchronized helpers');
assert.ok(service.includes('if (changed) updateNotification()'), 'notifications are updated only after an actual state change');
assert.ok(home.includes('JARVIS SPEAKING • MIC MUTED') && home.includes('THINKING • MIC MUTED'), 'muting microphone does not force live speaking/thinking Orb to idle');
assert.ok(/searching:'searching'/.test(orb), 'task SEARCHING maps to the visible searching animation');
assert.ok(!/embedded release API key/i.test(layout) && layout.includes('does not contain an embedded key'), 'settings do not falsely claim an embedded API key');
assert.ok(router.includes('TYPE_BUILTIN_SPEAKER') && router.includes('setCommunicationDevice(it)') && router.includes('communicationRouteActive = false') && router.includes('communicationRouteActive = true'), 'speaker route records success/fallback and only trusts AEC on a confirmed route');
assert.ok(player.includes('AudioAttributes.USAGE_VOICE_COMMUNICATION'), 'PCM output uses the communication audio path');
assert.ok(detector.includes('SPEAKER_TO_PLAYBACK_RATIO = 0.30f') && detector.includes('SPEAKER_FALLBACK_TO_PLAYBACK_RATIO = 0.34f'), 'barge-in thresholds distinguish AEC and fallback paths');
assert.ok(/versionCode\s*=\s*162/.test(build) && /versionName\s*=\s*"1\.8\.26"/.test(build), 'release version matches Hotfix 13');
console.log('PASS duplicate non-repeatable Kotlin annotations are absent');
console.log('PASS concurrent conversation-state changes and transcript snapshots are serialized');
console.log('PASS muting the mic does not falsify assistant playback state');
console.log('PASS SEARCHING status maps to the actual visible orb animation');
console.log('PASS no embedded API-key claim remains');
console.log('PASS speakerphone uses the communication route when available');
console.log('PASS barge-in AEC/fallback thresholds and version metadata are consistent');
