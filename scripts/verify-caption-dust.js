#!/usr/bin/env node
'use strict';

// Isolated deterministic tests for the real SpeakingOrb caption-dissolution renderer.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

let now = 1000;
const definitions = new Map();
class FakeHTMLElement {}
const sandbox = {
  HTMLElement: FakeHTMLElement,
  customElements: {
    get: name => definitions.get(name),
    define: (name, ctor) => definitions.set(name, ctor),
  },
  window: {},
  document: {
    createElement: tag => ({ tagName: tag, textContent: '', style: {},
      getBoundingClientRect: () => ({ left: 40, top: 70, width: 80, height: 22 }) }),
  },
  navigator: { userAgent: 'Android WebView' },
  matchMedia: () => ({ matches: false }),
  performance: { now: () => now },
  console,
  Math, Number, String, Set, Map, Array, Object, Date,
};
const sourcePath = path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'speaking-orb.js');
vm.runInNewContext(fs.readFileSync(sourcePath, 'utf8'), sandbox, { filename: sourcePath, timeout: 5000 });
const SpeakingOrb = definitions.get('speaking-orb');
assert.ok(SpeakingOrb, 'the SpeakingOrb custom element must register');

function createOrb(word, reducedMotion = false, offset = 0) {
  const calls = { clear: 0, rects: [] };
  const span = {
    textContent: `${word} `,
    getBoundingClientRect: () => ({ left: 40 + offset, top: 70 + offset, width: Math.max(10, word.length * 8), height: 22 }),
  };
  const orb = new SpeakingOrb();
  orb.media = { matches: reducedMotion };
  orb.capEl = { children: [span] };
  orb.fx = { width: 420, height: 260 };
  orb.fctx = {
    globalCompositeOperation: 'source-over',
    setTransform() {},
    clearRect() { calls.clear++; },
    fillRect(x, y, w, h) { calls.rects.push([x, y, w, h, this.fillStyle]); },
    set fillStyle(value) { this._fillStyle = value; },
    get fillStyle() { return this._fillStyle; },
  };
  orb.getBoundingClientRect = () => ({ left: 0, top: 0, width: 420, height: 260 });
  orb.glyphs = text => Array.from({ length: Math.max(6, text.length * 5) }, (_, i) => [i % 20, Math.floor(i / 20) * 4]);
  return { orb, calls };
}

// 20 distinct rendered-text / layout / reduced-motion combinations.
for (let i = 0; i < 20; i++) {
  const word = ['Jarvis', 'Merhaba', 'yanıt', 'toz', 'synchronization', 'dünya'][i % 6] + (i % 2 ? '!' : '');
  const reducedMotion = i % 4 === 0;
  const { orb, calls } = createOrb(word, reducedMotion, i * 2);
  orb.dismissCaptionDust();
  assert.ok(orb.exitDust && orb.exitDust.particles.length > 0, `case ${i + 1}: visible words should become dust particles`);
  for (const p of orb.exitDust.particles) {
    for (const k of ['x', 'y', 'dx', 'dy', 'size', 'alpha', 'phase', 'seed']) {
      assert.ok(Number.isFinite(p[k]), `case ${i + 1}: particle ${k} must be finite`);
    }
  }
  now += 40;
  orb.drawExitDust();
  const firstPaint = calls.rects.at(-1);
  assert.ok(firstPaint, `case ${i + 1}: first frame should draw visible dust`);
  const firstX = firstPaint[0], firstY = firstPaint[1];
  now += 220;
  orb.drawExitDust();
  const movingPaint = calls.rects.at(-1);
  assert.ok(movingPaint, `case ${i + 1}: middle frame should continue drawing dust`);
  assert.ok(firstX !== movingPaint[0] || firstY !== movingPaint[1], `case ${i + 1}: dust particles should visibly move`);
  now += 520;
  orb.drawExitDust();
  assert.equal(orb.exitDust, null, `case ${i + 1}: dissolution must clean up after its finite lifetime`);
}

// No visible glyphs must be safe and must not leave a stale canvas animation behind.
{
  const { orb, calls } = createOrb('');
  orb.capEl.children = [];
  orb.exitDust = { startedAt: now, particles: [{ x: 1, y: 1, dx: 1, dy: 1, size: 1, alpha: 1, phase: 0, seed: 1 }] };
  orb.dismissCaptionDust();
  assert.ok(orb.exitDust, 'repeated empty-caption dismissal must not interrupt an existing dissolution');
  orb.clearCaptionDust();
  assert.equal(orb.exitDust, null, 'explicit hard cleanup must clear stale dust state');
  assert.ok(calls.clear > 0, 'explicit hard cleanup must clear the particle canvas');
}

console.log('PASS 20 varied caption-dust movement, finite-coordinate, cleanup, and reduced-motion cases');
console.log('PASS empty caption cleanup');

// Exercise the actual caption() implementation under realistic incremental text updates.
function captionHarness() {
  const { orb } = createOrb('');
  let children = [];
  const capEl = {
    dataset: { g: '' },
    classList: { toggle() {}, remove() {} },
    get children() { return children; },
    get childElementCount() { return children.length; },
    get textContent() { return children.map(child => child.textContent).join(''); },
    set textContent(value) {
      if (value === '') children = [];
      else children = [{ textContent: String(value), style: {}, getBoundingClientRect: () => ({ left: 40, top: 70, width: 80, height: 22 }) }];
    },
    append(child) { children.push(child); },
  };
  orb.capEl = capEl;
  orb.media = { matches: false };
  orb.flight = () => {};
  const dismissedTexts = [];
  orb.dismissCaptionDust = () => {
    dismissedTexts.push(capEl.textContent);
    orb.exitDust = { startedAt: now, particles: [{ x: 1, y: 2 }] };
  };
  return { orb, capEl, dismissedTexts, childCount: () => children.length };
}

const words = [
  { w: 'Jarvis.', start: 0.0, end: 0.2 },
  { w: 'İlk', start: 0.25, end: 0.42 },
  { w: 'cümle.', start: 0.5, end: 0.75 },
  { w: 'İkinci', start: 0.9, end: 1.1 },
  { w: 'cümle.', start: 1.2, end: 1.45 },
  { w: 'Üçüncü', start: 1.55, end: 1.8 },
  { w: 'cümle.', start: 1.9, end: 2.15 },
  { w: 'Dördüncü', start: 2.25, end: 2.5 },
  { w: 've', start: 2.6, end: 2.72 },
  { w: 'son', start: 2.8, end: 2.95 },
  { w: 'cümle.', start: 3.0, end: 3.25 },
];
const expectedFullText = words.map(w => w.w + ' ').join('');

// Simulate the array growing over successive streaming events. None of these updates may
// dissolve text already shown; sentence punctuation and the old eight-word grouping are irrelevant.
{
  const test = captionHarness();
  const line = { words: words.slice(0, 3), who: 'assistant', externalSync: false };
  test.orb.caption(0.6, line);
  assert.equal(test.capEl.textContent, 'Jarvis. İlk cümle. ', 'first streamed sentence remains visible');
  assert.equal(test.dismissedTexts.length, 0, 'streaming the first sentence must not start dust');

  line.words.push(...words.slice(3, 7));
  test.orb.caption(2.0, line);
  assert.equal(test.capEl.textContent, words.slice(0, 7).map(w => w.w + ' ').join(''), 'second and third sentences accumulate without removing the first');
  assert.equal(test.dismissedTexts.length, 0, 'growing the live transcript must not dissolve earlier words');

  line.words.push(...words.slice(7));
  test.orb.caption(3.1, line);
  assert.equal(test.capEl.textContent, expectedFullText, 'all sentences are on screen before final dissolution');
  assert.equal(test.dismissedTexts.length, 0, 'the full answer must stay readable while the final word is spoken');

  test.orb.caption(5.1, line);
  assert.equal(test.dismissedTexts.length, 1, 'one final dust-out begins after the last word plus the hold interval');
  assert.equal(test.dismissedTexts[0], expectedFullText, 'dust captures the complete answer, not a partial group');
  assert.equal(test.childCount(), 0, 'DOM text clears only after all words have been captured as dust');
  test.orb.caption(5.2, line);
  assert.equal(test.dismissedTexts.length, 1, 'finished line must not recreate the text or dust it repeatedly');
}

// A live AudioTrack must keep the full text visible even if the caption clock has passed the
// normal expiry threshold. Once playback actually ends, its post-playback clock permits one dust-out.
{
  const test = captionHarness();
  const line = { words: words.slice(), who: 'assistant', externalSync: true };
  test.orb.externalAudioActive = true;
  test.orb.caption(4.9, line);
  assert.equal(test.capEl.textContent, expectedFullText, 'active audio keeps the completed text visible');
  assert.equal(test.dismissedTexts.length, 0, 'no dust while speech is still playing');
  test.orb.caption(9.5, line);
  assert.equal(test.capEl.textContent, expectedFullText, 'even a long gap in caption updates cannot dissolve text before audio ends');
  assert.equal(test.dismissedTexts.length, 0, 'network or transcript gaps during active audio never trigger final dust');

  test.orb.externalAudioActive = false;
  test.orb.externalAudioEndedAt = now - 300;
  test.orb.externalAudioEndTime = 3.25;
  test.orb.caption(3.55, line);
  assert.equal(test.dismissedTexts.length, 0, 'no dust earlier than ~0.9 s after the voice really stopped');
  test.orb.externalAudioEndedAt = now - 1000;
  test.orb.caption(5.1, line);
  assert.equal(test.dismissedTexts.length, 1, 'the ended audio dissolves ~0.9 s after the voice stopped');
  assert.equal(test.dismissedTexts[0], expectedFullText, 'post-playback dust includes every completed sentence');
}

// A hidden/cleared caption still dissolves safely, but a live line must never be cleared just
// because a new streamed word changed the array length.
{
  const test = captionHarness();
  const line = { words: words.slice(0, 2), who: 'assistant', externalSync: false };
  test.orb.caption(0.4, line);
  assert.equal(test.childCount(), 2);
  test.orb.caption(0.45, line);
  assert.equal(test.dismissedTexts.length, 0, 'same line refresh does not invoke outgoing dust');
  test.orb.caption(3.0, null);
  assert.equal(test.dismissedTexts.length, 1, 'an explicit hidden-caption transition can still start dust');
}

// 20 additional end-to-end caption timelines vary response length, punctuation, and grouping.
// Each incoming word must append to the current caption with zero intermediate dust-outs.
for (let caseIndex = 0; caseIndex < 100; caseIndex++) {
  const test = captionHarness();
  const count = 3 + ((caseIndex * 7) % 23);
  const streamedWords = Array.from({ length: count }, (_, i) => ({
    w: `C${caseIndex}W${i}${i % 4 === 3 ? '.' : (i === count - 1 ? '?' : '')}`,
    start: i * 0.24,
    end: i * 0.24 + 0.18,
  }));
  const line = { words: [], who: 'assistant', externalSync: false };
  for (let i = 0; i < streamedWords.length; i++) {
    line.words.push(streamedWords[i]);
    test.orb.caption(streamedWords[i].start + 0.01, line);
    assert.equal(test.capEl.textContent, streamedWords.slice(0, i + 1).map(w => w.w + ' ').join(''),
      `stress case ${caseIndex + 1}, word ${i + 1}: all prior words stay accumulated`);
    assert.equal(test.dismissedTexts.length, 0,
      `stress case ${caseIndex + 1}, word ${i + 1}: no intermediate dust-out during streaming`);
  }
  test.orb.caption(streamedWords.at(-1).end + 1.81, line);
  assert.equal(test.dismissedTexts.length, 1, `stress case ${caseIndex + 1}: exactly one final dust-out`);
  assert.equal(test.dismissedTexts[0], streamedWords.map(w => w.w + ' ').join(''),
    `stress case ${caseIndex + 1}: final dust contains every word in order`);
}

console.log('PASS 100 incremental caption timelines: no early dust and complete final text captured');
console.log('PASS incremental stream preserves prior words and sentences until the complete answer is dusted');
console.log('PASS active audio holds all completed text; post-playback clock enables final dust-out');
console.log('PASS explicit caption hiding remains safe');
