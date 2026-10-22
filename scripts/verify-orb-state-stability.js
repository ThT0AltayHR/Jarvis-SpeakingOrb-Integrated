#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const file = `${__dirname}/../app/src/main/assets/orb.html`;
const html = fs.readFileSync(file, 'utf8');
const start = html.indexOf('function setPrimaryState(value) {');
assert.ok(start >= 0, 'setPrimaryState exists');
let depth = 0, end = -1, opened = false;
for (let i = start; i < html.length; i++) {
  if (html[i] === '{') { depth++; opened = true; }
  else if (html[i] === '}') {
    depth--;
    if (opened && depth === 0) { end = i + 1; break; }
  }
}
assert.ok(end > start, 'setPrimaryState function can be isolated');
let refreshCount = 0;
const syncStart = html.indexOf('function syncOrbVisualState() {');
assert.ok(syncStart >= 0, 'syncOrbVisualState exists');
function extractFunction(source, functionStart) {
  let depth = 0, functionEnd = -1, opened = false;
  for (let i = functionStart; i < source.length; i++) {
    if (source[i] === '{') { depth++; opened = true; }
    else if (source[i] === '}') {
      depth--;
      if (opened && depth === 0) { functionEnd = i + 1; break; }
    }
  }
  assert.ok(functionEnd > functionStart, 'function source can be isolated');
  return source.slice(functionStart, functionEnd);
}
const syncSource = extractFunction(html, syncStart);
const context = {
  mapState: value => ({idle:'done', ready:'done', off:'done', listening:'listening', thinking:'thinking', searching:'searching', speaking:'speaking', done:'done'}[String(value || '').toLowerCase()] || 'listening'),
  primaryState: 'done', taskState: 'idle', taskIsActive: false, taskAnimation: null,
  completionToken: 0, forcedAnimation: 'working',
  terminalTaskStates: new Set(['completed','failed','blocked','cancelled']),
  orb: { state: 'done' },
  scheduleTerminalClear() {},
  refreshThinkingVisibility() { refreshCount++; context.syncOrbVisualState(); }
};
vm.createContext(context);
vm.runInContext(`${syncSource}; ${html.slice(start, end)}; globalThis.callSetPrimaryState = setPrimaryState;`, context);
assert.equal(context.callSetPrimaryState('idle'), false, 'idle -> idle is a no-op');
assert.equal(context.primaryState, 'done');
assert.equal(context.forcedAnimation, 'working', 'duplicate state keeps existing animation');
assert.equal(context.callSetPrimaryState('speaking'), true, 'state edge is applied');
assert.equal(context.primaryState, 'speaking');
assert.equal(context.orb.state, 'speaking', 'real audio state overrides task animation');
const stateRefreshCount = refreshCount;
context.forcedAnimation = 'searching';
assert.equal(context.callSetPrimaryState('speaking'), false, 'speaking -> speaking is a no-op');
assert.equal(context.forcedAnimation, 'searching', 'duplicate speaking does not cancel an active animation');
assert.equal(refreshCount, stateRefreshCount, 'duplicate state does not refresh/restart the Orb');
assert.equal(context.callSetPrimaryState('thinking'), true, 'real status edge is applied');
assert.equal(context.orb.state, 'thinking', 'real thinking edge updates the visible sphere');
console.log('PASS Orb state stability: duplicates are idempotent, real status changes still apply');

const taskStart = html.indexOf('function setTaskState(value) {');
assert.ok(taskStart >= 0, 'setTaskState exists');
depth = 0; end = -1; opened = false;
for (let i = taskStart; i < html.length; i++) {
  if (html[i] === '{') { depth++; opened = true; }
  else if (html[i] === '}') {
    depth--;
    if (opened && depth === 0) { end = i + 1; break; }
  }
}
assert.ok(end > taskStart, 'setTaskState function can be isolated');
let taskRefreshCount = 0, taskClearCount = 0, taskScheduleCount = 0;
const taskContext = {
  taskState: 'idle', taskIsActive: false, taskAnimation: null, completionToken: 0,
  terminalTaskStates: new Set(['completed','failed','blocked','cancelled']),
  taskAnimations: {thinking:'solving', searching:'searching', working:'working'},
  clearTerminalTimer() { taskClearCount++; },
  refreshThinkingVisibility() { taskRefreshCount++; },
  scheduleTerminalClear() { taskScheduleCount++; }
};
vm.createContext(taskContext);
vm.runInContext(`${html.slice(taskStart, end)}; globalThis.callSetTaskState = setTaskState;`, taskContext);
assert.equal(taskContext.callSetTaskState('thinking'), true, 'task state edge is applied');
const taskRefreshCountBefore = taskRefreshCount;
const taskCompletionToken = taskContext.completionToken;
const taskClearCountBefore = taskClearCount;
assert.equal(taskContext.callSetTaskState('thinking'), false, 'duplicate task state is ignored');
assert.equal(taskRefreshCount, taskRefreshCountBefore, 'duplicate task state cannot refresh/restart animation');
assert.equal(taskContext.completionToken, taskCompletionToken, 'duplicate task state cannot replace completion token');
assert.equal(taskClearCount, taskClearCountBefore, 'duplicate task state cannot restart terminal timer');
assert.equal(taskContext.callSetTaskState('completed'), true, 'task terminal edge is applied');
console.log('PASS task-state stability: duplicate task updates are no-ops');
context.primaryState = 'thinking'; context.taskIsActive = true; context.taskAnimation = 'searching'; context.syncOrbVisualState();
assert.equal(context.orb.state, 'searching', 'active search task is reflected by the visible main Orb');
context.primaryState = 'speaking'; context.syncOrbVisualState();
assert.equal(context.orb.state, 'speaking', 'active playback always overrides task animation');
console.log('PASS visible Orb task mapping: search changes the state; playback has priority');

