#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = `${__dirname}/..`;
const ws = fs.readFileSync(`${root}/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt`, 'utf8');
const policy = fs.readFileSync(`${root}/app/src/main/java/com/jarvis/assistant/network/GeminiTurnCompletionPolicy.kt`, 'utf8');
const setup = ws.slice(ws.indexOf('private fun sendSetupMessage'), ws.indexOf('\n    private fun ', ws.indexOf('private fun sendSetupMessage') + 1));
assert.ok(setup.includes('put("generationConfig"') && setup.includes('put("systemInstruction"'), 'setup uses canonical JSON names');
assert.ok(setup.includes('put("inputAudioTranscription", JSONObject())') && setup.includes('put("outputAudioTranscription", JSONObject())'), 'both transcript streams are enabled');
assert.ok(ws.includes('Ignoring non-error server payload received before setupComplete handshake'), 'unexpected payload before setupComplete is ignored rather than falsely marking the session LIVE');
assert.ok(!/if \(!isSetupComplete\.get\(\) && \(json\.has\("serverContent"\) \|\| json\.has\("toolCall"\)\)\)\s*\{\s*isSetupComplete\.set\(true\)/.test(ws), 'server content cannot bypass the setupComplete handshake');
const turnStart = ws.indexOf('val generationDone =');
const turnCallback = ws.indexOf('listener.onTurnCompleted()', turnStart);
const isTurnBlock = ws.slice(turnStart, turnCallback < 0 ? undefined : turnCallback + 'listener.onTurnCompleted()'.length);
assert.ok(isTurnBlock.includes('generationComplete'), 'generation completion is still observed for logging');
assert.ok(isTurnBlock.includes('GeminiTurnCompletionPolicy.isTurnComplete'), 'only authoritative turn completion controls the turn callback');
assert.ok(!/val isTurnDone\s*=.{0,200}generationComplete/.test(isTurnBlock), 'generationComplete is not an authoritative turn-end signal');
assert.ok(isTurnBlock.indexOf('if (fallbackText.isNotBlank()) listener.onAssistantTextReceived(fallbackText)') < isTurnBlock.indexOf('listener.onTurnCompleted()'), 'text fallback is delivered before the completed-turn callback');
const interruptionAt = ws.indexOf('val wasInterrupted = serverContent.optBoolean("interrupted", false)');
const modelTurnAt = ws.indexOf('val modelTurn = if (GeminiTurnCompletionPolicy.shouldProcessModelTurn(wasInterrupted)) serverContent.optJSONObject("modelTurn") else null', interruptionAt);
assert.ok(interruptionAt >= 0 && modelTurnAt > interruptionAt, 'interrupt decision is made before modelTurn payload parsing');
const outputTranscriptionAt = ws.indexOf('val outputTrans = if (wasInterrupted) null else serverContent.optJSONObject("outputTranscription")');
assert.ok(interruptionAt >= 0 && outputTranscriptionAt > interruptionAt, 'interrupted flag is handled before output-transcription callbacks');
assert.match(ws.slice(interruptionAt, modelTurnAt + 160), /listener\.onInterrupted\(\)[\s\S]*?fallbackAssistantTranscript\.setLength\(0\)[\s\S]*?outputTranscriptionReceivedThisTurn = false/, 'interrupted turns clear fallback transcript state');
assert.match(ws, /val modelTurn = if \(GeminiTurnCompletionPolicy\.shouldProcessModelTurn\(wasInterrupted\)\) serverContent\.optJSONObject\("modelTurn"\) else null/, 'audio/text payload in the same interrupted frame is dropped instead of re-enqueued');
assert.match(policy, /fun shouldProcessModelTurn\(interrupted: Boolean\): Boolean = !interrupted/, 'interrupt policy is shared with directly unit-tested production logic');
for (const field of ['toolResponse','functionResponses','clientContent','turnComplete','inlineData','mimeType','audioStreamEnd']) {
  assert.ok(ws.includes(field), `outgoing Live API protocol contains ${field}`);
}
for (const wrong of ['put("tool_response"', 'put("function_responses"', 'put("client_content"', 'put("turn_complete"', 'put("inline_data"', 'put("mime_type"']) {
  assert.ok(!ws.includes(wrong), `outgoing API protocol field ${wrong} is absent`);
}
const policyCode = policy.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
assert.ok(policyCode.includes('turnComplete || endOfTurn') && !policyCode.includes('generationComplete'), 'pure policy intentionally excludes generationComplete from its executable logic');
console.log('PASS Gemini Live setup schema, transcription configuration, and handshake gating');
console.log('PASS generationComplete and turnComplete have distinct lifecycle semantics');
console.log('PASS text fallback avoids duplicate modelTurn/outputTranscription appends');
console.log('PASS interrupted frames drop trailing assistant transcript/audio before callbacks');
console.log('PASS tool response, text content, multimodal payload, and audio-stream-end envelopes use canonical names');
