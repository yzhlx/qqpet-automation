# Setup baseline — 2026-10-21

范围：T001的历史工具基线与本地仓库身份。本轮只读本地文件、原图及git元数据；未调用Android工具、adb、安装器、Gradle或JUnit。所有设备状态均为此前会话记录，不代表当前在线状态。

## 已有Android工具基线（历史记录）

来源：[research.md R1](../research.md#r1-android工具缺失的根因与修复已完成)。此前active desktop profile的唯一`dsh-android`覆盖项为disabled:true，改false后热加载，实际调用过以下工具：

| 工具 | 历史返回 | 本段是否重跑 |
| --- | --- | --- |
| android_devices | DKS9K23420020242、HUAWEI FOA-AL00、Android12、在线 | 否 |
| android_app_info | com.ynk.virtualdisplay，1.0.60/versionCode60，running=true | 否 |
| android_ui_tree | 返回层级；总屏1084×2412，主体节点720×1600，QQ含继续学习/分享 | 否 |

配置诊断入口：[check_android_enabled.py](../../../tools/diagnostics/check_android_enabled.py)。配置PASS与设备当前可用是不同结论；本段不复改插件或设备。插件默认截图/投流不指定display4，不能以混合尺寸层级转换点击。宠物VD4和物理屏控制台须分别确认。

## 当前本地源码身份（本段实际读取）

- `git -C VirtualDisplay rev-parse HEAD`：`d95c320398efb74247e1137c7aae685a297fba7a`。
- 既定分支记录：`feature/qqpet-mvp-session`；本段未切换分支、未提交。
- 工作树dirty：已有automation/UI/测试多处修改及未跟踪源码、`.foreman/`、`phys-r2.png`。本段未修改这些文件，不能以HEAD单独代表当前源码。
- 本段没有新构建，不能把现有APK等同于当前所有dirty源码。

## 交接APK与测试基线（历史记录）

- reenter APK：`VirtualDisplay/.build-tmp/qqpet-reenter-20261021.apk`；历史SHA-256 `01bf95477ca783e4c5728510e3a0f1239e0f4610f7b39b6dfafbc1306e42a7ac`。
- 历史设备证书SHA-256：`a63a225405105cd99ab3a31621ab455c8e917ab8d703638fcc881e80a325cf77`。
- 本地[junit-reenter.log](../../../VirtualDisplay/.build-tmp/junit-reenter.log)实际内容：`OK (408 tests)`、`classpath 191 tests 32 missing []`、`EXIT 0`。这是旧编译产物的运行结果，不是本轮新源码或设备验收。
- 历史安装记录为覆盖安装Success、数据保留；本段没有重读包/签名或安装状态。

## 历史s21局结果（Setup本地证据确认；非当前最近运行）

证据目录：[s21-reenter](../../../VirtualDisplay/.build-tmp/device-acceptance/s21-reenter/)。sessionId=`b2e3dda0-1d31-4026-887c-ab46cbff32a7`，verdict STOPPED、0/1，stopReason=`LEARNING_NOT_CONFIRMED_AFTER_START`。run-record的terminalState为FAILED；这与verdict STOPPED是两个文件各自的状态措辞，不应改写为成功。

开课前原图显示：田径运动课30分钟、学费66、金币81、体力100、清洁100、去上课蓝按钮可见。课程还显示消耗体力15、清洁6：这是消耗观测，不等于把护理目标或这两项消耗直接作为STUDY硬门槛。详见[诊断](study-start-diagnosis.md)。

## gitignore检查（只报告，不修改）

读取[仓库.gitignore](../../../VirtualDisplay/.gitignore)及实际`git check-ignore`：

- `.build-tmp/cp.lst`和`app/build/outputs/apk/debug/app-debug.apk`已被排除。
- `.foreman/tmp/diagnostic.keystore`未被现有规则排除；当前git status显示`.foreman/`未跟踪。签名临时材料应始终finally删除，提交前还需lead审查ignore/暂存范围；本段不改规则、不读取私钥内容。
- `phys-r2.png`在本段最初git检查时是未跟踪原始图片，原规则没有覆盖仓库根该文件。不要批量`git add .`；不能用全局忽略PNG伤及测试夹具。
- 后续lead已修复上述ignore缺口。本段重新读取`.gitignore`确认追加`/.foreman/`、`/phys-*.png`、`__pycache__/`、`*.pyc`、`*.keystore`、`*.jks`、`.env*`。这是lead的改动，本任务未改规则；临时签名材料仍须finally清理，ignore不替代清理。
- lead同时修复`tools/jvm-tests/run_tests.py`的真实退出码传播，本段重新读到`raise SystemExit(r.returncode)`；历史README与旧日志关于“只打印EXIT”描述已过时，新构建按[build-guide](build-guide.md)执行。

## 尚未验证

当前设备在线/当前页面、当前已安装包是否等同旧reenter包、新源码编译、全部独立/组合场景。T001只固定证据基线，不代表任何新产品验收通过。

## 2026-10-21 20:32 当前状态补记（覆盖“最近/当前”历史措辞）

lead已报告最新候选SHA-256 `1b00d258d88ac03805bc70dc961b494c29cacbe593d3272d96a8e7f01a1dfc44`、JUnit450通过，并实际运行WORK runId13711e66-246b-4892-a1ab-459fc190e0a7。20:10:25单次派发、20:10:27开工、20:20:30完成观察、20:20:31同工资页award68确认，20:20:32 DISMISS_BUTTON_NOT_LOCATED停止。核心打工/工资通过，整体收尾、US1和三独立/七组合验收未通过；详见[当前结果](results.md)。

五规则仍未全量生产接线，STUDY仍强制读取健康、统一runner/七组合基础未实现。本段文档复核未操作设备或执行构建。旧16:19成绩单不能归属s21 attempt，不能证明旧局成功或页面未被触碰；此前相关过度推断已在[诊断](study-start-diagnosis.md)撤回。
