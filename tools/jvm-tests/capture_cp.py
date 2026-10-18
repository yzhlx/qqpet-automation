import os, shutil, subprocess, time

src = r"C:\Users\lu158\dsh-qqpet\tools\android-build\gradle-cache\.tmp"
dst = r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp\captured-classpath.txt"
env = dict(os.environ)
env.update({
    "JAVA_HOME": r"C:\Users\lu158\dsh-qqpet\tools\android-build\jdk21\jdk-21.0.12.1+1",
    "GRADLE_USER_HOME": r"C:\Users\lu158\dsh-qqpet\tools\android-build\gradle-cache",
    "JAVA_TOOL_OPTIONS": "-Djava.io.tmpdir=C:/Users/lu158/dsh-qqpet/VirtualDisplay/.build-tmp",
    "TMP": r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp",
    "TEMP": r"C:\Users\lu158\dsh-qqpet\VirtualDisplay\.build-tmp",
    "REAL_AIDL": r"C:\Users\lu158\dsh-qqpet\tools\android-build\sdk\build-tools\36.0.0\aidl.exe.orig",
})
proc = subprocess.Popen(
    [r"C:\Users\lu158\dsh-qqpet\tools\android-build\gradle\gradle-9.3.1\bin\gradle.bat",
     ":app:testDebugUnitTest", "--offline", "--no-daemon", "--console=plain",
     "--tests", "com.ynk.virtualdisplay.SessionPlanTest"],
    cwd=r"C:\Users\lu158\dsh-qqpet\VirtualDisplay", env=env,
    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
)
found = None
deadline = time.time() + 150
while time.time() < deadline and proc.poll() is None:
    try:
        names = [n for n in os.listdir(src) if n.startswith("gradle-worker-classpath")]
    except FileNotFoundError:
        names = []
    if names:
        found = os.path.join(src, names[0])
        shutil.copyfile(found, dst)
        break
    time.sleep(0.2)
proc.wait()
print("copied" if found else "not-found", "exit", proc.returncode)
