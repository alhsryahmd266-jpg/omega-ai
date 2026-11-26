#!/usr/bin/env bash
# Picks the LiteRT version Omega Sight is built with (LiteRT = Google's new name for TensorFlow Lite; it runs the owner's .tflite files).
# The app reaches the library by reflection, so the one thing a version must have is the classic Interpreter classes in its jar
# (the 1.x releases keep the Java package org.tensorflow.lite; the 2.x releases moved to a new API without them). This takes the newest
# stable 1.x release that has them, and lists the fallbacks to try if Gradle cannot use it.
#
# Writes to $GITHUB_OUTPUT:  version=<x.y.z>   gpu=true|false   attempts="<version>:<gpu> <version>:<gpu> ..."
set -u
BASE=https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert
T="${RUNNER_TEMP:-/tmp}"
META="$T/litert-meta.xml"
curl -fsSL "$BASE/litert/maven-metadata.xml" -o "$META" || true
ALL=$(grep -o '<version>[^<]*</version>' "$META" 2>/dev/null | sed 's/<[^>]*>//g' | tr '\n' ' ')
echo "litert versions on maven: $ALL"

KNOWN=1.4.0
CANDS=$(printf '%s\n' $ALL | grep -E '^1\.[0-9]+\.[0-9]+$' | sort -V -r | head -6 | tr '\n' ' ')
[ -n "$CANDS" ] || CANDS=$KNOWN
echo "stable 1.x candidates, newest first: $CANDS"

CHOSEN=""
for V in $CANDS; do
  AAR="$T/litert-$V.aar"
  if ! curl -fsSL "$BASE/litert/$V/litert-$V.aar" -o "$AAR"; then echo "  $V: no aar"; continue; fi
  J="$T/litert-$V"; rm -rf "$J"; mkdir -p "$J"
  if ! unzip -qo "$AAR" classes.jar -d "$J" 2> /dev/null; then echo "  $V: no classes.jar in the aar"; continue; fi
  LIST=$(unzip -l "$J/classes.jar" | awk '{print $4}')
  FOUND=""
  for PK in org/tensorflow/lite com/google/ai/edge/litert; do
    if echo "$LIST" | grep -qxF "$PK/Interpreter.class" && echo "$LIST" | grep -qxF "$PK/Interpreter\$Options.class"; then FOUND=$PK; break; fi
  done
  if [ -n "$FOUND" ]; then
    echo "  $V: has Interpreter and Interpreter.Options in $FOUND"
    CHOSEN=$V
    break
  fi
  echo "  $V: no Interpreter class (a different API): skipped"
done
[ -n "$CHOSEN" ] || { echo "no candidate has the Interpreter API: using the known version $KNOWN"; CHOSEN=$KNOWN; }

GPU=false
if curl -fsI "$BASE/litert-gpu/$CHOSEN/litert-gpu-$CHOSEN.aar" > /dev/null 2>&1; then GPU=true; fi

ATTEMPTS="$CHOSEN:$GPU"
[ "$CHOSEN" = "$KNOWN" ] || ATTEMPTS="$ATTEMPTS $KNOWN:true"
ATTEMPTS="$ATTEMPTS $KNOWN:false"

echo "using litert $CHOSEN (gpu delegate: $GPU); attempts in order: $ATTEMPTS"
echo "::notice title=litert::using $CHOSEN (gpu=$GPU); available: $ALL"
{
  echo "version=$CHOSEN"
  echo "gpu=$GPU"
  echo "attempts=$ATTEMPTS"
} >> "${GITHUB_OUTPUT:-/dev/stdout}"
