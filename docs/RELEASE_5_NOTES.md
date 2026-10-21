# Release 5 — package identity and UI audit

- Android application ID: `com.jarvisai.app`.
- Updated runtime package identity checks to match the application ID. The Kotlin namespace remains `com.jarvis.assistant` intentionally, because source packages/imports use that namespace; changing it alone would break generated `R`/`BuildConfig` imports.
- Thinking-orb canvas uses a transparent canvas (`alpha:true`) and transparent document/body; no separate colored panel or card background is added.
- The animation canvas is compact (up to 96 CSS px on standard layouts, 68 px on very short screens), pointer-transparent, and fades with a short 180 ms transition. It is in a separate top area and does not cover the main Speaking Orb.
- Attachment analysis is content-oriented: it sniffs file signatures, extracts bounded text from supported archives/documents, renders PDF pages, decodes images, samples video frames, and extracts short audio samples where Android codecs permit. It is not literally universal: encrypted/unsupported/corrupt formats, some codecs, very large archives, and unsupported office variants may only yield metadata or partial content.
- Long video processing samples frames/audio in quick and detailed modes rather than decoding every frame. Detailed mode can take longer; actual analysis quality also depends on the connected model's supported input types and payload limits.
- Build/APK compilation intentionally not performed per user request.
