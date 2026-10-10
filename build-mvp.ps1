param([string[]]$Tasks = @('test', 'assembleDebug'))
$ErrorActionPreference = 'Stop'
$taskWorkspace = Split-Path -Parent $PSScriptRoot
$taskTemp = [System.IO.Path]::GetTempPath()
$taskTools = Join-Path $taskTemp 'QQPetMvpTools'
$taskProject = Join-Path $taskTemp 'QQPetMvpAscii'
if (!(Test-Path -LiteralPath (Join-Path $taskTools 'jdk'))) {
    throw 'Local JDK 21 / Gradle 9.3.1 preparation is required; see MVP-VALIDATION.md.'
}
# Native Windows AIDL emits paths using the system code page. Use real ASCII
# copies, including SDK and Gradle cache, rather than a directory junction.
$taskPython = @'
import pathlib, shutil, subprocess, sys, tempfile
source = pathlib.Path(sys.argv[1]).resolve()
target = pathlib.Path(tempfile.gettempdir()) / "QQPetMvpAscii"
pin = "0390d8f07ac4071658e77bff9b0974c57f16a186"
for repo in (source, target):
    if subprocess.run(["git", "-C", str(repo), "merge-base", "--is-ancestor", pin, "HEAD"]).returncode != 0:
        raise RuntimeError("Build source or prepared mirror does not descend from the pinned baseline")
shutil.copytree("\\\\?\\" + str(source), "\\\\?\\" + str(target),
                dirs_exist_ok=True, ignore=shutil.ignore_patterns("build", ".gradle", ".git", "deliverables"))
# copytree merges but never deletes, so a file removed from the source would linger in the mirror
# and still be compiled. Prune only the source trees that are actually mirrored this way.
prune_roots = (pathlib.Path("app") / "src",)
for root in prune_roots:
    src_root = source / root
    dst_root = target / root
    if not dst_root.is_dir():
        continue
    for path in sorted(dst_root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        rel = path.relative_to(dst_root)
        if path.is_dir():
            if not path.iterdir():
                path.rmdir()
        elif not (src_root / rel).exists():
            path.unlink()
sdk = pathlib.Path(tempfile.gettempdir()) / "QQPetMvpSdk"
(target / "local.properties").write_text("sdk.dir=" + sdk.as_posix() + "\n", encoding="ascii")
'@
# Windows PowerShell 5.1 strips embedded double quotes from `-c` arguments, which
# corrupts adjacent PowerShell here-strings. Hand the identical script to Python
# through a real file so the build works under both 5.1 and 7.x.
$taskScript = Join-Path $taskTemp 'QQPetMvpMirror.py'
Set-Content -LiteralPath $taskScript -Value $taskPython -Encoding UTF8
# Gradle and the JVM write ordinary progress text to stderr. Under
# $ErrorActionPreference='Stop' Windows PowerShell 5.1 turns that native stderr
# into a terminating error, so relax the preference around these two calls only.
$taskStrict = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & python3.12.exe $taskScript $PSScriptRoot 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw 'Build mirror synchronization failed' }
} finally { $ErrorActionPreference = $taskStrict }
$env:JAVA_HOME = (Get-ChildItem (Join-Path $taskTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:GRADLE_USER_HOME = Join-Path $taskTools 'cache'
$env:ANDROID_USER_HOME = Join-Path $taskTools 'android-user'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true'
$env:QQPET_SOURCE_REPO = $PSScriptRoot
foreach ($taskName in $Tasks) {
    $ErrorActionPreference = 'Continue'
    try {
        & (Join-Path $taskProject 'gradlew.bat') -p $taskProject $taskName '-Pkotlin.incremental=false' --no-daemon --console=plain 2>&1 |
            ForEach-Object { "$_" } | Tee-Object -FilePath (Join-Path $taskTemp "slice2-$taskName-build.txt")
    } finally { $ErrorActionPreference = $taskStrict }
    if ($LASTEXITCODE -ne 0) { throw "Gradle $taskName failed: $LASTEXITCODE" }
}
$taskDeliverables = Join-Path $PSScriptRoot 'deliverables'
New-Item -ItemType Directory -Path $taskDeliverables -Force | Out-Null
$taskApk = Join-Path $taskProject 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path -LiteralPath $taskApk) {
    Copy-Item -LiteralPath $taskApk -Destination (Join-Path $taskDeliverables 'qqpet-mvp-debug.apk') -Force
}
$taskTestApk = Join-Path $taskProject 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk'
if (Test-Path -LiteralPath $taskTestApk) {
    Copy-Item -LiteralPath $taskTestApk -Destination (Join-Path $taskDeliverables 'qqpet-mvp-gates.apk') -Force
}
