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

// Exercise the actual caption() branches that otherwise used to remove text instantly.
function captionHarness(initialText, initialKey) {
  const { orb } = createOrb(initialText);
  let children = initialText ? [{
    textContent: `${initialText} `,
    getBoundingClientRect: () => ({ left: 40, top: 70, width: initialText.length * 8, height: 22 }),
  }] : [];
  const capEl = {
    dataset: { g: initialKey || '' },
    classList: { toggle() {} },
    get children() { return children; },
    get childElementCount() { return children.length; },
    get textContent() { return children.map(child => child.textContent).join(''); },
    set textContent(value) {
      if (value === '') children = [];
      else children = [{ textContent: String(value), getBoundingClientRect: () => ({ left: 40, top: 70, width: 80, height: 22 }) }];
    },
    append(child) { children.push(child); },
  };
  orb.capEl = capEl;
  orb.media = { matches: false };
  orb.flight = () => {};
  let dismissed = 0;
  orb.dismissCaptionDust = () => { dismissed++; };
  return { orb, capEl, dismissed: () => dismissed, childCount: () => children.length };
}
{
  const test = captionHarness('Last reply', '0:1:assistant');
  test.orb.caption(3, { words: [{ w: 'Last.', start: 0, end: 0.1 }], who: 'assistant' });
  assert.equal(test.dismissed(), 1, 'expired last caption must start outgoing dust');
  assert.equal(test.childCount(), 0, 'expired caption must clear DOM after preserving its dust particles');
}
{
  const test = captionHarness('Old group', '0:2:assistant');
  test.orb.caption(0.4, {
    words: [{ w: 'One.', start: 0, end: 0.2 }, { w: 'Two.', start: 0.3, end: 0.6 }],
    who: 'assistant',
  });
  assert.equal(test.dismissed(), 1, 'replacing a live word group must start outgoing dust');
  assert.equal(test.childCount(), 1, 'new streaming group should replace the old text after it is captured as dust');
  assert.equal(test.capEl.children[0].textContent, 'Two. ', 'new live text must still render after prior-group dissolution begins');
}
{
  const test = captionHarness('Waiting old line', '0:1:assistant');
  test.orb.caption(0, null);
  assert.equal(test.dismissed(), 1, 'hidden/cleared caption must start outgoing dust');
  assert.equal(test.childCount(), 0, 'hidden caption text should be removed after dust capture');
}
console.log('PASS actual caption expiry, word-group replacement, and hidden-caption dissolution paths');
