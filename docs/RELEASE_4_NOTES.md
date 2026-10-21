# JARVIS release 4 — integration notes

## UI and theme
- Fresh installs now use the `dark` theme with `#000000` as the theme/window background.
- Replaced launcher icons at five Android density levels with a new rounded-square JARVIS PNG mark; adaptive launcher resources still use PNG, not SVG.
- The first time a named user opens JARVIS after voice profile setup, a Turkish TextToSpeech welcome is spoken once. It is delayed slightly to avoid adding extra work directly to the initial frame.

## Thinking and speaking animation
- All nine source animation geometries and all nine activity-state mappings remain in the local WebView engine.
- The Thinking Orb is now placed in a reserved upper status area; the Speaking Orb stays below it instead of being obscured by a full-size overlay.
- Android canvas resolution and frame rate are capped to reduce GPU load; hidden canvases stop drawing.
- Background web search exposes an `OBSERVING`/`COMPLETED`/`FAILED` task state so the search animation tracks the task.

## Chat
- Chat history now stores multiple local conversation sessions; the header menu opens history, and the plus button starts a new session without deleting archived sessions.
- Text sends wait for the Live WebSocket to be ready, keep unsent text in the input, and pass the selected session's recent context to the model.
- Markdown code fences render in monospace with token colours and can be copied by tapping the block.
- Fenced responses marked `filename=...` render a file card with Copy and Save actions. Android 10+ writes to `Downloads/Jarvis`; older Android versions use the app-specific downloads folder.

## Web search and persona
- Turkish background searches (for example, “web'de ara …”) use the background extractor and do not open Chrome. Explicit Chrome/phone-browser requests still open the browser.
- Search results are persisted into chat; from Home, JARVIS opens Chat when background results are ready.
- The shared persona prompt makes JARVIS's identity and tone consistent, distinguishes voice from text-chat output, encourages source-backed search results, and requests complete named code files when appropriate.

## Verification limitations
- `scripts/verify-thinking-orbs.js`: passed all nine animation drawing smoke tests and local WebView bridge checks.
- `scripts/verify-jarvis-project.py`: passed resource XML, theme defaults, icon density, chat-history/file-card, web-search routing, and animation-layer checks.
- `node --check`: passed for the two main JavaScript bundles and the inline WebView controller.
- A complete `assembleDebug` APK build could not be verified in the build environment because the Gradle 8.9 distribution was not available offline. Do not treat the static checks as a substitute for an on-device build and runtime test.
