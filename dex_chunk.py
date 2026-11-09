import subprocess, os, glob, shutil
os.chdir(os.path.dirname(os.path.abspath(__file__)))
# Percorsi dalla variabili d'ambiente passate da build.sh (fallback: layout locale).
JAVA = os.path.abspath(os.environ.get("JDK_BIN", "tools/jdk/jdk-17.0.20.1+1/bin") + "/java.exe")
D8JAR = os.path.abspath(os.environ.get("D8JAR", "tools/bt36/android-16/lib/d8.jar"))  # d8 di android-14 (8.2.2-dev) ha NPE su ActionBarDrawerToggle$1.class
LIB = os.path.abspath(os.environ.get("LIB", "tools/android/platforms/android-34/android-34-ext12/android.jar"))
import glob as _glob
dirs = sorted(_glob.glob("libs/*-cls")) + ["out/gen_libs_classes", "out/classes"]
CHUNK = 250
parts = []
total = 0
for d in dirs:
    files = sorted(f for f in glob.glob(os.path.join(d, "**", "*.class"), recursive=True)
                   if os.path.basename(f) != "module-info.class")
    for i in range(0, len(files), CHUNK):
        chunk = files[i:i + CHUNK]
        outdir = "out/dexpart_%d" % len(parts)
        os.makedirs(outdir, exist_ok=True)
        cmd = [JAVA, "-cp", D8JAR, "com.android.tools.r8.D8",
               "--release", "--min-api", "24",
               "--lib", LIB, "--output", outdir] + chunk
        ok = False
        for attempt in range(4):
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode == 0:
                ok = True
                break
        if not ok:
            err = r.stderr.decode("utf-8", "replace")
            print("FAIL su", d, "chunk da", i)
            print(err[-3000:])
            raise SystemExit(1)
        parts.append(outdir)
        total += len(chunk)
dexdir = "out/dex"
shutil.rmtree(dexdir, ignore_errors=True)
os.makedirs(dexdir)
i = 0
for p in parts:
    for f in sorted(glob.glob(os.path.join(p, "classes*.dex"))):
        name = "classes.dex" if i == 0 else "classes%d.dex" % (i + 1)
        shutil.copyfile(f, os.path.join(dexdir, name))
        i += 1
print("class file dexati:", total, ": dex files:", i - 1)
