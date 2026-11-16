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
adb shell pm grant $PKG android.permission.RECORD_AUDIO 2>&1 | tee -a "$LOG"
adb shell input keyevent KEYCODE_WAKEUP
adb shell input keyevent KEYCODE_HOME

shot() { adb exec-out screencap -p > "$OUT/$1.png"; say "shot $1 ($(stat -c %s "$OUT/$1.png") bytes)"; }
current_ime() { adb shell settings get secure default_input_method | tr -d '\r'; }

# makes our keyboard the selected one and waits until the system agrees
select_keyboard() {
  for i in 1 2 3 4 5 6; do
    adb shell ime enable "$IME" > /dev/null 2>&1
    adb shell ime set "$IME" > /dev/null 2>&1
    sleep 1
    if [ "$(current_ime)" = "$IME" ]; then return 0; fi
  done
  say "could not select the keyboard (it is $(current_ime))"
  return 1
}
select_keyboard

# the app starts with exactly these settings: scenario <name> <kind:key=value>...
scenario() {
  local name="$1"; shift
  say "== $name"
  adb shell am force-stop $PKG
  # the settings go onto the disk BEFORE our keyboard is selected: selecting it starts the keyboard's process, which reads them once
  $KB prefs "$RUNNER_TEMP/omega.xml" "$@"
  adb push "$RUNNER_TEMP/omega.xml" /data/local/tmp/omega.xml > /dev/null
  adb shell chmod 644 /data/local/tmp/omega.xml
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/omega.xml shared_prefs/omega.xml'" 2>&1 | tee -a "$LOG"
  sleep 2                       # a force-stop makes the system forget the selected keyboard a moment later: wait, then select ours
  select_keyboard
  adb shell am start -W -n $PKG/.ui.MainActivity > /dev/null
  sleep 2.5
  say "default keyboard: $(current_ime)"
}

# taps the text field until OUR keyboard is on the screen (the system may have gone back to Gboard meanwhile)
focus() {
  for i in 1 2 3 4; do
    if [ "$(current_ime)" != "$IME" ]; then say "the system changed the keyboard, selecting ours again"; select_keyboard; fi
    $KB field 2>&1 | tee -a "$LOG"
    sleep 2.5
    # the dump alone is not proof (it also answers while the keyboard is hidden): the system must say the keyboard is shown
    if $KB shown && $KB dump >> "$LOG" 2>&1; then return 0; fi
    say "our keyboard is not on the screen yet (try $i)"
    adb shell input keyevent KEYCODE_BACK
    sleep 1
    # BACK may have closed the app: bring the test screen with the text field back before trying again
    adb shell am start -W -n $PKG/.ui.MainActivity > /dev/null
    sleep 2
  done
  return 1
}

# keyboard in a look, nothing typed
look() { scenario "$@"; focus; shot "$1"; }

# keyboard in a look with some text typed: typed <name> <text> <prefs...>
typed() {
  local name="$1" text="$2"; shift 2
  scenario "$name" "$@"; focus
  $KB type "$text" 2>&1 | tee -a "$LOG"
  sleep 1.2
  shot "$name"
}

# the tool row of the strip opened, nothing typed: bar <name> <prefs...>
bar() { scenario "$@"; focus; $KB openbar 2>&1 | tee -a "$LOG"; sleep 1.2; shot "$1"; }

# the tool row with one tool shown as running (its gold disc): barlit <name> <tool> <prefs...>
barlit() {
  local name="$1" tool="$2"; shift 2
  scenario "$name" "$@"; focus; $KB openbar 2>&1 | tee -a "$LOG"
  adb shell am broadcast -a com.omega.keyboard.DEBUG_ACTIVE --es tool "$tool" -p $PKG > /dev/null
  sleep 1.2; shot "$name"
}

# holds a key down for a few seconds and takes three screenshots while the finger is still on it: held <name> <key>
held() {
  local name="$1" key="$2" X Y
  read -r X Y < <($KB pos "$key")
  if [ -z "$X" ]; then say "no position for key $key"; return 1; fi
  adb shell input swipe "$X" "$Y" "$X" "$Y" 3600 &
  local pid=$!
  sleep 0.4
  shot "$name-a"
  shot "$name-b"
  shot "$name-c"
  wait $pid
}

# --- the app's own screens
scenario app-main s:theme=light
shot app-main
adb shell am start -W -n $PKG/.ui.MainActivity --es page model 2>&1 | tail -3 >> "$LOG"; sleep 2.5; shot app-model
adb shell am start -W -n $PKG/.ui.SettingsActivity 2>&1 | tail -3 >> "$LOG"; sleep 2.5; shot app-settings

# --- the key grid, Arabic and English
look  kb-ar-light     s:theme=light s:last_lang=ar
typed kb-ar-dark   "مرحبا بكم" s:theme=dark s:last_lang=ar
typed kb-ar-blue   "اهلا يا صديقي" s:theme=light s:last_lang=ar
typed kb-ar-pink   "كيف حالك" s:theme=ocean s:last_lang=ar i:text_color=0xFFFF2BD6
typed kb-en-light  "Hello world" s:theme=light s:last_lang=en
typed kb-en-red    "hello omega" s:theme=dark s:last_lang=en i:text_color=0xFFFF1744
typed kb-aurora    "neon text" s:theme=aurora s:last_lang=en i:text_color=0xFF39FF14
typed kb-neon      "glow" s:theme=neon s:last_lang=en i:text_color=0xFFFF2BD6
typed kb-rainbow   "rainbow" s:theme=amoled s:last_lang=en b:text_rainbow=true
typed kb-highlight "marker" s:theme=light s:last_lang=en b:text_highlight=true i:text_color=0xFFFFEA00
# the luxe look (the default): black and gold by night, pearl and gold by day
look  kb-luxe-en      s:theme=luxe s:last_lang=en
look  kb-luxe-ar      s:theme=luxe s:last_lang=ar
look  kb-pearl-en     s:theme=pearl s:last_lang=en
look  kb-pearl-ar     s:theme=pearl s:last_lang=ar
typed kb-luxe-typed-ar "مرحبا بكم يا اصدقاء" s:theme=luxe s:last_lang=ar
typed kb-luxe-typed-en "Hello world" s:theme=luxe s:last_lang=en
typed kb-pearl-typed-ar "اهلا بيك" s:theme=pearl s:last_lang=ar
typed kb-luxe-sugg-ar "مر" s:theme=luxe s:last_lang=ar b:toolbar_open=false
typed kb-pearl-sugg-en "hel" s:theme=pearl s:last_lang=en b:toolbar_open=false
bar   kb-bar-luxe-ar  s:theme=luxe s:last_lang=ar
bar   kb-bar-luxe-en  s:theme=luxe s:last_lang=en
bar   kb-bar-pearl-ar s:theme=pearl s:last_lang=ar
bar   kb-bar-pearl-en s:theme=pearl s:last_lang=en
barlit kb-bar-luxe-lit   auto s:theme=luxe s:last_lang=en
barlit kb-bar-pearl-lit  screen s:theme=pearl s:last_lang=ar
look  kb-auto         s:theme=auto s:last_lang=en
look  kb-ocean        s:theme=ocean s:last_lang=ar
look  kb-sunset       s:theme=sunset s:last_lang=en
look  kb-rose         s:theme=rose s:last_lang=en
look  kb-forest       s:theme=forest s:last_lang=ar
look  kb-mint         s:theme=mint s:last_lang=en
bar   kb-bar-light-ar  s:theme=light s:last_lang=ar
bar   kb-bar-dark-en   s:theme=dark s:last_lang=en
bar   kb-bar-aurora-ar s:theme=aurora s:last_lang=ar
bar   kb-bar-neon-en   s:theme=neon s:last_lang=en

# --- the appearance sheet: colours, background colour (two switches), themes, size and effects
scenario sheet s:theme=aurora s:last_lang=en
focus
$KB tool theme 2>&1 | tee -a "$LOG"
sleep 2
shot sheet
$KB tab 1 2>&1 | tee -a "$LOG"; sleep 1.2; shot sheet-themes
$KB tab 2 2>&1 | tee -a "$LOG"; sleep 1.2; shot sheet-size
$KB tab 0 2>&1 | tee -a "$LOG"; sleep 0.8
$KB target 3 2>&1 | tee -a "$LOG"; sleep 1.2; shot sheet-bg

# --- the luxe look in the sheet and the panels
scenario sheet-luxe s:theme=luxe s:last_lang=ar
focus
$KB tool theme 2>&1 | tee -a "$LOG"
sleep 2
shot sheet-luxe
$KB tab 1 2>&1 | tee -a "$LOG"; sleep 1.2; shot sheet-luxe-themes
scenario kb-emoji-luxe s:theme=luxe s:last_lang=en
focus
$KB key EMOJI 2>&1 | tee -a "$LOG"; sleep 2; shot kb-emoji-luxe
scenario kb-symbols-luxe s:theme=luxe s:last_lang=ar
focus
$KB key SYMBOLS 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-symbols-luxe-1
scenario kb-clip-luxe s:theme=luxe s:last_lang=en
focus
$KB tool clip 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-clip-luxe
scenario kb-sheet-pearl s:theme=pearl s:last_lang=en
focus
$KB tool theme 2>&1 | tee -a "$LOG"
sleep 2
shot kb-sheet-pearl

# --- the finger: a key held down (glow, ripple, preview bubble) and the long-press alternatives
scenario kb-hold-en s:theme=aurora s:last_lang=en
focus
held kb-hold-en g
scenario kb-hold-luxe s:theme=luxe s:last_lang=en
focus
held kb-hold-luxe g
scenario kb-hold-luxe-enter s:theme=luxe s:last_lang=ar
focus
held kb-hold-luxe-enter ENTER
# --- the luxe extras, frozen so a still picture can show them: the shine at two tilts, the glint halfway, the sparks of the enter key
fx() { adb shell am broadcast -a com.omega.keyboard.DEBUG_FX -p $PKG "$@" > /dev/null; sleep 1; }
scenario kb-fx-luxe s:theme=luxe s:last_lang=en
focus
fx --ef tx 0.9 --ef ty -0.5
shot kb-fx-tilt-a
fx --ef tx -0.9 --ef ty 0.5
shot kb-fx-tilt-b
fx --ef tx 0 --ef ty 0 --ef intro 0.45
shot kb-fx-glint
fx --ef intro -1 --ef spark 0.35
shot kb-fx-spark
scenario kb-fx-pearl s:theme=pearl s:last_lang=ar
focus
fx --ef tx 0.9 --ef ty -0.5
shot kb-fx-pearl-tilt
fx --ef tx 0 --ef ty 0 --ef intro 0.45
shot kb-fx-pearl-glint
fx --ef intro -1 --ef spark 0.35
shot kb-fx-pearl-spark
scenario kb-popup-en s:theme=dark s:last_lang=en
focus
held kb-popup-en e
scenario kb-popup-ar s:theme=ocean s:last_lang=ar
focus
held kb-popup-ar ي

# --- emoji, symbols
scenario kb-emoji s:theme=aurora s:last_lang=en
focus
$KB key EMOJI 2>&1 | tee -a "$LOG"; sleep 2; shot kb-emoji
scenario kb-symbols s:theme=sunset s:last_lang=en
focus
$KB key SYMBOLS 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-symbols-1
$KB key SYMBOLS2 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-symbols-2

# --- Arabic: symbols pages, the extra-letters page, the popup of the Arabic comma (has the Arabic question mark)
scenario kb-symbols-ar s:theme=light s:last_lang=ar
focus
$KB key SYMBOLS 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-symbols-ar-1
$KB key SYMBOLS2 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-symbols-ar-2
scenario kb-alt-ar s:theme=dark s:last_lang=ar
focus
$KB key ALT 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-alt-ar
scenario kb-popup-comma s:theme=dark s:last_lang=ar
focus
held kb-popup-comma "،"

# --- the other panels of the tool row
scenario kb-clip s:theme=dark s:last_lang=en
focus
$KB tool clip 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-clip
scenario kb-lang s:theme=light s:last_lang=ar
focus
$KB tool typed 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-lang
scenario kb-voice s:theme=light s:last_lang=ar
focus
$KB tool voice 2>&1 | tee -a "$LOG"; sleep 3; shot kb-voice

# --- text decoration (زخرفة): write, open the panel, pick looks (the whole text is decorated), undo; then the live mode
scenario kb-decor s:theme=luxe s:last_lang=ar
focus
$KB type "لا يسيء الظن بك" 2>&1 | tee -a "$LOG"; sleep 1.2
$KB tool decor 2>&1 | tee -a "$LOG"; sleep 1.8
shot kb-decor-1
$KB panel royal 2>&1 | tee -a "$LOG"; sleep 1.8
shot kb-decor-2
say "decor royal: [$($KB text 2>/dev/null)]"
$KB panel kashida 2>&1 | tee -a "$LOG"; sleep 1.5
say "decor kashida: [$($KB text 2>/dev/null)]"
$KB panel brackets 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-decor-3
say "decor brackets: [$($KB text 2>/dev/null)]"
$KB panel undo 2>&1 | tee -a "$LOG"; sleep 1.5
say "decor undo: [$($KB text 2>/dev/null)]"
$KB panel none 2>&1 | tee -a "$LOG"; sleep 1.2
say "decor none: [$($KB text 2>/dev/null)]"
# live: the letters are decorated as they are typed, one backspace takes a letter with its marks
scenario kb-decor-live s:theme=pearl s:last_lang=ar b:decor_live=true s:decor_style=royal
focus
$KB type "عرفك" 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-decor-live-1
say "decor live typed: [$($KB text 2>/dev/null)]"
$KB key BACKSPACE 2>&1 | tee -a "$LOG"; sleep 1.2
say "decor live after one backspace: [$($KB text 2>/dev/null)]"
shot kb-decor-live-2
# the English fonts
scenario kb-decor-en s:theme=luxe s:last_lang=en
focus
$KB type "Hello Omega" 2>&1 | tee -a "$LOG"; sleep 1.2
$KB tool decor 2>&1 | tee -a "$LOG"; sleep 1.8
$KB panel script 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-decor-en
say "decor script: [$($KB text 2>/dev/null)]"

# --- translating what is typed, the Gboard way: a box inside the keyboard, its translation written live into the app's field.
# English typed in the box -> Arabic in the field; the tick keeps it; another sentence; swap; the list of source languages; back.
scenario kb-livetr s:theme=light s:last_lang=en s:typed_target=ar s:typed_source=auto
focus
$KB tool typed 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-livetr-0
$KB type "good morning my friend" 2>&1 | tee -a "$LOG"; sleep 14
shot kb-livetr-1
say "livetr field while typing: [$($KB text 2>/dev/null)]"
$KB key ENTER 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-livetr-2
say "livetr field after the tick: [$($KB text 2>/dev/null)]"
$KB type "thank you" 2>&1 | tee -a "$LOG"; sleep 9
shot kb-livetr-3
say "livetr field with a second sentence: [$($KB text 2>/dev/null)]"
$KB strip tr:swap 2>&1 | tee -a "$LOG"; sleep 9
shot kb-livetr-4
say "livetr field after the swap: [$($KB text 2>/dev/null)]"
$KB strip tr:src 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-livetr-5
$KB strip tr:back 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-livetr-6
say "livetr field at the end: [$($KB text 2>/dev/null)]"

# the same bar in the luxe look, with Arabic typed and English coming out
scenario kb-livetr-luxe s:theme=luxe s:last_lang=ar s:typed_target=en s:typed_source=auto
focus
$KB tool typed 2>&1 | tee -a "$LOG"; sleep 1.5
$KB type "صباح الخير يا صديقي" 2>&1 | tee -a "$LOG"; sleep 14
shot kb-livetr-luxe
say "livetr luxe field: [$($KB text 2>/dev/null)]"

# --- resizing by hand: handles, drag the top edge up, the right edge in, the left edge in, done, then typing in the new size
scenario kb-resize s:theme=light s:last_lang=ar
focus
$KB tool resize 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-resize-1
$KB drag RZ_TOP 0 -240 800 2>&1 | tee -a "$LOG"; sleep 1.8; shot kb-resize-2
$KB drag RZ_RIGHT -260 0 800 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-resize-3
$KB drag RZ_LEFT 90 0 800 2>&1 | tee -a "$LOG"; sleep 1.5; shot kb-resize-4
$KB key RZ_DONE 2>&1 | tee -a "$LOG"; sleep 1.5
$KB type "اعمل كل اللي قلتلك عليه" 2>&1 | tee -a "$LOG"; sleep 1.2; shot kb-resize-typed
say "typed after the resize: [$($KB text 2>/dev/null)]"
# the size was saved: a fresh start comes up in it
scenario kb-resize-kept s:theme=light s:last_lang=ar i:height_pct=140 i:width_pct=80 i:kb_offset=100
focus
shot kb-resize-kept

# --- does every key type what it shows? The same sentence in the normal size, tall, narrow on the left, narrow on the right
check() {
  local name="$1" text="$2"; shift 2
  scenario "$name" "$@"; focus
  $KB type "$text" 2>&1 | tee -a "$LOG"
  sleep 1.2
  local got; got="$($KB text 2>/dev/null)"
  if [ "$got" = "$text" ]; then say "CHECK $name: ok"; else say "CHECK $name: FAIL wanted [$text] got [$got]"; fi
  shot "$name"
}
check type-normal "اعمل كل اللي قلتلك عليه" s:theme=light s:last_lang=ar
check type-tall   "اضغط على حرف يطلع حرف تاني" s:theme=light s:last_lang=ar i:height_pct=165
check type-short  "ضصثقفغعهخحج شسيبلاتنمكط ذءؤرىةوزظد" s:theme=light s:last_lang=ar i:height_pct=60
check type-narrow-left  "Hello world how are you" s:theme=light s:last_lang=en i:width_pct=65 i:kb_offset=0
check type-narrow-right "اللوحة فيها غلط" s:theme=light s:last_lang=ar i:width_pct=65 i:kb_offset=100

# --- landscape (last: it turns the phone sideways)
scenario kb-land s:theme=aurora s:last_lang=en
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
focus
shot kb-land
$KB tool typed 2>&1 | tee -a "$LOG"; sleep 1.5
shot kb-land-translate
$KB strip tr:back 2>&1 | tee -a "$LOG"; sleep 1.2
$KB tool theme 2>&1 | tee -a "$LOG"; sleep 2; shot kb-land-sheet
adb shell settings put system user_rotation 0

adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1
adb logcat -d 2>/dev/null | grep -E "Omega|AndroidRuntime|FATAL" | tail -300 > "$OUT/logcat-omega.txt"
say "done"
exit 0
