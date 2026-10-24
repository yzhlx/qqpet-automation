import argparse, fnmatch, os, subprocess, sys

parser = argparse.ArgumentParser(description="Run freshly compiled JUnit classes; optional class-name glob filters")
parser.add_argument("filters", nargs="*", help="Class-name globs, e.g. '*Payroll*Test' (default: all test classes)")
parser.add_argument("--log", help="Optional UTF-8 path retaining the complete JUnit result")
args = parser.parse_args()

cp = [l.strip() for l in open(r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\cp.lst", encoding="utf-8") if l.strip() and os.path.exists(l.strip())]
classes = next(l for l in cp if l.endswith("compileDebugUnitTestKotlin\\classes") or l.endswith("compileDebugUnitTestKotlin/classes"))
names = []
for root, dirs, files in os.walk(classes):
    for f in files:
        if f.endswith("Test.class") and "$" not in f:
            rel = os.path.relpath(os.path.join(root, f), classes)[:-6].replace(os.sep, ".")
            names.append(rel)
names.sort()
if args.filters:
    names = [name for name in names if any(fnmatch.fnmatchcase(name, pattern) or
             fnmatch.fnmatchcase(name.rsplit('.', 1)[-1], pattern) for pattern in args.filters)]
if not names:
    raise SystemExit("No compiled test classes matched; compile current tests and check the filters")
missing = [w for w in ("junit", "android.jar") if not any(w in l.lower() for l in cp)]
if missing:
    raise SystemExit("Required test classpath entries missing: " + ', '.join(missing))
print("classpath", len(cp), "test classes", len(names), "filters", args.filters or ["all"], flush=True)
java = r"C:\Users\lu158\dsh-qqpet\tools\android-build\jdk21\jdk-21.0.12.1+1\bin\java.exe"
argfile = r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\junit.args"
lines = ["-Djava.io.tmpdir=C:/Users/lu158/dsh-qqpet/VirtualDisplay/.build-tmp", "-cp", ";".join(cp), "org.junit.runner.JUnitCore"] + names
open(argfile, "w", encoding="mbcs", newline="\n").write("\n".join(lines) + "\n")
r = subprocess.run([java, "@" + argfile], capture_output=True,
                   cwd=r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\app")
sys.stdout.buffer.write(r.stdout)
sys.stdout.buffer.write(r.stderr)
if args.log:
    from pathlib import Path
    log_path = Path(args.log)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text((r.stdout + r.stderr).decode('utf-8', errors='replace') +
                        '\nEXIT ' + str(r.returncode) + '\n', encoding='utf-8')
print("EXIT", r.returncode)
raise SystemExit(r.returncode)
