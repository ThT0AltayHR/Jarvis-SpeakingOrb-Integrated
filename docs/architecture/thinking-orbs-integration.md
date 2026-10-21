# Thinking Orbs integration in JARVIS

The `thinking-orbs` canvas engine is bundled locally as `app/src/main/assets/thinking-orb-engine.js` and rendered in the existing `orb.html` WebView. It does not add a React, npm, remote-host, or network dependency to the Android app. The original Speaking Orb remains the default for idle and spoken-response states; the Thinking Orb canvas cross-fades in during thinking/searching and active task progress, including tasks started while the base orb is otherwise idle or listening.

## State mapping

| JARVIS task state | Thinking animation |
| --- | --- |
| `UNDERSTANDING` | `breathing` |
| `PLANNING` | `composing` |
| `OBSERVING` | `searching` |
| `RESOLVING_TARGET` | `searching` |
| `ACTING` | `working` |
| `VERIFYING` | `solving` |
| `CONTINUING` | `weaving` |
| `REPLANNING` / `RECOVERING` | `shaping` |
| `WAITING_FOR_UI` | `connecting` |
| `PAUSED` | `breathing` |

A completed task briefly displays the `solving` completion state before returning to the regular JARVIS orb. The canvas honours reduced-motion preferences, pauses while the WebView document is hidden, uses a device-pixel-ratio cap of 2, and is resized with its parent. While the overlay is active, the hidden 6,000-particle Speaking Orb render loop is suspended and resumed when the base orb returns.

## WebView bridge

`window.JarvisOrb.setState(state)` retains the existing JARVIS state API. The additions are:

- `window.JarvisOrb.setTaskState(state)` to reflect `AgentTaskEngine` progress.
- `window.JarvisOrb.setThinkingAnimation(state)` to explicitly display one of `working`, `searching`, `solving`, `listening`, `connecting`, `weaving`, `composing`, `breathing`, or `shaping`.
- `window.JarvisOrb.clearThinkingAnimation()` to clear an explicitly selected animation.

The upstream MIT notice is included in `app/src/main/assets/thinking-orbs-LICENSE.txt`.


## Local smoke test

With Node.js installed, run `node scripts/verify-thinking-orbs.js` from the project root. The dependency-free check renders all nine states into a recording canvas context and checks for valid presets, finite drawing coordinates, local script references, and the WebView bridge. This is a JavaScript smoke test; it does not replace a full Android/Gradle build on a machine with the Android SDK and Gradle distribution available.
