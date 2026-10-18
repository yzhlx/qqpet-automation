import os, subprocess, sys

cp = [l.strip() for l in open(r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\cp.lst", encoding="utf-8") if l.strip() and os.path.exists(l.strip())]
classes = next(l for l in cp if l.endswith("compileDebugUnitTestKotlin\\classes") or l.endswith("compileDebugUnitTestKotlin/classes"))
names = []
for root, dirs, files in os.walk(classes):
    for f in files:
        if f.endswith("Test.class") and "$" not in f:
            rel = os.path.relpath(os.path.join(root, f), classes)[:-6].replace(os.sep, ".")
            names.append(rel)
missing = [w for w in ("junit", "android.jar") if not any(w in l.lower() for l in cp)]
print("classpath", len(cp), "tests", len(names), "missing", missing)
java = r"C:\Users\lu158\dsh-qqpet\tools\android-build\jdk21\jdk-21.0.12.1+1\bin\java.exe"
argfile = r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\junit.args"
lines = ["-Djava.io.tmpdir=C:/Users/lu158/dsh-qqpet/VirtualDisplay/.build-tmp", "-cp", ";".join(cp), "org.junit.runner.JUnitCore"] + names
open(argfile, "w", encoding="mbcs", newline="\n").write("\n".join(lines) + "\n")
r = subprocess.run([java, "@" + argfile], capture_output=True,
                   cwd=r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\app")
sys.stdout.buffer.write(r.stdout)
sys.stdout.buffer.write(r.stderr)
print("EXIT", r.returncode)
