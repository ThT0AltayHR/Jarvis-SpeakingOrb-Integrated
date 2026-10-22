#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');

const root = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(root, 'app/src/main/assets/orb.html'), 'utf8');
const start = html.indexOf('      const appendText = value => {');
const end = html.indexOf('\n      };\n\n      window.JarvisOrb = {', start);
assert.ok(start >= 0 && end > start, 'must find the actual appendText implementation in orb.html');
const appendTextDeclaration = html.slice(start, end + '\n      };'.length);
const factory = String.raw`(() => {
  let currentText = '';
  const safeText = value => String(value == null ? '' : value).replace(/\s+/g, ' ').trim();
  const stateChanges = [];
  let clearCount = 0;
  let orb;
  const clearText = () => { currentText = ''; clearCount++; };
  const setPrimaryState = value => stateChanges.push(value);
  orb = {
    line: null,
    externalAudioActive: false,
    externalAudioEndedAt: null,
    externalAudioTime: 0,
    time: 0,
    capEl: { textContent: '', dataset: {} },
    dismissCaptionDust() { this.dustRequests = (this.dustRequests || 0) + 1; },
    estimate(words, startAt) {
      return words.map((w, i) => ({ w, start: startAt + i * 0.2, end: startAt + i * 0.2 + 0.16 }));
    }
  };
  ${appendTextDeclaration}
  return { appendText, getText: () => currentText, getLine: () => orb.line, orb, stateChanges, getClearCount: () => clearCount };
})()`;
const api = vm.runInNewContext(factory, { Number, String, Math }, { timeout: 5000 });
const words = () => Array.from(api.getLine().words, word => String(word.w));

api.appendText('Merha');
assert.deepEqual(words(), ['Merha']);
const firstLine = api.getLine();
api.appendText('Merhaba dünya');
assert.deepEqual(words(), ['Merhaba', 'dünya'], 'suffix + additional words must not split the partial word');
assert.equal(api.getLine(), firstLine, 'incremental text updates must retain the same live caption line');
api.appendText('Merhaba dünya! Yeni');
assert.deepEqual(words(), ['Merhaba', 'dünya!', 'Yeni'], 'punctuation attaches to the preceding word and the next word is added');
api.appendText('Merhaba dünya! Yeni cümle');
assert.deepEqual(words(), ['Merhaba', 'dünya!', 'Yeni', 'cümle'], 'all subsequent words accumulate in order');

api.appendText('');
api.appendText('İkinci yanıt başlıyor');
assert.deepEqual(words(), ['İkinci', 'yanıt', 'başlıyor'], 'a fresh answer must get a fresh caption line');
assert.notEqual(api.getLine(), firstLine, 'the next answer must not reuse the previous line object');
console.log('PASS real orb.html appendText handles split words, punctuation, cumulative multi-sentence text and new turns');
