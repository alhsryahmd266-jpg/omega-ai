#!/usr/bin/env bash
# Runs on the CI runner while an Android emulator is up (see preview_keyboard.yml). Installs the debug APK, switches the phone
# to the keyboard and takes screenshots of it in several looks. Every step may fail: whatever was captured is published.
set +e
PKG=com.omega.keyboard
IME=$PKG/.ime.OmegaImeService
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${OUT:-preview-out}"
mkdir -p "$OUT"
KB="python3 $HERE/kb.py"
LOG="$OUT/log.txt"
: > "$LOG"
say() { echo "[$(date +%T)] $*" | tee -a "$LOG"; }

adb wait-for-device
say "device: $(adb shell getprop ro.product.model | tr -d '\r') api $(adb shell getprop ro.build.version.sdk | tr -d '\r') $(adb shell wm size | tr -d '\r' | tail -1) $(adb shell wm density | tr -d '\r' | tail -1)"
adb install -r -t "$APK" 2>&1 | tee -a "$LOG"
adb shell settings put secure show_ime_with_hard_keyboard 1
# the system learns about a freshly installed keyboard a little later than the install finishes
for i in $(seq 1 30); do
  if adb shell ime list -a -s | tr -d '\r' | grep -qx "$IME"; then say "keyboard known to the system after $i tries"; break; fi
  sleep 2
done
adb shell ime list -a -s 2>&1 | tr -d '\r' >> "$LOG"
adb shell ime enable "$IME" 2>&1 | tee -a "$LOG"
adb shell ime set "$IME" 2>&1 | tee -a "$LOG"
say "default keyboard: $(adb shell settings get secure default_input_method | tr -d '\r')"
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>&1 | tee -a "$LOG"
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_HOME

shot() { adb exec-out screencap -p > "$OUT/$1.png"; say "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"; }

# the app starts with exactly these settings: scenario <name> <kind:key=value>...
scenario() {
  local name="$1"; shift
  say "== $name"
  adb shell am force-stop $PKG
  $KB prefs "$RUNNER_TEMP/omega.xml" "$@"
  adb push "$RUNNER_TEMP/omega.xml" /data/local/tmp/omega.xml > /dev/null
  adb shell chmod 644 /data/local/tmp/omega.xml
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/omega.xml shared_prefs/omega.xml'" 2>&1 | tee -a "$LOG"
  adb shell am start -W -n $PKG/.ui.MainActivity > /dev/null
  sleep 2.5
}

focus() { $KB field 2>&1 | tee -a "$LOG"; sleep 2.5; }

# keyboard in a look, nothing typed
look() { scenario "$@"; focus; $KB dump >> "$LOG" 2>&1; shot "$1"; }

# keyboard in a look with some text typed: typed <name> <text> <prefs...>
typed() {
  local name="$1" text="$2"; shift 2
  scenario "$name" "$@"; focus
  $KB type "$text" 2>&1 | tee -a "$LOG"
  sleep 1.2
  shot "$name"
}

# --- the app's own screens
scenario app-main s:theme=light
shot app-main
adb shell am start -W -n $PKG/.ui.ModelActivity 2>&1 | tail -3 >> "$LOG"; sleep 2.5; shot app-model
adb shell am start -W -n $PKG/.ui.SettingsActivity 2>&1 | tail -3 >> "$LOG"; sleep 2.5; shot app-settings

# --- the key grid, Arabic and English
look kb-ar-light     s:theme=light s:last_lang=ar
typed kb-ar-dark   "مرحبا بكم" s:theme=dark s:last_lang=ar
typed kb-en-light  "Hello world" s:theme=light s:last_lang=en
typed kb-en-red    "hello omega" s:theme=dark s:last_lang=en i:text_color=0xFFFF1744
typed kb-aurora    "neon text" s:theme=aurora s:last_lang=en i:text_color=0xFF39FF14
typed kb-neon      "glow" s:theme=neon s:last_lang=en i:text_color=0xFFFF2BD6
typed kb-rainbow   "rainbow" s:theme=amoled s:last_lang=en b:text_rainbow=true
typed kb-highlight "marker" s:theme=light s:last_lang=en b:text_highlight=true i:text_color=0xFFFFEA00
look kb-auto         s:theme=auto s:last_lang=en
look kb-ocean        s:theme=ocean s:last_lang=ar
look kb-sunset       s:theme=sunset s:last_lang=en
look kb-rose         s:theme=rose s:last_lang=en
look kb-forest       s:theme=forest s:last_lang=ar
look kb-gold         s:theme=gold s:last_lang=en
look kb-mint         s:theme=mint s:last_lang=en

# --- the appearance sheet
scenario sheet s:theme=aurora s:last_lang=en
focus
$KB tool theme 2>&1 | tee -a "$LOG"
sleep 2
shot sheet

# --- the neon picture: type, press the tool, take the PNG the keyboard made
scenario neon-run s:theme=neon s:last_lang=en i:text_color=0xFF39FF14
focus
$KB type "Omega" 2>&1 | tee -a "$LOG"
sleep 1
$KB tool neon 2>&1 | tee -a "$LOG"
sleep 5
shot neon-share
F=$(adb shell "run-as $PKG ls -t cache/neon" 2>/dev/null | tr -d '\r' | head -1)
if [ -n "$F" ]; then
  adb exec-out "run-as $PKG cat cache/neon/$F" > "$OUT/neon-image.png"
  say "neon picture $F ($(stat -c %s "$OUT/neon-image.png") bytes)"
else
  say "no neon picture was made"
fi

adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1
adb logcat -d 2>/dev/null | grep -E "Omega|AndroidRuntime|FATAL" | tail -300 > "$OUT/logcat-omega.txt"
say "done"
exit 0
