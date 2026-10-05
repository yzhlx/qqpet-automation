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
sdk = pathlib.Path(tempfile.gettempdir()) / "QQPetMvpSdk"
(target / "local.properties").write_text("sdk.dir=" + sdk.as_posix() + "\n", encoding="ascii")
'@
& python3.12.exe -c $taskPython $PSScriptRoot
if ($LASTEXITCODE -ne 0) { throw 'Build mirror synchronization failed' }
$env:JAVA_HOME = (Get-ChildItem (Join-Path $taskTools 'jdk') -Directory | Select-Object -First 1).FullName
$env:GRADLE_USER_HOME = Join-Path $taskTools 'cache'
$env:ANDROID_USER_HOME = Join-Path $taskTools 'android-user'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Djava.net.preferIPv4Stack=true'
foreach ($taskName in $Tasks) {
    & (Join-Path $taskProject 'gradlew.bat') -p $taskProject $taskName --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle $taskName failed: $LASTEXITCODE" }
}
$taskDeliverables = Join-Path $PSScriptRoot 'deliverables'
New-Item -ItemType Directory -Path $taskDeliverables -Force | Out-Null
$taskApk = Join-Path $taskProject 'app\build\outputs\apk\debug\app-debug.apk'
if (Test-Path -LiteralPath $taskApk) {
    Copy-Item -LiteralPath $taskApk -Destination (Join-Path $taskDeliverables 'qqpet-mvp-debug.apk') -Force
}
