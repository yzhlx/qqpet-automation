# Current-source build and verification guide

日期：2026-10-21。以下命令仅供lead后续执行；本任务没有执行编译、JUnit、签名或安装。工作目录为 `C:/Users/lu158/dsh-qqpet/VirtualDisplay`（现有ASCII联接），源仓库HEAD/dirty状态见[baseline](baseline.md)。

## 已读配方及必须修正的陷阱

- [compile_reenter.py](../../../VirtualDisplay/.build-tmp/compile_reenter.py)建立JAVA_HOME/Gradle缓存/SDK/tmp环境，但只调用assembleDebug及compileDebugUnitTestKotlin；新验证必须显式加bundleDebugClassesToCompileJar，不能测旧runtime jar。
- [install_aidlwrap.py](../../../VirtualDisplay/.build-tmp/install_aidlwrap.py)使用`.build-tmp/aidlwrap.exe`替换SDK aidl，要求aidl.exe.orig存在；[restore_aidl.py](../../../VirtualDisplay/.build-tmp/restore_aidl.py)从.orig还原。恢复必须放finally。
- [dumpcp.init.gradle](../../../VirtualDisplay/.build-tmp/dumpcp.init.gradle)在testDebugUnitTest的doFirst写cp.lst，然后故意抛`classpath dumped`；这是本机提取classpath的已读入口，不是完整单测命令。
- [run_tests.py](../../../VirtualDisplay/tools/jvm-tests/run_tests.py)读取UTF-8 cp.lst，过滤不存在条目，枚举Test.class，以mbcs argfile从app目录启动JUnit。原先只打印JUnit EXIT；本轮lead已添加`raise SystemExit(r.returncode)`，本段重新读取源码确认，因此现在外层会传播真实JUnit失败。仍需核对OK、非零测试数和新classpath，不能仅看shell exit0。
- **输出格式更正（2026-10-23，spec 002 T014）**：该权威 runner 现打印 `classpath <n> test classes <m> filters [...]`（支持类名 glob 过滤与 `--log`），**不再是**旧行的 `classpath <n> tests <m> missing []`。本指南 §2 的校验正则已同步为新格式；沿用旧正则会恒判 `JUnit not proven passing`。注意 `.build-tmp/run_tests.py` 是**旧副本**（无 glob 过滤、无 `--log`、只打印 EXIT 不传播退出码），**不得用于判定**。
- 现有cp.lst有192行；旧日志实际使用191条、32个测试类、408个测试。不能将这组数当新代码固定验收值。

## 1. 编译当前源码并刷新classpath

先确认本地wrapper、aidl原件、Gradle wrapper与ASCII联接存在。以下PowerShell代码只在本次进程设置环境；不改系统配置，不读取/输出任何签名口令。

```powershell
$repo = 'C:/Users/lu158/dsh-qqpet/VirtualDisplay'
$tools = 'C:/Users/lu158/dsh-qqpet/tools/android-build'
$py = 'C:/Users/lu158/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/python.exe'
$env:JAVA_HOME = "$tools/jdk21/jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME = "$tools/gradle-cache"
$env:ANDROID_HOME = "$tools/sdk"
$env:ANDROID_USER_HOME = "$repo/.build-tmp/android-user"
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$repo/.build-tmp"
$env:TEMP = "$repo/.build-tmp"
$env:TMP = "$repo/.build-tmp"
$env:REAL_AIDL = "$tools/sdk/build-tools/36.0.0/aidl.exe.orig"
$env:QQPET_SOURCE_REPO = $repo
$env:MSYS_NO_PATHCONV = '1'
Push-Location $repo
try {
    & $py -I .build-tmp/install_aidlwrap.py
    if ($LASTEXITCODE -ne 0) { throw 'aidl wrapper install failed' }
    & ./gradlew.bat :app:assembleDebug :app:compileDebugUnitTestKotlin :app:bundleDebugClassesToCompileJar --console=plain -Dkotlin.compiler.execution.strategy=in-process *> .build-tmp/current-build.log
    if ($LASTEXITCODE -ne 0) { throw 'build failed: inspect current-build.log' }

    $dumpStarted = Get-Date
    & ./gradlew.bat -I .build-tmp/dumpcp.init.gradle :app:testDebugUnitTest --rerun-tasks --console=plain -Dkotlin.compiler.execution.strategy=in-process *> .build-tmp/current-cp-dump.log
    $dumpLog = Get-Content .build-tmp/current-cp-dump.log -Raw
    if ($dumpLog -notmatch 'classpath dumped') { throw 'classpath task did not reach expected dump point' }
    if (!(Test-Path .build-tmp/cp.lst) -or (Get-Item .build-tmp/cp.lst).LastWriteTime -lt $dumpStarted) { throw 'cp.lst was not freshly generated' }
    # This one Gradle invocation is intentionally nonzero: the init script throws after dumping.
    # Any other build failure remains a failure; do not treat arbitrary nonzero output as expected.
} finally {
    & $py -I "$repo/.build-tmp/restore_aidl.py"
    Pop-Location
}
```

`--rerun-tasks`使doFirst确实执行；这是取classpath的诊断开销，不是另一份成功JUnit证据。若后续创建更直接的printClasspath任务，可替换此步骤，但本段不改业务Gradle配置。

将cp.lst中main jar强制换成本次compile jar，并检查关键条目（仍从ASCII repo目录执行）：

```powershell
$cpFix = @'
from pathlib import Path
root = Path('C:/Users/lu158/dsh-qqpet/VirtualDisplay')
p = root / '.build-tmp/cp.lst'
lines = p.read_text(encoding='utf-8-sig').splitlines()
main = root / 'app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar'
assert main.is_file(), 'fresh main compile jar missing'
lines = [x.strip() for x in lines if x.strip() and 'runtime_app_classes_jar' not in x and 'compile_app_classes_jar' not in x]
missing = [x for x in lines if not Path(x).exists()]
lines = [x for x in lines if Path(x).exists()] + [str(main)]
lines = list(dict.fromkeys(lines))
assert any('compileDebugUnitTestKotlin' in x and Path(x).is_dir() for x in lines), 'unit test classes missing'
assert any('junit-4.13.2.jar' in x for x in lines), 'JUnit missing'
assert any('android.jar' in x.lower() for x in lines), 'mockable Android jar missing'
p.write_text('\n'.join(lines) + '\n', encoding='utf-8')
print('classpath refreshed', len(lines), 'discarded missing optional paths', len(missing))
'@
$cpFix | & $py -I -
if ($LASTEXITCODE -ne 0) { throw 'classpath validation failed' }
```

必须检查new-build日志为BUILD SUCCESSFUL；单纯classpath存在不证明编译成功。若主compile jar在dump重编译后更新，该同一路径会指向最新产物。

## 2. JUnit：核对真实退出码和有效测试输出

保持`$repo/$py`为上一节值；runner本身会将Java cwd置为app/以加载测试资产。

```powershell
Push-Location $repo
try {
    $unitOutput = & $py -I tools/jvm-tests/run_tests.py 2>&1
    $runnerExit = $LASTEXITCODE
    $unitOutput | ForEach-Object { Write-Output $_ }
    $text = ($unitOutput | ForEach-Object { $_.ToString() }) -join "`n"
    if ($runnerExit -ne 0 -or $text -notmatch '(?m)^OK \([1-9][0-9]* tests?\)' -or $text -notmatch '(?m)^EXIT 0\s*$' -or $text -notmatch '(?m)^classpath [0-9]+ test classes [1-9][0-9]* filters') {
        throw 'JUnit not proven passing; inspect actual output and fresh classpath'
    }
} finally { Pop-Location }
```

保存新结果与构建身份到场景记录；不把旧408重打印算新跑。JSON往返/AtomicFile迁移仍需设备集成验证，mockable android.jar的org.json/Rect桩不支持这些证明。

## 3. 签名与保留数据覆盖安装：lead执行边界

已读[sign_reenter.py](../../../VirtualDisplay/.build-tmp/sign_reenter.py)和[install_reenter.py](../../../VirtualDisplay/.build-tmp/install_reenter.py)。前者有临时ASCII钥匙副本、文件口令、zipalign、apksigner verify和finally删除；其原输出`qqpet-reenter-20261021.apk`已经存在，脚本会主动拒绝覆盖，**不能直接重跑来签新候选**。后者硬编码旧APK，也不能用于新候选安装。

lead应复制已批准签名配方到新临时脚本并仅替换output/aligned/report名称，用明确的新构建标签；保留原钥匙来源、文件口令、指纹验证及finally清理，不在命令行/文档打印口令。本段不生成/执行签名脚本或复制钥匙。

签名后只读验证命令（`$candidate`须先赋实际新APK绝对路径）：

```powershell
& "$tools/jdk21/jdk-21.0.12.1+1/bin/java.exe" -jar "$tools/sdk/build-tools/36.0.0/lib/apksigner.jar" verify --verbose --print-certs $candidate
if ($LASTEXITCODE -ne 0) { throw 'signature verification failed' }
Get-FileHash -LiteralPath $candidate -Algorithm SHA256
```

证书预期`a63a225405105cd99ab3a31621ab455c8e917ab8d703638fcc881e80a325cf77`。SHA-256和签名报告先记入新构建记录，再由lead对明确候选执行保留数据覆盖安装：

```powershell
& 'C:/Users/lu158/AppData/Local/Android/Sdk/platform-tools/adb.exe' -s DKS9K23420020242 install -r $candidate
```

此命令仅是未来lead步骤，本段从未执行。安装成功、包版本/数据保留、VD4现场恢复都应另取真实证据；不得卸载清数据，不复用carebar2旧包，不以“安装成功”代替功能通过。

## 4. 清理与仓库边界

finally还原SDK aidl；核对临时钥匙/口令文件确实删除，只保留非秘密签名报告。本段最初发现的`.foreman/`及根`phys-r2.png`ignore缺口已由lead修复，本段重新读到对应规则（见baseline）；本任务没有改.gitignore、业务源码或暂存文件。ignore不能替代临时签名材料清理。

## 2026-10-21 20:32 最新执行记录（lead回报，非本文重跑）

- 当前候选`VirtualDisplay/.build-tmp/qqpet-resource-rules-20261021.apk`，SHA-256=`1b00d258d88ac03805bc70dc961b494c29cacbe593d3272d96a8e7f01a1dfc44`；旧cb29…bdfb7和446/448项测试为历史阶段，不作为最新身份。
- lead回报新JUnit450通过。本次文档修订仅只读看到compile-latest.log的BUILD SUCCESSFUL和resource-rules-signature.txt的v3/证书验证，没有运行构建/测试/安装。
- 候选已用于独立WORK runId13711e66-246b-4892-a1ab-459fc190e0a7：一次派发、实际开工和同场award68已确认，20:20:32停于DISMISS_BUTTON_NOT_LOCATED；核心通过不等于整体独立WORK或US1通过。
- 五项规则仍未完全接入生产；STUDY仍有强制健康读取，统一runner/七组合基础尚未实现。后续代码变更必须重新编译/测试/签名并核候选身份，不能复用本次450通过替未构建修改背书。
