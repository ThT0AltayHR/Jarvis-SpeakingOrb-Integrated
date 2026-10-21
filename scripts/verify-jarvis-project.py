#!/usr/bin/env python3
"""Static release checks for the JARVIS Android project (no external dependencies)."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import struct

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/com/jarvis/assistant"
ASSETS = ROOT / "app/src/main/assets"
errors = []


def check(condition, message):
    if condition:
        print(f"PASS {message}")
    else:
        errors.append(message)
        print(f"FAIL {message}")

# Parse all XML resources to catch malformed layouts/themes/adaptive launcher descriptors.
xml_files = list((ROOT / "app/src/main").rglob("*.xml"))
for xml in xml_files:
    try:
        ET.parse(xml)
    except Exception as exc:
        errors.append(f"Malformed XML: {xml.relative_to(ROOT)}: {exc}")
check(not any("Malformed XML:" in e for e in errors), f"all {len(xml_files)} Android XML files parse")

prefs = (SRC / "data/preferences/AppPreferences.kt").read_text()
themes = (RES / "values/themes.xml").read_text()
theme_manager = (SRC / "util/ThemeManager.kt").read_text()
check('prefs.getString(KEY_APP_THEME, "dark")' in prefs, "fresh installs default to the dark theme")
check('prefs.getString(KEY_CUSTOM_THEME_COLOR, "#000000")' in prefs, "custom theme fallback is #000000")
check('parent="Theme.JarvisAssistant.Dark"' in themes and '#000000' in themes, "base Android theme and dark surface use black")
check('Color.parseColor("#000000")' in theme_manager, "window theme background uses #000000")

app = (ROOT / "app/src/main/java/com/jarvis/assistant/JarvisApp.kt").read_text()
check('VoskModelManager.init(this' not in app, "large Vosk models are not loaded during Application startup")

orb_html = (ASSETS / "orb.html").read_text()
engine = (ASSETS / "thinking-orb-engine.js").read_text()
check(len(re.findall(r"working:'|searching:'|solving:'|listening:'|connecting:'|weaving:'|composing:'|breathing:'|shaping:'", orb_html)) >= 9, "WebView maps all nine thinking animation states")
check(all(state in engine for state in ["orbits", "globe", "rubik", "wave", "web", "braid", "ribbon", "ring", "morph"]), "engine bundle includes all nine animation geometries")
check("left:50%; top:1%" in orb_html and "width:min(25vw, 96px); height:min(25vw, 96px)" in orb_html, "thinking layer occupies the reserved area above the sphere")
check("speaking-orb { display:block; position:absolute; left:14%; bottom:0; width:72%" in orb_html, "main sphere is positioned below the status layer rather than hidden by it")
check("thinkingFrameIntervalMs = isAndroidWebView ? (1000 / 30)" in orb_html, "thinking animation is frame capped on Android")
check("this.frameIntervalMs=/Android/i.test" in (ASSETS / "speaking-orb.js").read_text(), "speaking orb is frame capped on Android")

repo = (SRC / "data/repository/ChatRepository.kt").read_text()
sheet = (SRC / "ui/chat/ChatHistoryBottomSheet.kt").read_text()
formatter = (SRC / "ui/chat/ChatMessageFormatter.kt").read_text()
saver = (SRC / "ui/chat/ChatFileSaver.kt").read_text()
check("fun startNewConversation" in repo and "fun openConversation" in repo, "conversation sessions can be created and reopened")
check("chatRepo.sessions.collect" in sheet and "openConversation(session.id)" in sheet, "history sheet loads and selects saved sessions")
check("ForegroundColorSpan" in formatter and "ClickableSpan" in formatter, "fenced code blocks receive syntax colors and block-copy interaction")
check("MediaStore.Downloads.EXTERNAL_CONTENT_URI" in saver, "named code files can be saved to Downloads/Jarvis")

parser = (SRC / "automation/command/CommandParser.kt").read_text()
executor = (SRC / "automation/command/CommandExecutor.kt").read_text()
check("WEB_SEARCH_BACKGROUND_TR" in parser and "WEB_SEARCH_BROWSER" in parser, "Turkish background search is separated from explicit browser search")
check("BuiltInChromeEngine.searchAndExtract(command.query)" in executor and "SearchWebInBrowser" in executor, "background research does not require opening Chrome")
service = (SRC / "background/JarvisConversationService.kt").read_text()
home_fragment = (SRC / "ui/home/HomeFragment.kt").read_text()
check("app.chatRepository.addTurn(\"Web'de ara:" in service and "Sonuçlar Sohbet bölümüne eklendi" in service, "standalone search results are persisted to chat")
check('JarvisConversationService.webSearchState' in home_fragment and 'selectTab(MainActivity.TAB_CHAT)' in home_fragment, "search animation state is mapped and completed results are shown in chat")

icon_paths = sorted(RES.glob("mipmap-*/ic_launcher.png"))
icon_ok = bool(icon_paths)
for icon in icon_paths:
    try:
        with icon.open("rb") as f:
            header = f.read(24)
        if header[:8] != b"\x89PNG\r\n\x1a\n":
            icon_ok = False
        else:
            width, height = struct.unpack(">II", header[16:24])
            if width != height or width < 48:
                icon_ok = False
    except Exception:
        icon_ok = False
check(icon_ok and len(icon_paths) >= 5, f"new PNG launcher icon exists at {len(icon_paths)} density levels")

# Detect accidental conflict markers in hand-edited source files.
conflicts = []
for extension in ("*.kt", "*.xml", "*.js"):
    for path in ROOT.rglob(extension):
        try:
            data = path.read_text(errors="replace")
        except Exception:
            continue
        if "<<<<<<< " in data or ">>>>>>> " in data:
            conflicts.append(str(path.relative_to(ROOT)))
check(not conflicts, "no merge-conflict markers in source files")

if errors:
    print(f"\n{len(errors)} checks failed")
    sys.exit(1)
print("\nPASS all static JARVIS project checks")
