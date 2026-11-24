#!/bin/bash
# Build Podcasts APK. Requires the Android build toolchain and the private
# signing keystore (pod.keystore), which is not published with this repo.
#   export KS_PASS='...'   # keystore password, never committed
set -e
[ -n "$KS_PASS" ] || { echo "Set KS_PASS first"; exit 1; }
BT=${BT:-/workspace/toolchain/bt}
AJ=${AJ:-/workspace/toolchain/platform/android-34/android.jar}
rm -rf classes dex gen dexout unsigned.apk out.apk aligned.apk
$BT/aapt2 compile --dir res -o res.zip
$BT/aapt2 link -o unsigned.apk -I $AJ --manifest AndroidManifest.xml --min-sdk-version 21 --target-sdk-version 34 res.zip
javac -source 1.8 -target 1.8 -classpath $AJ -d classes src/au/com/jmarrone/podcasts/*.java 2>&1 | grep -vi warning || true
mkdir -p dex
$BT/d8 --release --min-api 21 --lib $AJ --output dex $(find classes -name "*.class")
python3 - <<'PY2'
import zipfile
src = zipfile.ZipFile('unsigned.apk')
out = zipfile.ZipFile('out.apk', 'w', zipfile.ZIP_DEFLATED)
for n in src.namelist():
    out.writestr(src.getinfo(n), src.read(n))  # preserves per-entry compression (resources.arsc stays STORED)
out.writestr('classes.dex', open('dex/classes.dex','rb').read())
out.close()
PY2
$BT/zipalign -f 4 out.apk aligned.apk
$BT/apksigner sign --ks pod.keystore --ks-pass env:KS_PASS --out Podcasts_v1.13.apk aligned.apk
$BT/apksigner verify --print-certs Podcasts_v1.13.apk
