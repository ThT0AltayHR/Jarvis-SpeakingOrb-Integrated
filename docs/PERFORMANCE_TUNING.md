# JARVIS Android performance profile

This release applies small, bounded runtime settings instead of relying on a large-heap flag or disabling features wholesale.

- **Cold start:** the large Vosk speech and speaker models are no longer copied and loaded from `JarvisApp.onCreate()`. They are initialized by the voice-enrollment/wake-word path that actually needs them.
- **Speaking Orb:** Android WebView frames are capped at 30 FPS; non-Android browsers target 45 FPS. The adaptive particle controller is retained and can lower particle count when frames are slow.
- **Thinking Orbs:** Android status animations are capped at 30 drawn frames/second. The decorative canvas uses a maximum device-pixel ratio of 1.35 on Android and pauses while hidden.
- **Background work:** background web search continues on `Dispatchers.IO` and returns extracted result text; it does not need to open Chrome unless the user explicitly asks to search in the browser.
- **Conversation history:** only the most recent 10 turns are added to the text-chat context, with per-turn text limits and a 10,000-character total cap. The UI stores at most 20 conversation sessions and 100 turns per active chat.
- **Network readiness:** text input waits briefly for the Live WebSocket to become ready, instead of clearing and losing the user's message on a premature send.

The animations have been smoke-tested in Node.js and resource XML has been statically checked. A complete Android Gradle build still requires downloading Gradle 8.9, which was not available in the offline build environment, so device-level FPS and memory figures have not been claimed.
