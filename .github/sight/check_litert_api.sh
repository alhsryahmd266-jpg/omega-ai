#!/usr/bin/env bash
# Prints the real signatures of the LiteRT classes the app calls by reflection, and ticks off every call the app makes.
# Usage: check_litert_api.sh <version>      Never fails the build: it is information for the next fix.
set -u
V="${1:?version}"
BASE=https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert
T="${RUNNER_TEMP:-/tmp}/litert-api-$V"
rm -rf "$T" && mkdir -p "$T"
CP=""
for A in litert litert-api litert-gpu litert-gpu-api; do
  if curl -fsSL "$BASE/$A/$V/$A-$V.aar" -o "$T/$A.aar" 2> /dev/null; then
    mkdir -p "$T/$A" && unzip -qo "$T/$A.aar" classes.jar -d "$T/$A" 2> /dev/null && CP="$CP:$T/$A/classes.jar"
    echo "artifact $A $V: ok"
  else
    echo "artifact $A $V: not published"
  fi
done
CP="${CP#:}"
# the 1.x releases keep the Java package org.tensorflow.lite, newer ones use com.google.ai.edge.litert: look where the Interpreter is
ALLCLASSES=$(for J in "$T"/*/classes.jar; do unzip -l "$J" 2> /dev/null | awk '{print $4}'; done)
P=none
if echo "$ALLCLASSES" | grep -qxF 'org/tensorflow/lite/Interpreter.class'; then P=org.tensorflow.lite
elif echo "$ALLCLASSES" | grep -qxF 'com/google/ai/edge/litert/Interpreter.class'; then P=com.google.ai.edge.litert; fi
echo "Interpreter package: $P"
if [ "$P" = none ]; then
  echo "classes in the jars (first 80):"; echo "$ALLCLASSES" | grep -E 'Interpreter|Tensor|Delegate' | head -80
fi
OUT="$T/api.txt"
: > "$OUT"
sig() { javap -p -classpath "$CP" "$1" 2>&1; }

for C in "$P.Interpreter" "$P.Interpreter\$Options" "$P.Tensor" "$P.Tensor\$QuantizationParams" "$P.Delegate" "$P.DataType" "$P.gpu.GpuDelegate" "$P.gpu.CompatibilityList"; do
  echo "=== $C" >> "$OUT"
  sig "$C" >> "$OUT"
done
head -400 "$OUT"

# every call the reflection code makes: (class, regular expression the javap line must match)
RES="$T/checks.txt"
: > "$RES"
expect() {
  local cls="$1" pat="$2" what="$3"
  if sig "$cls" | grep -Eq "$pat"; then echo "OK    $what" >> "$RES"; else echo "MISSING  $what   [$cls]" >> "$RES"; fi
}
expect "$P.Interpreter" "public ${P//./\\.}\\.Interpreter\\(java\\.io\\.File, ${P//./\\.}\\.Interpreter\\\$Options\\)" 'Interpreter(File, Options)'
expect "$P.Interpreter" ' getInputTensorCount\(\)' 'getInputTensorCount()'
expect "$P.Interpreter" ' getOutputTensorCount\(\)' 'getOutputTensorCount()'
expect "$P.Interpreter" ' getInputTensor\(int\)' 'getInputTensor(int)'
expect "$P.Interpreter" ' getOutputTensor\(int\)' 'getOutputTensor(int)'
expect "$P.Interpreter" ' runForMultipleInputsOutputs\(java.lang.Object\[\], java.util.Map' 'runForMultipleInputsOutputs(Object[], Map)'
expect "$P.Interpreter" ' close\(\)' 'close()'
expect "$P.Interpreter\$Options" ' setNumThreads\(int\)' 'Options.setNumThreads(int)'
expect "$P.Interpreter\$Options" " addDelegate\\(${P//./\\.}\\.Delegate\\)" 'Options.addDelegate(Delegate)'
expect "$P.Tensor" ' shape\(\)' 'Tensor.shape()'
expect "$P.Tensor" ' dataType\(\)' 'Tensor.dataType()'
expect "$P.Tensor" ' numBytes\(\)' 'Tensor.numBytes()'
expect "$P.Tensor" ' quantizationParams\(\)' 'Tensor.quantizationParams()'
expect "$P.Tensor\$QuantizationParams" ' getScale\(\)' 'QuantizationParams.getScale()'
expect "$P.Tensor\$QuantizationParams" ' getZeroPoint\(\)' 'QuantizationParams.getZeroPoint()'
expect "$P.gpu.GpuDelegate" "public ${P//./\\.}\\.gpu\\.GpuDelegate\\(\\)" 'GpuDelegate()'
expect "$P.gpu.CompatibilityList" "public ${P//./\\.}\\.gpu\\.CompatibilityList\\(\\)" 'CompatibilityList()'
expect "$P.gpu.CompatibilityList" ' isDelegateSupportedOnThisDevice\(\)' 'CompatibilityList.isDelegateSupportedOnThisDevice()'
# the tensor object's concrete class must not be what the code calls through: the public type must be an interface or a public class
if sig "$P.Tensor" | grep -Eq '^public (abstract )?interface|^public (abstract )?class'; then echo "OK    Tensor is a public type" >> "$RES"; else echo "MISSING  Tensor is not a public type" >> "$RES"; fi

echo "=== what the app calls ==="
cat "$RES"
BAD=$(grep -c '^MISSING' "$RES")
if [ "$BAD" -gt 0 ]; then
  MSG=$(sed ':a;N;$!ba;s/%/%25/g;s/\r/%0D/g;s/\n/%0A/g' "$RES")
  echo "::warning title=LiteRT $V: $BAD calls the app makes were not found::$MSG"
else
  echo "::notice title=LiteRT $V::all the calls the app makes exist (package $P)"
fi
{
  echo "### LiteRT $V ($P) — calls made by reflection"
  echo '```'
  cat "$RES"
  echo '```'
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit 0
