# 独立护理（CARE-only）真机验收 — 2026-10-21/22

## 2026-10-22 12:43 复验：库存优先 + 逐笔真钱包 + 后缀学费保护生效

构建：`.build-tmp/qqpet-resource-rules-20261022-003943-483868.apk`，SHA-256 `9c3ff66dc939f197894b4f1a150c84a01f036cda1b69398c5541b20af8642153`，证书 `a63a2254…cf77`，保数据覆盖安装；全量 **643 项 JVM EXIT 0**。

- run `f608ec60-8667-4505-8df7-6c0dc338075a`，mode=care，bundle `continuation-20261022-004332-774590-independent-care-final`。
- 终态 `verdict=CARE_PASS`、`run-record terminalState=COMPLETED`、stopReason 为空。
- 结束时体力 **100**、清洁 **100**、心情 32（均为 DIGIT_RECOGNITION，`round-1-ready.png`）。
- 本次喂食 1 次（`feed_count=1`，35.2 秒），清洁 0 次（清洁在上一局已达 100），`courseStarts=0`、预算 100→100 **未扣游戏分钟**。
- 库存判据：食物场景用「兑换食物」气泡证明 **Known(0)**（`EMPTY_STOCK_SCENE_PROOF`），买后用「盘上有饼干且无气泡」证明 **Known(1)**（`OWNED_STOCK_SCENE_PROOF`）；洗澡场景用「兑换洗澡道具」气泡与蓝色计数标记（`SCENE_COUNTER_LOWER_BOUND`，x=448..471,y=1224..1255 由像素分析实测）。
- 每笔付款前读取**当前兑换弹窗**的钱包数字（`readExchange` 全帧 OCR + 弹窗 ROI 过滤），逐笔不同值，未再用房间缓存。
- 限制：蓝色计数只证明“至少 1 件”，不是精确件数；ML Kit 读不出这种带白描边的蓝色数字，精确件数仍无生产读数。

### 本轮为达成 CARE_PASS 修掉的三处真机缺陷

1. 空库存不再把 Unknown 当 0：需要「兑换气泡 + 场景已确认」才记 Known(0)；未知保持 Unknown 并停止。
2. 兑换弹窗钱包读数从 64×67 小裁剪改为全帧 OCR：裁剪里清晰是「36」却读不到，改全帧后逐笔读出 99/61/56/51/46/41。
3. 护理迭代超过 60 秒后 `cleanGain` 回退路径抛 `HEALTH_STALE`，让已达 100/100 的一局误停：差值改用**记录值**（`recordedEnergy/recordedCleanliness`，不受新鲜度门禁影响），而“是否还要继续消费”的判定改为**先重读新鲜健康**（`isStale()` → `readHealth`），两者分开。

## 场景：独立护理一次（无工作、无学习）— 2026-10-21 早先一次

- APK：`.build-tmp/qqpet-resource-rules-20261021-224402-215836.apk`
- SHA-256：`528c490c281d5ed7ce17f12c8ec17568318b49bb1affbab13b31a8e455c60fa4`，证书 `a63a2254…cf77`，数据保留。

## 场景：独立护理一次（无工作、无学习）

起点体力 58 / 清洁 78。计划 CARE 目标 100/100。

### 证据链（pet_mvp.log，Asia/Shanghai 2026-10-21）

- 日志记录真实购买 COOKIE×1 / 5 币（`exchange-paid total=5`，`origin=feed`），共 5 次、合计25币；喂食体力58→68→78→88→98→100。
- 购买日志存在payGuard调用，但多次日志沿用wallet=287，不能证明每次购买前刷新了真实钱包；数字库存读取未取得生产实证，不能把购买触发解释为已读到库存0。
- 清洁 78→100（`clean` 事件）。
- 23:03:27 `CARE_COMPLETE` 体力100/清洁100/心情26。
- 23:03:27 **`SESSION_STOP CARE_PASS`**。

## 判定

- **独立 CARE窄场景：通过。** 仅确认本次目标100/100达成、5次各5币购买（总25币）与CARE_PASS；未工作、未上课。此结论不代表库存优先、只买缺口、每次刷新钱包或完整资金保护已验收。
- 数字库存观察仍为独立parser/adapter与pure测试，尚无生产接线实证；初始库存0不能由本次购买行为反推。多次购买前日志沿用wallet=287，不能证明逐次刷新真钱包。全部未付后缀学费保护尚未接入生产；独立CARE的tuitionReserve=0不能验证组合场景保护。

## 未覆盖

- 有库存零现金、部分库存只买缺口、Unknown库存处理等生产场景均未取得实证；parser/pure测试不能替代生产接线验证。（2026-10-22 复验已覆盖「Known(0) 才买、买后确认」与「Unknown 停止」，但“零现金但有库存”的正例仍未取证。）
- 每次购买前刷新真钱包、全部未付后缀学费保护：**逐笔真钱包已在 2026-10-22 复验中取得证据**；**全部未付后缀学费保护**仅在组合（方案）路径实现，独立CARE的 tuitionReserve=0 不能验证它。
- 独立STUDY、七组合、按需工作、恢复/异常未验收（独立STUDY 已在 2026-10-22 02:55 取得同 run 1/1 证据，见 us1-study.md）。
