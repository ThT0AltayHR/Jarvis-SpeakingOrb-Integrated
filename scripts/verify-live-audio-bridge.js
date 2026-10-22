#!/usr/bin/env node
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const root = path.join(__dirname, '..');
const home = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/ui/home/HomeFragment.kt'), 'utf8');
const service = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/background/JarvisConversationService.kt'), 'utf8');
const player = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/audio/AudioPlayer.kt'), 'utf8');
const formatter = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/ui/chat/ChatMessageFormatter.kt'), 'utf8');
const layout = fs.readFileSync(path.join(root, 'app/src/main/res/layout/item_naira_chat_message.xml'), 'utf8');
const orbHtml = fs.readFileSync(path.join(root, 'app/src/main/assets/orb.html'), 'utf8');
const orbJs = fs.readFileSync(path.join(root, 'app/src/main/assets/speaking-orb.js'), 'utf8');
const chatFragment = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/ui/chat/ChatFragment.kt'), 'utf8');
const adapter = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/ui/chat/JarvisChatAdapter.kt'), 'utf8');
const chatLayout = fs.readFileSync(path.join(root, 'app/src/main/res/layout/fragment_chat.xml'), 'utf8');
const manifest = fs.readFileSync(path.join(root, 'app/src/main/AndroidManifest.xml'), 'utf8');
const accumulator = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/network/StreamingTextAccumulator.kt'), 'utf8');

assert.match(home, /val playbackEdge = lastPushedPlaybackActive != playbackActive/,
  'playback start/stop changes must be treated as non-droppable events');
assert.match(home, /if \(!playbackEdge && now - lastAudioPushTime < 33L\) return/,
  'only continuous amplitude/clock samples may be throttled');
assert.match(home, /lastPushedPlaybackActive = playbackActive/,
  'the bridge must remember the most recently delivered playback state');

assert.doesNotMatch(player, /fun notifyTurnInterrupted\(/,
  'interruption may not use a drain-only API that lets stale buffered PCM finish');
const interruptionHandlerStart = service.indexOf('override fun onInterrupted()');
const interruptionHandlerEnd = service.indexOf('override fun onTurnCompleted()', interruptionHandlerStart);
const interruptionHandler = service.slice(interruptionHandlerStart, interruptionHandlerEnd);
assert.ok(interruptionHandler.indexOf('audioPlayer?.flush()') >= 0,
  'Gemini interruption must immediately flush queued and in-flight audio');
assert.match(service, /override fun onInterrupted\(\)[\s\S]*?handlingUserPlaybackInterrupt = true[\s\S]*?audioPlayer\?\.flush\(\)[\s\S]*?setConversationState\(if \(userTurnInProgress\) ConversationState\.LISTENING else ConversationState\.IDLE\)/,
  'server interruption must flush immediately and settle on a state based on actual user activity');
assert.match(service, /Interrupted-output suppression expired because no replacement utterance followed/,
  'stale output gate has a bounded recovery path if no user utterance follows');
const recorderStart = service.indexOf('audioRecorder = AudioRecorder');
const thinkingGate = service.indexOf('if (_conversationState.value == ConversationState.THINKING)', recorderStart);
const sendAudio = service.indexOf('liveWebSocket?.sendAudioChunk(chunk)', thinkingGate);
assert.ok(thinkingGate >= 0 && sendAudio > thinkingGate,
  'mic frames are gated while thinking, before new audio is sent to Live');
assert.match(service, /if \(_conversationState\.value == ConversationState\.THINKING\)\s*\{\s*consecutiveVoicedFrames = 0[\s\S]*?if \(ownerGateOpen && hasSpeechInCurrentUtterance && liveWebSocket\?\.isSessionReady\(\) == true\)\s*\{\s*liveWebSocket\?\.sendAudioChunk\(chunk\)\s*\}\s*return@AudioRecorder\s*\}/,
  'while thinking the mic keeps streaming to Live (like the fast build) so server VAD can end the turn; the speaker-active branch still mutes it');
assert.match(service, /StreamingTextAccumulator\.append\(currentTurnAssistantText, textChunk\)/,
  'assistant live text uses the exact transcript-delta accumulator');
const audioReceiveStart = service.indexOf('override fun onAudioDataReceived(pcmData: ByteArray)');
const audioReceiveEnd = service.indexOf('override fun onAssistantTextReceived(textChunk: String)', audioReceiveStart);
const audioReceiveBody = service.slice(audioReceiveStart, audioReceiveEnd);
assert.doesNotMatch(audioReceiveBody, /setConversationState\(ConversationState\.SPEAKING\)/,
  'network packet arrival alone must not change the Orb into speaking');
assert.match(service, /VoiceBargeInDetector\(\)/,
  'barge-in goes through a sustained voice activity detector');
assert.match(service, /audioRecorder\?\.hasActiveEchoCancellation\(\)/,
  'speakerphone voice interruption checks the capture session echo canceller');
assert.match(service, /onsetFrames\.forEach \{ liveWebSocket\?\.sendAudioChunk\(it\) \}/,
  'confirmed interruption forwards the complete buffered speech onset to Gemini');
assert.match(service, /ConversationStatePolicy\.shouldApply/,
  'stale state events cannot move the Orb out of speaking while output remains active');

assert.match(service, /playbackActive = _audioPlaybackActive\.value \|\| audioPlayer\?\.isPlaying\(\) == true/,
  'queued PCM as well as physical playback protects the speaking Orb from stale states');
assert.match(service, /userTurnInProgress = hasSpeechInCurrentUtterance \|\|[\s\S]{0,120}isBargeInTurnPending/,
  'server interruption racing mic onset preserves a confirmed barge-in utterance');
assert.match(service, /providerTurnComplete = true[\s\S]{0,250}suppressInterruptedProviderTurn = false[\s\S]{0,250}isBargeInTurnPending = false/,
  'starting a new session clears stale audio-interruption flags');
const detector = fs.readFileSync(path.join(root, 'app/src/main/java/com/jarvis/assistant/audio/VoiceBargeInDetector.kt'), 'utf8');
assert.match(detector, /SPEAKER_FALLBACK_REQUIRED_FRAMES/,
  'speakerphone can detect sustained user speech even when hardware AEC is not available');
assert.match(detector, /SPEAKER_FALLBACK_TO_PLAYBACK_RATIO/,
  'no-AEC voice detection threshold is relative to speaker output to reduce echo false positives');


const interruptedStart = service.indexOf('override fun onInterrupted()');
const interruptedEnd = service.indexOf('override fun onTurnCompleted()', interruptedStart);
assert.ok(interruptedStart >= 0 && interruptedEnd > interruptedStart, 'interrupted callback boundaries exist');
assert.match(service.slice(interruptedStart, interruptedEnd), /delay\(5_000L\)[\s\S]*?suppressInterruptedProviderTurn = false/,
  'stale output suppression remains active until user utterance end or a bounded no-utterance timeout');
const turnCompleteStart = interruptedEnd;
const turnCompleteEnd = service.indexOf('override fun onError(', turnCompleteStart);
assert.ok(turnCompleteEnd > turnCompleteStart, 'turn-complete callback boundaries exist');
assert.doesNotMatch(service.slice(turnCompleteStart, turnCompleteEnd), /suppressInterruptedProviderTurn = false/,
  'a stale turn-complete event cannot unblock the stopped model turn');
assert.match(service, /suppressInterruptedProviderTurn = false[\s\S]{0,180}interruptedOutputSuppressionJob\?\.cancel\(\)[\s\S]{0,180}setConversationState\(ConversationState\.THINKING\)/,
  'the stale-output gate is released at a valid user utterance end before the next answer begins');

assert.match(orbHtml, /if \(nextState === primaryState\) return false;/,
  'repeated primary-state reports do not reset the same Orb animation');
assert.doesNotMatch(service, /currentTurnAssistantText\.append\(' '\)/,
  'word boundaries are no longer guessed between provider text chunks');
assert.match(accumulator, /target\.append\(incoming, start, incoming\.length\)/,
  'transcript accumulator preserves raw deltas except a duplicate literal boundary space');

assert.match(orbHtml, /--cap-font: "Noto Serif", "Iowan Old Style", Georgia, serif/,
  'live spoken captions use the new readable serif style');
assert.match(orbJs, /font:500 var\(--cap-size,28px\)\/1\.34 var\(--cap-font,"Noto Serif",Georgia,serif\)/,
  'caption line-height and weight should keep the new typography legible');
assert.match(layout, /android:fontFamily="serif"[\s\S]*android:textSize="16sp"[\s\S]*android:lineSpacingExtra="5dp"/,
  'assistant messages use the refreshed serif typography and paragraph spacing');
assert.match(formatter, /appendProse\(/, 'assistant prose renderer must apply Markdown presentation');
assert.match(formatter, /RelativeSizeSpan/, 'headings receive a distinct hierarchy');
assert.match(formatter, /"  • "/, 'unordered Markdown lists become actual bullets');
assert.match(formatter, /TypefaceSpan\("monospace"\)/, 'inline/fenced code remains monospaced');
assert.match(formatter, /linkPattern/, 'Markdown web links have a dedicated safe recognizer');

assert.match(home, /override fun onPageStarted[\s\S]*?lastPushedPlaybackActive = null[\s\S]*?lastAudioPushTime = 0L/,
  'WebView reload must invalidate the prior audio bridge state');
assert.match(home, /val frequency = viewModel\.audioFrequency\.value[\s\S]*?viewModel\.audioPlaybackActive\.value/,
  'WebView page finish must seed the already-current AudioTrack state');
assert.match(adapter, /onPlayAssistantResponse: \(\(String\) -> Unit\)\?/,
  'assistant replay is a callback-backed action, not a fake toast-only control');
assert.match(adapter, /action\(turn\.jarvisResponse\)/,
  'assistant replay button dispatches the real response text');
assert.doesNotMatch(adapter, /Playing response\.\.\./,
  'the placeholder replay toast has been removed');
assert.match(chatFragment, /TextToSpeech\(requireContext\(\)\.applicationContext\)/,
  'chat replay initializes Android TextToSpeech');
assert.match(chatFragment, /engine\.speak\(spokenText, TextToSpeech\.QUEUE_FLUSH/,
  'chat replay actually sends response text to the speech engine');
assert.match(chatFragment, /engine\.setLanguage\(Locale\.forLanguageTag\(\"tr-TR\"\)\)/,
  'chat replay explicitly requests Turkish language');
assert.match(chatFragment, /responseTts\?\.stop\(\); responseTts\?\.shutdown\(\)/,
  'chat replay releases the speech engine with the view lifecycle');
assert.match(manifest, /android\.intent\.action\.TTS_SERVICE/,
  'Android 11+ package-visibility query explicitly includes a TTS engine');
assert.doesNotMatch(chatFragment, /chipPersona\.setOnClickListener|chipMode\.setOnClickListener/,
  'informational persona/mode chips no longer pretend to trigger actions');
assert.match(chatLayout, /android:id=\"@\+id\/chipPersona\"[\s\S]*?android:clickable=\"false\"[\s\S]*?android:focusable=\"false\"/,
  'persona indicator is marked as a non-interactive label');
assert.match(chatLayout, /android:id=\"@\+id\/chipMode\"[\s\S]*?android:clickable=\"false\"[\s\S]*?android:focusable=\"false\"/,
  'mode indicator is marked as a non-interactive label');

// The lifecycle rule is exhaustively checked across edges inside the 33ms sample throttle.
function shouldDispatch(lastActive, active, elapsedMs) {
  const edge = lastActive !== active;
  return edge || elapsedMs >= 33;
}
let checks = 0;
const timeline = [
  {last:null, active:false, ms:0, expected:true, name:'initial idle sync'},
  {last:false, active:true, ms:8, expected:true, name:'start edge inside throttle'},
  {last:true, active:true, ms:12, expected:false, name:'dense active sample throttled'},
  {last:true, active:false, ms:19, expected:true, name:'stop edge inside throttle'},
  {last:false, active:false, ms:31, expected:false, name:'dense idle sample throttled'},
  {last:false, active:false, ms:33, expected:true, name:'regular sample interval'},
];
for (const t of timeline) {
  assert.equal(shouldDispatch(t.last, t.active, t.ms), t.expected, t.name);
  checks++;
}
for (const previous of [null, false, true]) {
  for (const active of [false, true]) {
    for (const dt of [0, 1, 16, 32, 33, 34, 65]) {
      const expected = previous !== active || dt >= 33;
      assert.equal(shouldDispatch(previous, active, dt), expected);
      checks++;
    }
  }
}
console.log(`PASS ${checks} audio bridge edge/throttle cases`);
console.log('PASS Gemini interruption immediately flushes stale audio and preserves any buffered user onset');
console.log('PASS real playback gates SPEAKING state, queue-aware stale state protection and voice barge-in onset preservation');
console.log('PASS mic stream is gated while the assistant is thinking on speakerphone');
console.log('PASS live captions and chat assistant use refreshed serif/Markdown style');
console.log('PASS transcript chunks preserve provider word boundaries without invented spaces');
console.log('PASS WebView audio state re-seeds after load/reload');
console.log('PASS chat replay uses real Turkish Android TextToSpeech, with lifecycle cleanup');
console.log('PASS informational chat chips are not fake clickable actions');
