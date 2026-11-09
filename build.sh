#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Toolchain in tools/ (layout nel README.md): JDK 17, build-tools Android,
# platform android-34 e d8 di un build-tools recente (36.x).
JDK_BIN=""
for d in tools/jdk/*/bin; do
  if [ -d "$d" ]; then JDK_BIN="$d"; break; fi
done
BT_DIR=""
for d in tools/android/build-tools/*; do
  if [ -d "$d" ]; then BT_DIR="$d"; break; fi
done
ANDROID_JAR=""
for d in tools/android/platforms/*/android.jar tools/android/platforms/*/*/android.jar; do
  if [ -f "$d" ]; then ANDROID_JAR="$d"; break; fi
done
D8JAR=""
for d in tools/bt36/*/lib/d8.jar; do
  if [ -f "$d" ]; then D8JAR="$d"; break; fi
done
if [ -z "$JDK_BIN" ] || [ -z "$BT_DIR" ] || [ -z "$ANDROID_JAR" ] || [ -z "$D8JAR" ]; then
  echo "Toolchain mancante in tools/ (JDK 17, build-tools, android.jar, d8 36.x) — vedi README.md" >&2
  exit 1
fi
JDK="$JDK_BIN"
BT="$BT_DIR"
OUT="out"
# Python 3 (solo stdlib): quello di sistema se in PATH, altrimenti il percorso locale.
PY=""
for p in python python3 'C:\Users\Mattia\AppData\Local\Python\pythoncore-3.14-64\python.exe'; do
  if { command -v "$p" >/dev/null 2>&1 || [ -f "$p" ]; } && "$p" --version >/dev/null 2>&1; then
    PY="$p"; break
  fi
done
if [ -z "$PY" ] || ! "$PY" --version >/dev/null 2>&1; then
  echo "Python 3 non trovato (serve solo per gli script di build): installalo e mettilo in PATH" >&2
  exit 1
fi
# Svuota i contenuti ma non la cartella: il server HTTP ci pu\u00f2 avere il cwd e su Windows la cartella occupata non si toglie
find "$OUT" -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

export PATH="$JDK:$PATH"

echo "== aapt2 compile =="
"$BT/aapt2.exe" compile --dir app/src/res -o "$OUT/compiled_app.zip"
"$BT/aapt2.exe" compile --dir libs/merged-res -o "$OUT/compiled_libs.zip"

echo "== aapt2 link =="
"$BT/aapt2.exe" link \
  --manifest app/src/AndroidManifest.xml \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  -I "$ANDROID_JAR" \
  -o "$OUT/app.unsigned.apk" \
  --java "$OUT/gen" \
  "$OUT/compiled_libs.zip" \
  "$OUT/compiled_app.zip"

echo "== javac =="
CP="$ANDROID_JAR;libs/material-x/classes.jar;libs/appcompat-x/classes.jar;libs/core-x/classes.jar;libs/activity-x/classes.jar;libs/cardview-x/classes.jar;libs/coordinatorlayout-x/classes.jar;libs/constraintlayout-x/classes.jar;libs/drawerlayout-x/classes.jar;libs/customview-full-cls/classes.jar;libs/dynamicanimation-x/classes.jar;libs/fragment-x/classes.jar;libs/recyclerview-x/classes.jar;libs/transition-x/classes.jar;libs/vectordrawable-x/classes.jar;libs/viewpager2-x/classes.jar;libs/lifecycle-runtime.jar;libs/annotation.jar;$OUT/gen"
"$JDK/javac.exe" -source 1.8 -target 1.8 \
  -cp "$CP" \
  -d "$OUT/classes" \
  app/src/it/mattia/unsloth/MainActivity.java \
  app/src/it/mattia/unsloth/App.java \
  app/src/it/mattia/unsloth/ScrollTrackingWebView.java \
  app/src/it/mattia/unsloth/GenService.java \
  app/src/it/mattia/unsloth/KeepAliveService.java \
  app/src/it/mattia/unsloth/KeepAliveReceiver.java \
  "$OUT/gen/it/mattia/unsloth/R.java" \
  $(find app/src/androidx app/src/com -name "*.java" 2>/dev/null | sort)

echo "== gen lib R classes =="
"$PY" - <<'PYEOF'
import os, re, glob
rjava = "out/gen/it/mattia/unsloth/R.java"
with open(rjava) as f:
    content = f.read()
int_map, arr_map = {}, {}
cur, in_arr, arr_name, arr_type, arr_vals = None, False, None, None, []
for line in content.splitlines():
    s = line.strip()
    if in_arr:
        arr_vals.extend(re.findall(r'(0x[0-9a-f]+)', s))
        if s.endswith('};'): in_arr = False; arr_map[(arr_type, arr_name)] = arr_vals; arr_vals = []
        continue
    m = re.match(r'public static final class (\w+)\s*\{', s)
    if m: cur = m.group(1); continue
    m = re.match(r'public static final int\[\] (\w+)\s*=\s*\{', s)
    if m and cur: in_arr = True; arr_name = m.group(1); arr_type = cur; arr_vals = re.findall(r'(0x[0-9a-f]+)', s); continue
    m = re.match(r'public static final int (\w+)\s*=\s*(0x[0-9a-f]+)\s*;', s)
    if m and cur: int_map[(cur, m.group(1))] = m.group(2)
libs = {}
for d in glob.glob("libs/*-x"):
    rtxt, man = os.path.join(d, "R.txt"), os.path.join(d, "AndroidManifest.xml")
    if not os.path.exists(rtxt) or not os.path.exists(man): continue
    with open(man) as f:
        m = re.search(r'package="([^"]+)"', f.read())
        if not m: continue
    libs[d] = (m.group(1), rtxt)
for d, (pkg, rtxt) in sorted(libs.items()):
    ient, aent = [], []
    with open(rtxt) as f:
        for line in f:
            line = line.strip()
            if not line: continue
            m = re.match(r'int\[\]\s+(\w+)\s+(\w+)\s*\{', line)
            if m:
                key = (m.group(1), m.group(2))
                vals = arr_map.get(key) or re.findall(r'(0x[0-9a-f]+)', line)
                aent.append((m.group(1), m.group(2), vals)); continue
            p = line.split()
            if len(p) >= 4 and p[0] == 'int':
                key = (p[1], p[2])
                ient.append((p[1], p[2], int_map.get(key, "0x0")))
    bi, ba = {}, {}
    for t, n, v in ient: bi.setdefault(t, []).append((n, v))
    for t, n, v in aent: ba.setdefault(t, []).append((n, v))
    pp = os.path.join("out", "gen_libs", *pkg.split("."))
    os.makedirs(pp, exist_ok=True)
    lines = ["package " + pkg + ";", "", "public final class R {"]
    for t in sorted(set(list(bi) + list(ba))):
        lines.append("  public static final class " + t + " {")
        if t in ba:
            for n, vals in sorted(ba[t]):
                lines.append("    public static final int[] " + n + "={")
                for i in range(0, len(vals), 8):
                    c = vals[i:i+8]
                    lines.append("      " + ", ".join(c) + ("," if i+8 < len(vals) else ""))
                lines.append("    };")
        if t in bi:
            for n, v in sorted(bi[t]):
                lines.append("    public static final int " + n + "=" + v + ";")
        lines.append("  }")
    lines.append("}")
    with open(os.path.join(pp, "R.java"), "w") as f:
        f.write("\n".join(lines) + "\n")
print("lib R classes generated (int + int[])")
PYEOF

rm -rf out/gen_libs_classes
"$JDK/javac.exe" -source 1.8 -target 1.8 \
  -cp "$ANDROID_JAR" \
  -d out/gen_libs_classes \
  $(find out/gen_libs -name "R.java")

echo "== d8 =="
JDK_BIN="$JDK_BIN" D8JAR="$D8JAR" LIB="$ANDROID_JAR" "$PY" dex_chunk.py

echo "== add classes.dex to apk =="
"$PY" - <<'EOF'
import zipfile, glob, os
z = zipfile.ZipFile("out/app.unsigned.apk", "a")
files = sorted(glob.glob("out/dex/classes*.dex"),
               key=lambda f: 0 if os.path.basename(f) == "classes.dex"
               else int("".join(c for c in f if c.isdigit())))
for f in files:
    z.write(f, os.path.basename(f))
z.close()
print("dex aggiunti alla radice:", len(files))
EOF

echo "== zipalign =="
"$BT/zipalign.exe" 4 "$OUT/app.unsigned.apk" "$OUT/app.aligned.apk"

echo "== keystore =="
if [ ! -f unsloth.keystore ]; then
    "$JDK/keytool.exe" -genkeypair \
        -keystore unsloth.keystore \
        -storepass unsloth123 -keypass unsloth123 \
        -alias unsloth \
        -dname "CN=Unsloth, OU=Mattia, O=Mattia, L=IT, C=IT" \
        -validity 10000 -keyalg RSA -keysize 2048
fi

echo "== apksigner sign =="
"$BT/apksigner.bat" sign \
    --ks unsloth.keystore \
    --ks-pass pass:unsloth123 --key-pass pass:unsloth123 \
    --out out/Unsloth.apk \
    out/app.aligned.apk

echo "== apksigner verify =="
"$BT/apksigner.bat" verify --print-certs out/Unsloth.apk

echo "== done =="
ls -la out/Unsloth.apk
