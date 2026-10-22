// Hotfix 13 — runtime (not just source-text) check of the Home Orb caption + dust lifecycle.
// Runs the real speaking-orb.js and the real orb.html script inside a tiny DOM stub.
const assert = require('assert');
const fs = require('fs'), path = require('path');
const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'app/src/main/assets/orb.html'), 'utf8');
assert(!/orb\.level\s*=[^=]/.test(html), 'orb.html must never assign SpeakingOrb.level (getter-only accessor throws in strict mode)');

const { orb, step, J } = require('./orb-runtime-sim/harness.js');
const text = () => orb.capEl.children.map(c => c.textContent).join('').trim();
const audioOn = (ms) => J.setExternalAudio(0.5, 0, 0, ms, true);
const audioOff = () => J.setExternalAudio(0, 0, 0, 0, false);
function speak(sentence, perWordMs) {
  J.setState('thinking'); step(400); audioOn(0); J.setState('speaking');
  const words = sentence.split(' '); let cum = '';
  words.forEach((w, i) => { cum += (i ? ' ' : '') + w; J.appendText(cum); for (let k = 0; k < perWordMs / 100; k++) { audioOn(i * perWordMs + k * 100); step(100); } });
}

// 1) setExternalAudio must not throw and must flip the playback state
speak('Merhaba reis bugün nasılsın', 500);
assert.strictEqual(orb.externalAudioActive, true, 'external audio must become active');
assert(text().includes('Merhaba'), 'caption words must be visible while Jarvis speaks');
// 2) service clears its text while audio is still playing -> must NOT dust mid-sentence
J.clearCaption(); step(300);
assert(text().length > 0 && !orb.exitDust, 'clearCaption during playback must not dust the sentence');
// 3) voice ends -> sentence turns to dust ~0.9-2.3 s later and the DOM text is gone
audioOff(); step(500);
assert(text().length > 0, 'text should still be readable right after the last word');
let dusted = false; for (let i = 0; i < 30 && !dusted; i++) { step(100); if (orb.exitDust || text() === '') dusted = true; }
assert(dusted, 'caption must dissolve into dust after the voice ends');
step(1500); assert.strictEqual(text(), '', 'caption text must be gone after the dust-out');
// 4) transcript words that trail the audio (after the dust) still show and dust (separate turn)
step(1000);
speak('Tamam reis', 400); audioOff(); step(2500);
assert.strictEqual(text(), '', 'first caption already dusted');
J.appendText('Tamam reis geç gelen kelimeler'); step(300);
assert(text().length > 0, 'late transcript words must still be displayed');
step(4000); assert.strictEqual(text(), '', 'late words must dust out too');
// 5) second answer replaces the first one cleanly
J.clearCaption(); speak('İkinci cevap', 500);
assert(text().includes('İkinci'), 'next answer must render');
audioOff(); step(4000); assert.strictEqual(text(), '');
console.log('verify-orb-runtime-captions: PASS');
