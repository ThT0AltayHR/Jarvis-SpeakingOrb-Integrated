#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const read = rel => fs.readFileSync(path.join(root, rel), 'utf8');
const player = read('app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt');
const policy = read('app/src/main/java/com/jarvis/assistant/data/model/ConversationStatePolicy.kt');
const build = read('app/build.gradle.kts');
assert.match(player, /data class PlaybackSegment[\s\S]*?startFrame: Long[\s\S]*?endFrame: Long[\s\S]*?amplitude: Float/,
  'playback audio levels are stored against frame ranges');
assert.match(player, /val activeSegment = playbackSegments\.firstOrNull[\s\S]*?playedFrames > segment\.startFrame && playedFrames < segment\.endFrame/,
  'the reported level follows the current device playhead instead of newest queued PCM');
assert.match(player, /appendPlaybackSegmentLocked\([\s\S]*?startFrame = startFrame[\s\S]*?endFrame = framesWrittenThisTurn/,
  'every successful partial write adds its range to the audible amplitude timeline');
assert.match(player, /Recreating AudioTrack discards its hardware queue[\s\S]{0,300}offset = 0/,
  'AudioTrack recovery replays the current chunk rather than skipping bytes accepted by a discarded sink');
assert.match(policy, /if \(playbackActive && !userInterrupting && requested != ConversationState\.SPEAKING\)/,
  'queued or playing PCM blocks stale IDLE/LISTENING/THINKING transitions before SPEAKING is published');
assert.match(build, /versionCode\s*=\s*162/);
assert.match(build, /versionName\s*=\s*"1\.8\.26"/);
console.log('PASS Hotfix 12 source guards: playhead-linked amplitude, stale Orb-state suppression and chunk replay on AudioTrack rebuild');
