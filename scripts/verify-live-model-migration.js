const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
function ok(condition, message) {
  if (!condition) { console.error('FAIL ' + message); process.exitCode = 1; }
  else console.log('PASS ' + message);
}
const models = read('app/src/main/java/com/jarvis/assistant/data/model/GeminiLiveModels.kt');
const ws = read('app/src/main/java/com/jarvis/assistant/network/GeminiLiveWebSocket.kt');
const native = read('app/src/main/java/com/jarvis/assistant/ui/settings/ApiConnectionActivity.kt');
const xml = read('app/src/main/res/layout/activity_api_connection.xml');
const html = read('app/src/main/assets/settings/settings.html');
const gradle = read('app/build.gradle.kts');
const apiActivity = read('app/src/main/java/com/jarvis/assistant/ui/settings/ApiConnectionActivity.kt');
ok(models.includes('const val DEFAULT_MODEL = MODEL_3_8_LIVE'), 'fresh installs default to stable Gemini 3.8 Live');
ok(/val SUPPORTED_MODELS\s*=\s*listOf\(MODEL_3_8_LIVE\)/.test(models), 'selectable model allow-list contains only a supported Live API model');
ok(!models.includes('MODEL_3_1_FLASH_AGENT'), 'unsupported pseudo-agent model removed');
ok(/model\.takeIf\s*\{\s*it in GeminiConstants\.SUPPORTED_MODELS\s*\}/.test(ws), 'WebSocket enforces the supported model allow-list');
ok(!ws.includes('thinkingBudget') && !ws.includes('thinking_budget'), 'removed unsupported 2.5-only thinking configuration');
ok(native.includes('val selectedModel = GeminiConstants.DEFAULT_MODEL'), 'native settings persist only the supported model');
ok(xml.includes('Gemini 3.8 Live (Stable') && !xml.includes('rbModelFlash15') && !xml.includes('rbModelFlash20'), 'native picker has no fake or unsupported radio options');
const modelSelectBlock = (html.match(/<select[^>]*id="modelSelect"[\s\S]*?<\/select>/) || [""])[0];
const options = [...modelSelectBlock.matchAll(/<option\s+value="([^"]+)"/g)].map(m => m[1]);
ok(options.length === 1 && options[0] === 'models/gemini-3.8-live', 'Web settings expose one supported model option');
ok(html.includes("if (!modelSelect.value) modelSelect.value = 'models/gemini-3.8-live'"), 'retired saved model values safely migrate to stable default');
ok(!xml.includes('etCustomEndpoint') && !apiActivity.includes('binding.etCustomEndpoint') && apiActivity.includes('preferences.customEndpoint = \"\"'), 'inactive custom endpoint control removed and stale saved value cleared');
ok(apiActivity.includes('API KEY CHECK PASSED • HTTP REACHABLE') && !apiActivity.includes('CONNECTED • HANDSHAKE OK'), 'API key test no longer falsely claims a Live WebSocket handshake');
ok(/versionCode\s*=\s*161/.test(gradle) && /versionName\s*=\s*"1\.8\.25"/.test(gradle), 'release version bumped to 1.8.25 / 161');
if (process.exitCode) process.exit(process.exitCode);
