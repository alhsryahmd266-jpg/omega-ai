#!/usr/bin/env bash
# Runs Omega Sight on an Android emulator (called by the emulator action):
#   1. the screens, 2. the model self-test with a tiny generated model, 3. the image test, 4. the accessibility service,
#   5. an end-to-end run: the app captures the screen of a stand-in game (testgame), finds its bots and aims at them with real gestures.
# Everything is best effort: a failing step is logged and the next one still runs. Screenshots and logs go to $OUT.
set -u
OUT="${OUT:-preview-out}"
APK="${APK:?path of the app debug apk}"
GAME="${GAME:?path of the test game apk}"
MODEL="${MODEL:-}"
PKG=com.omega.sight
GPKG=com.omega.sight.testgame
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
exec > >(tee -a "$OUT/preview.log") 2>&1

step() { echo; echo "=== $(date +%T) $*"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2> /dev/null; echo "shot $1: $(stat -c %s "$OUT/$1.png" 2> /dev/null) bytes"; }
# the command string is quoted for the device's shell too (it may contain ";")
app() { adb shell "am start -W -n $PKG/.ui.MainActivity --es sight_cmd '$1'" > /dev/null; }
tags() { adb logcat -d -s "$@" | tail -n "${LINES_MAX:-4}"; }

step "device"
adb wait-for-device
adb shell getprop ro.build.version.sdk
adb shell wm size
adb shell wm density
adb logcat -G 16M > /dev/null 2>&1 || true
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard 2> /dev/null || true

step "install"
adb install -r -g "$APK" || { echo "the app did not install"; exit 1; }
adb install -r -g "$GAME" || echo "the test game did not install"
adb shell pm list packages | grep omega
adb shell dumpsys package "$PKG" | grep -E "granted=true|requested permissions" | head -20

step "screens"
adb logcat -c
adb shell am start -W -n "$PKG/.ui.MainActivity" > /dev/null
sleep 5
shot 01-top
app "open_all"; sleep 2
app "scroll:setup"; sleep 1; shot 02-setup
app "scroll:files"; sleep 1; shot 03-files
app "scroll:tests"; sleep 1; shot 04-tests
app "scroll:tuning"; sleep 1; shot 05-tuning
adb shell input swipe 540 1900 540 600 300; sleep 1; shot 06-tuning-2
adb shell input swipe 540 1900 540 600 300; sleep 1; shot 07-tuning-3
app "scroll:limits"; sleep 1; shot 08-limits

step "model self-test"
if [ -n "$MODEL" ] && [ -f "$MODEL" ]; then
  adb push "$MODEL" /data/local/tmp/vision.tflite > /dev/null
  adb shell "run-as $PKG sh -c 'mkdir -p files/models && cat /data/local/tmp/vision.tflite > files/models/vision.tflite'"
  adb shell "run-as $PKG ls -la files/models"
  adb logcat -c
  app "scroll:files"; sleep 2; shot 10-files-with-model
  app "test_vision"; sleep 10; shot 11-vision-test
  adb shell input keyevent KEYCODE_BACK; sleep 1
else
  echo "no test model: skipped"
fi

step "accessibility service"
adb shell settings put secure enabled_accessibility_services "$PKG/com.omega.sight.input.SightService"
adb shell settings put secure accessibility_enabled 1
sleep 5
adb shell dumpsys accessibility | grep -i -B1 -A6 "omega" | head -30
app "scroll:setup"; sleep 2; shot 12-setup-accessibility

# a smaller screen for the game part: the emulator draws with its CPU, and a real phone has a GPU for that. It also makes the screen
# size differ from the panel's own size, as it does on phones whose owner changed the display size.
adb shell wm size 720x1600
adb shell wm density 280
sleep 3

step "the stand-in game alone (baseline: nothing aims)"
adb shell am force-stop "$GPKG"
adb logcat -c
adb shell am start -W -n "$GPKG/.GameActivity" > /dev/null
sleep 18
shot 20-game-baseline
cp "$OUT/20-game-baseline.png" "$OUT/20-game-baseline-keep.png"
adb logcat -d -s SightTestGame:I > "$OUT/game-baseline.log"
tail -n 2 "$OUT/game-baseline.log"
adb shell wm size

step "image test on that picture"
adb push "$OUT/20-game-baseline-keep.png" /data/local/tmp/still.png > /dev/null
adb shell "run-as $PKG sh -c 'cat /data/local/tmp/still.png > files/still.png'"
adb logcat -c
app "still:/data/data/$PKG/files/still.png"; sleep 12; shot 21-image-test
tags AndroidRuntime:E System.err:W
adb shell input keyevent KEYCODE_BACK; sleep 1

step "pick the stand-in game and start the capture"
adb shell appops set "$PKG" PROJECT_MEDIA allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2> /dev/null
adb shell am force-stop "$GPKG"
app "game:$GPKG;scroll:setup"; sleep 2
adb logcat -c
app "start_capture"
started=0
for i in $(seq 1 20); do
  sleep 2
  if adb logcat -d -s SightLive:I | grep -q "capture: started"; then started=1; break; fi
  if [ "$i" -eq 3 ] || [ "$i" -eq 7 ] || [ "$i" -eq 11 ]; then
    echo "no capture yet ($i): looking for the system dialog"
    shot "30-dialog-$i"
    python3 "$HERE/ui.py" texts | head -30
    python3 "$HERE/ui.py" consent || true
  fi
done
echo "capture started: $started"
shot 31-capture-started
tags SightLive:I

step "motion test: the app aims at the bots of the stand-in game"
adb logcat -c
app "motion_test"
sleep 8;  shot 40-aim-8s;  adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2
sleep 12; shot 41-aim-20s; adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp" | head -2
sleep 20; shot 42-aim-40s
sleep 20; shot 43-aim-60s
LINES_MAX=3 tags SightTestGame:I
LINES_MAX=2 tags SightLive:I

step "results"
adb logcat -d -s SightTestGame:I > "$OUT/game.log"
adb logcat -d -s SightLive:I SightCmd:I > "$OUT/sight.log"
echo "capture events:"; grep "capture:" "$OUT/sight.log" | tee "$OUT/capture-events.txt"
echo "baseline:"; python3 "$HERE/analyze.py" "$OUT/game-baseline.log" | tee "$OUT/analysis-baseline.txt"
echo "with the app aiming:"; python3 "$HERE/analyze.py" "$OUT/game.log" | tee "$OUT/analysis-aim.txt"
adb logcat -d | grep -E "AndroidRuntime|FATAL|SIGSEGV|Fatal signal|tflite|litert|TfLite|omega\.sight" | tail -n 120 > "$OUT/errors.log"
echo "error log lines: $(wc -l < "$OUT/errors.log")"
app "scroll:tests"; sleep 5; shot 50-main-after
LINES_MAX=3 tags SightLive:I
adb logcat -d -s SightLive:I | grep "capture:" | tee "$OUT/capture-events.txt"
app "stop_capture"; sleep 3
shot 51-main-stopped
# one tall picture of the whole page (a very tall virtual display); last, because it is the riskiest thing here
adb shell wm size 1080x7000 > /dev/null 2>&1 && { sleep 4; app "open_all"; sleep 3; shot 52-whole-page; }
adb shell wm size reset > /dev/null 2>&1
adb shell wm density reset > /dev/null 2>&1
sleep 2
adb logcat -d -v time > "$OUT/logcat-full.txt" 2> /dev/null
gzip -f "$OUT/logcat-full.txt" 2> /dev/null
step "done"
sleep 1
