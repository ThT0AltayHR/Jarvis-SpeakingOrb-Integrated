#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const assert = require('node:assert/strict');
const root = `${__dirname}/..`;
const source = fs.readFileSync(`${root}/app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt`, 'utf8');
const start = source.indexOf('private fun sendSetupMessage(ws: WebSocket) {');
assert.ok(start >= 0, 'Gemini Live setup function exists');
const nextFunction = source.indexOf('\n    private fun ', start + 1);
assert.ok(nextFunction > start, 'setup function boundary exists');
const setup = source.slice(start, nextFunction);
for (const field of ['systemInstruction', 'generationConfig', 'responseModalities', 'speechConfig', 'voiceConfig', 'prebuiltVoiceConfig', 'voiceName']) {
  assert.ok(setup.includes(`"${field}"`), `setup uses canonical JSON field ${field}`);
}
assert.ok(setup.includes('put("inputAudioTranscription", JSONObject())'), 'input transcript is enabled at setup root');
assert.ok(setup.includes('put("outputAudioTranscription", JSONObject())'), 'assistant speech transcript is enabled at setup root');
const genIndex = setup.indexOf('put("generationConfig"');
const inIndex = setup.indexOf('put("inputAudioTranscription"');
const outIndex = setup.indexOf('put("outputAudioTranscription"');
const toolsIndex = setup.indexOf('put("tools"');
assert.ok(genIndex < inIndex && inIndex < outIndex && outIndex < toolsIndex, 'transcription configs are top-level siblings of generationConfig/tools');
for (const wrong of ['"system_instruction"', '"generation_config"', '"response_modalities"', '"speech_config"', '"voice_name"', '"thinking_budget"']) {
  assert.ok(!setup.includes(wrong), `legacy field ${wrong} is absent from the setup payload`);
}
assert.ok(source.includes('serverContent.optJSONObject("outputTranscription")'), 'assistant transcript response is parsed');
assert.ok(source.includes('outputTranscriptionReceivedThisTurn = true') && source.includes('fallbackAssistantTranscript.append(text)'), 'modelTurn text is buffered as a fallback instead of being appended twice');
assert.ok(source.includes('GeminiTurnCompletionPolicy.isTurnComplete') && !/val isTurnDone =[^\n]*generationComplete/.test(source), 'generationComplete alone cannot finalize the turn');
assert.ok(source.includes('put("toolResponse"') && source.includes('put("functionResponses"'), 'tool responses use canonical transport field casing');
assert.ok(source.includes('put("clientContent"') && source.includes('put("turnComplete", true)'), 'text/multimodal turns use canonical transport fields');
assert.ok(source.includes('put("inlineData"') && source.includes('put("mimeType"'), 'multimodal payload parts use canonical field casing');
assert.ok(source.includes('realtimeInput\\\":{\\\"audioStreamEnd\\\":true'), 'audio stream end uses the documented realtimeInput event');
assert.ok(source.includes('serverContent.optJSONObject("inputTranscription")'), 'user transcript response is parsed');
console.log('PASS Gemini Live WebSocket setup uses canonical field names');
console.log('PASS both input/output audio-transcription configs are enabled at the correct root level');
console.log('PASS incoming inputTranscription/outputTranscription are connected to callbacks');
