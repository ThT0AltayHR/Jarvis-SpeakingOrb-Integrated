#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(`${__dirname}/../app/src/main/assets/orb.html`, 'utf8');
function extractFunction(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `${signature} exists`);
  let depth = 0, end = -1, opened = false;
  for (let i = start; i < source.length; i++) {
    if (source[i] === '{') { depth++; opened = true; }
    else if (source[i] === '}') { depth--; if (opened && depth === 0) { end = i + 1; break; } }
  }
  assert.ok(end > start, `${signature} can be isolated`);
  return source.slice(start, end);
}
const fn = extractFunction(html, 'function finishTextOnlyTurn() {');
function mockLine(overrides = {}) {
  const words = [{w:'Jarvis.'},{w:'Merhaba'},{w:'dünya!'}];
  return {
    externalAudioActive:false, externalAudioTime:22, externalAudioEndTime:12, externalAudioEndedAt:null,
    estimate: (text, start) => text.map((w,i)=>({w,start:start+i*0.25,end:start+i*0.25+0.18})),
    line: { externalSync:true, words, toks:words.map(x=>x.w), waitingForPlayback:true, playbackStarted:false },
    ...overrides,
  };
}
function run(orb) {
  const ctx={ orb, performance:{now:()=>5000} };
  vm.createContext(ctx);
  vm.runInContext(`${fn}; globalThis.runFallback=finishTextOnlyTurn;`,ctx);
  return ctx.runFallback();
}
{
  const orb=mockLine();
  assert.equal(run(orb),true,'text-only completed answer should start caption timeline');
  assert.equal(orb.line.waitingForPlayback,false,'caption must not wait forever for a nonexistent AudioTrack');
  assert.equal(orb.line.playbackStarted,false,'text-only remains audio-free');
  assert.equal(orb.externalAudioEndedAt,5000,'monotonic clock starts for final caption/dust timing');
  assert.equal(orb.externalAudioEndTime,0,'text-only timeline is independent of the prior audio playhead');
  assert.deepEqual(orb.line.toks,['Jarvis.','Merhaba','dünya!'],'all final words remain in the line');
}
for (const setup of [
  {externalAudioActive:true},
  {line:{externalSync:true,words:[{w:'existing'}],waitingForPlayback:true,playbackStarted:true}},
  {line:{externalSync:true,words:[{w:'existing'}],waitingForPlayback:false,playbackStarted:false}},
  {line:{externalSync:true,words:[],waitingForPlayback:true,playbackStarted:false}},
  {line:null},
]) {
  const orb=mockLine(setup);
  const before=orb.externalAudioEndedAt;
  assert.equal(run(orb),false,'real audio/already-started/empty caption paths must not use the text-only fallback');
  assert.equal(orb.externalAudioEndedAt,before,'guarded no-op must not mutate the external clock');
}
assert.match(html,/previousState === 'thinking'[\s\S]*?finishTextOnlyTurn\(\)/,'fallback is tied to a real model-state completion edge');
const service=fs.readFileSync(`${__dirname}/../app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt`,'utf8');
assert.ok(service.includes('providerTurnComplete &&') && service.includes('val hasTextOnlyReply = synchronized(currentTurnAssistantText)') && service.includes('if (hasTextOnlyReply) finalizeTurn(preserveCaptionTail = true)'), 'native service finalizes text-only output after a guarded delay');
const textCallback = service.indexOf('override fun onAssistantTextReceived(textChunk: String)');
const callbackBlock = service.slice(textCallback, textCallback + 1800);
assert.ok(callbackBlock.includes('turnCompletionIdleJob?.cancel()') && callbackBlock.includes('providerTurnComplete = false'), 'new response text cancels stale turn-finalization window');
console.log('PASS text-only caption fallback retains all words and starts a separate dust-out clock');
console.log('PASS audio-backed/already-started/empty caption cases are not re-timed');
console.log('PASS native service finalizes text-only turns without corrupting a new response');
