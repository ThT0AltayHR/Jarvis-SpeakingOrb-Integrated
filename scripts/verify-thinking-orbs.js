#!/usr/bin/env node
'use strict';

// Dependency-free smoke test for the bundled Thinking Orbs canvas engine.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const projectRoot = path.resolve(__dirname, '..');
const assets = path.join(projectRoot, 'app', 'src', 'main', 'assets');
const enginePath = path.join(assets, 'thinking-orb-engine.js');
const engineSource = fs.readFileSync(enginePath, 'utf8');
const windowShim = {};

vm.runInNewContext(engineSource, {
  window: windowShim,
  Math, Map, Object, Number, String, Array, Set, Error
}, { filename: enginePath, timeout: 5000 });

const expectedStates = [
  'working', 'searching', 'solving', 'listening', 'connecting',
  'weaving', 'composing', 'breathing', 'shaping'
];
const engine = windowShim.JarvisThinkingOrbEngine;
assert.ok(engine, 'Thinking Orb engine should expose its public API');
assert.deepEqual(Array.from(engine.states), expectedStates, 'all nine presets should be registered in order');

const stats = { arcs: 0, fills: 0, strokes: 0, lineTo: 0, invalidCoordinates: 0 };
const validateNumbers = (values) => {
  if (values.some((value) => typeof value !== 'number' || !Number.isFinite(value))) {
    stats.invalidCoordinates++;
  }
};
const ctx = {
  set fillStyle(_value) {},
  set strokeStyle(_value) {},
  set lineWidth(value) { validateNumbers([value]); },
  beginPath() {},
  arc(x, y, radius, start, end) {
    stats.arcs++;
    validateNumbers([x, y, radius, start, end]);
    assert.ok(radius > 0, 'dot radius must be positive');
  },
  fill() { stats.fills++; },
  stroke() { stats.strokes++; },
  moveTo(x, y) { validateNumbers([x, y]); },
  lineTo(x, y) { stats.lineTo++; validateNumbers([x, y]); },
};

for (const state of expectedStates) {
  const before = { ...stats };
  engine.draw(ctx, state, 240, 3.25, true, 1);
  const operations = ['arcs', 'fills', 'strokes', 'lineTo']
    .reduce((count, key) => count + stats[key] - before[key], 0);
  assert.ok(operations > 0, `${state} must draw at least one canvas operation`);
  const preset = engine.resolvePreset(state);
  assert.ok(preset && typeof preset.mode === 'string', `${state} must resolve to an engine mode`);
  assert.ok(Number.isFinite(preset.speed) && preset.speed > 0, `${state} must have a valid speed`);
  console.log(`PASS ${state.padEnd(10)} -> ${preset.mode.padEnd(7)} (${operations} canvas operations)`);
}

assert.equal(stats.invalidCoordinates, 0, 'canvas coordinates and radii must remain finite');
const orbHtml = fs.readFileSync(path.join(assets, 'orb.html'), 'utf8');
for (const src of orbHtml.matchAll(/<script\s+src=["']([^"']+)["']/g)) {
  assert.ok(fs.existsSync(path.join(assets, src[1])), `missing local JavaScript asset: ${src[1]}`);
}
assert.match(orbHtml, /window\.JarvisOrb\s*=\s*\{/,
  'orb.html should expose the native-to-WebView bridge');
assert.match(orbHtml, /setExternalAudio:\s*\(amp, bass, high, playbackMs, playbackActive\)/,
  'native audio clock and active state should reach the orb');
assert.match(orbHtml, /externalSync:\s*true/,
  'live assistant captions should use the hardware playback clock');
assert.match(orbHtml, /clearCaptionDust/,
  'caption clearing should explicitly clear the particle canvas');
assert.match(orbHtml, /left:50%; top:1%;[^}]*width:min\(25vw, 96px\); height:min\(25vw, 96px\)/,
  'thinking animation should occupy a reserved status area above the speaking sphere');
assert.match(orbHtml, /speaking-orb \{ display:block; position:absolute; left:14%; bottom:0; width:72%/,
  'main speaking sphere should remain visible below the status area');
assert.doesNotMatch(orbHtml, /speaking-orb\s*\{\s*opacity:0/,
  'thinking animation must not hide the speaking orb');
const speakingOrbSource = fs.readFileSync(path.join(assets, 'speaking-orb.js'), 'utf8');
assert.match(speakingOrbSource, /const autoCount = \(\) => matchMedia\('\(pointer: coarse\)'\)\.matches \? 2800 : 8000/,
  'mobile particle density should be bounded for smooth rendering');
assert.match(speakingOrbSource, /this\.frameIntervalMs=\/Android\/i\.test\(navigator\.userAgent\|\|''\)\?1000\/30/,
  'the main speaking orb should use a mobile-friendly frame cap');
assert.match(speakingOrbSource, /L\.externalSync&&!this\.externalAudioActive/,
  'caption text and dust should only animate while the real audio is playing');
assert.match(orbHtml, /thinkingFrameIntervalMs = isAndroidWebView \? \(1000 \/ 30\)/,
  'thinking animations should be frame-capped in Android WebView');
assert.match(orbHtml, /Math\.min\(isAndroidWebView \? 1\.35 : 2, deviceDpr\)/,
  'thinking canvas resolution should be capped on mobile GPU');

// Exercise the actual inline WebView controller with a small DOM/timer shim.
const inlineMatch = orbHtml.match(/<script>([\s\S]*?)<\/script>/);
assert.ok(inlineMatch, 'orb.html should include its WebView controller');
const clock = { now: 0, nextId: 1, timers: new Map(), frames: new Set() };
const fakeSetTimeout = (fn, delay) => {
  const id = clock.nextId++;
  clock.timers.set(id, { fn, due: clock.now + delay });
  return id;
};
const fakeClearTimeout = (id) => clock.timers.delete(id);
const advanceClock = (delta) => {
  clock.now += delta;
  for (;;) {
    const due = [...clock.timers.entries()]
      .filter(([, timer]) => timer.due <= clock.now)
      .sort((a, b) => a[1].due - b[1].due)[0];
    if (!due) break;
    clock.timers.delete(due[0]);
    due[1].fn();
  }
};
const classNames = new Set();
const fakeRoot = {
  clientWidth: 240, style: {},
  classList: {
    toggle(name, active) { if (active) classNames.add(name); else classNames.delete(name); },
    contains(name) { return classNames.has(name); },
  },
};
const fakeContext = Object.assign({}, ctx, {
  setTransform() {}, clearRect() {},
});
const fakeCanvas = {
  width: 0, height: 0, attributes: {},
  getContext: () => fakeContext,
  setAttribute(name, value) { this.attributes[name] = value; },
};
const fakeOrb = {
  state: 'done', time: 0, line: null, level: 0, externalAudioTime: 0, externalAudioLevel: 0, externalAudioActive: false,
  capEl: { textContent: '', classList: { remove() {} } },
  dustClearedCount: 0, clearCaptionDust() { this.dustClearedCount++; },
  estimate(tokens, start) { return tokens.map((w, i) => ({ w, start: start + i * 0.1, end: start + (i + 1) * 0.1 })); },
  pauseCount: 0, resumeCount: 0,
  pause() { this.pauseCount++; }, resume() { this.resumeCount++; },
};
const fakeDocument = {
  visibilityState: 'visible',
  getElementById(id) { return id === 'jarvisOrb' ? fakeOrb : id === 'orbRoot' ? fakeRoot : fakeCanvas; },
  addEventListener() {},
};
const fakeWindow = {
  matchMedia: () => ({ matches: false }), devicePixelRatio: 1,
  setTimeout: fakeSetTimeout, clearTimeout: fakeClearTimeout,
  addEventListener() {}, JarvisThinkingOrbEngine: engine,
};
class FakeResizeObserver { observe() {} disconnect() {} }
const fakeRaf = (fn) => { const id = clock.nextId++; clock.frames.add(id); return id; };
const fakeCancelRaf = (id) => clock.frames.delete(id);
vm.runInNewContext(inlineMatch[1], {
  window: fakeWindow, document: fakeDocument, navigator: { userAgent: 'Android WebView' }, performance: { now: () => clock.now },
  requestAnimationFrame: fakeRaf, cancelAnimationFrame: fakeCancelRaf,
  ResizeObserver: FakeResizeObserver, Number, String, Set, Math,
}, { filename: 'orb.html:inline-script', timeout: 5000 });
const bridge = fakeWindow.JarvisOrb;
assert.ok(bridge, 'WebView controller should publish JarvisOrb');
bridge.setExternalAudio(0.42, 0.2, 0.3, 875, true);
assert.equal(fakeOrb.externalAudioTime, 0.875, 'the real playback clock should reach the renderer in seconds');
assert.equal(fakeOrb.externalAudioLevel, 0.42, 'voice animation should use the measured audio level');
assert.equal(fakeOrb.externalAudioActive, true, 'captions should know when audible playback starts');
bridge.setExternalAudio(0, 0, 0, 0, false);
assert.equal(fakeOrb.externalAudioActive, false, 'the caption/dust layer should be able to stop with audible playback');
bridge.setExternalAudio(0.55, 0.2, 0.4, 1250, true);
bridge.appendText('Merhaba dünya');
assert.equal(fakeOrb.line.externalSync, true, 'live captions should use hardware playback time');
assert.equal(fakeOrb.line.words[0].start, 1.25, 'the first caption should anchor to the real audio playhead');
const clearsBefore = fakeOrb.dustClearedCount;
bridge.clearCaption();
assert.equal(fakeOrb.line, null, 'clearing the transcript should clear its active word line');
assert.ok(fakeOrb.dustClearedCount > clearsBefore, 'clearing the transcript should clear lingering dust');
bridge.setState('speaking');
bridge.setTaskState('COMPLETED');
assert.equal(fakeRoot.classList.contains('thinking-active'), false,
  'completion should stay behind an active spoken response');
assert.equal(clock.timers.size, 0,
  'completion display duration must not elapse during spoken response');
advanceClock(2000);
bridge.setState('listening');
assert.equal(fakeRoot.classList.contains('thinking-active'), true,
  'queued completion should appear when speaking ends');
assert.equal(clock.timers.size, 1, 'completion timer should begin after it becomes visible');
advanceClock(1249);
assert.equal(fakeRoot.classList.contains('thinking-active'), true,
  'completion should remain visible through its display interval');
advanceClock(1);
assert.equal(fakeRoot.classList.contains('thinking-active'), true,
  'the listening preset should return after the completion display interval');
assert.equal(fakeCanvas.attributes['aria-label'], 'Jarvis dinliyor',
  'listening should activate the listening preset above the base orb');
bridge.setState('idle');
const nativeTaskStates = {
  UNDERSTANDING: 'Jarvis düşünüyor',
  PLANNING: 'Jarvis plan hazırlıyor',
  OBSERVING: 'Jarvis arıyor',
  RESOLVING_TARGET: 'Jarvis arıyor',
  ACTING: 'Jarvis işlem yapıyor',
  VERIFYING: 'Jarvis sonucu doğruluyor',
  CONTINUING: 'Jarvis adımları birleştiriyor',
  REPLANNING: 'Jarvis işlemi yeniden düzenliyor',
  RECOVERING: 'Jarvis işlemi yeniden düzenliyor',
  WAITING_FOR_UI: 'Jarvis bağlantı kuruyor',
  PAUSED: 'Jarvis düşünüyor',
  BLOCKED: 'Jarvis işlemi yeniden düzenliyor',
  FAILED: 'Jarvis işlemi yeniden düzenliyor',
  CANCELLED: 'Jarvis düşünüyor',
  COMPLETED: 'Jarvis sonucu doğruluyor',
};
for (const [state, expectedLabel] of Object.entries(nativeTaskStates)) {
  bridge.setTaskState(state);
  assert.equal(fakeRoot.classList.contains('thinking-active'), true, `${state} should surface a task animation`);
  assert.equal(fakeCanvas.attributes['aria-label'], expectedLabel, `${state} should map to its intended animation label`);
  bridge.setTaskState('IDLE');
}
assert.equal(fakeRoot.classList.contains('thinking-active'), false, 'idle should restore the Speaking Orb');
bridge.setThinkingAnimation('weaving');
assert.equal(fakeRoot.classList.contains('thinking-active'), true, 'manual preset should show the animation overlay');
bridge.clearThinkingAnimation();
assert.equal(fakeRoot.classList.contains('thinking-active'), false, 'clearing manual preset should restore the base orb');
const audioPlayerSource = fs.readFileSync(path.join(projectRoot, 'app', 'src', 'main', 'java', 'com', 'jarvis', 'assistant', 'audio', 'AudioPlayer.kt'), 'utf8');
assert.match(audioPlayerSource, /playbackHeadPosition/,
  'the Android playback clock must come from AudioTrack hardware position');
assert.match(audioPlayerSource, /allSamplesPlayed/,
  'playback must not be marked finished until buffered PCM is rendered');
console.log('PASS local script references, hardware playback clock, and caption/dust cleanup hooks');
console.log('PASS queued completion, planning state, and additive animation lifecycle');
console.log('PASS all Thinking Orb smoke checks');
