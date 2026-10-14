#!/bin/sh
# Runs the mod unattended on a copy of a saved world and prints what happened.
#   tools/autotest.sh [ticks] [world folder under run/saves]
# The copy lives in run-autotest/, with options of its own (windowed, never pausing), so the
# real world and the developer's settings are not touched.
set -e
cd "$(dirname "$0")/.."
TICKS="${1:-24000}"
WORLD="${2:-New World}"
rm -rf run-autotest/saves/AutoTest
mkdir -p run-autotest/saves
cp -R "run/saves/$WORLD" run-autotest/saves/AutoTest
rm -f run-autotest/saves/AutoTest/session.lock
[ -d run/config ] && mkdir -p run-autotest/config && cp -R run/config/. run-autotest/config/
cat > run-autotest/options.txt <<OPTIONS
fullscreen:false
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
tutorialStep:none
narrator:0
soundCategory_master:0.0
renderDistance:${RD:-6}
simulationDistance:${SD:-8}
maxFps:30
overrideWidth:640
overrideHeight:400
OPTIONS
./gradlew runAutotest -PautotestTicks="$TICKS" -PautotestShowcase="${SHOWCASE:-false}" -PautotestExpand="${EXPAND:-false}" -PautotestShortage="${SHORTAGE:-false}" -PautotestRoam="${ROAM:-false}" -q > run-autotest/gradle.out 2>&1 || true
grep "AUTOTEST" run-autotest/logs/latest.log | sed 's/^.*(villagerciv) //'
