# 兑换与页面识别证据索引（2026-10-12 游戏 UI 更新适配）

本次会话将历史 Agent-ADB 流程固化进 App 的过程中，QQ 宠物游戏客户端发生了 UI 更新，
导致原模板/坐标全部失配（连续 INITIAL_PAGE_UNKNOWN 与 EXCHANGE/PAGE_MISMATCH 停机）。
本文档索引全部实测证据帧、新探针几何、判别分数与验证方式。

## 一、游戏 UI 变更清单（实测）

| 变更点 | 旧版 | 新版（2026-10-12） | 证据帧 |
|---|---|---|---|
| 房间「出门」按钮 | 纵向漂移（y1354↔y1332） | 位置不稳定，room 探针弃用该区域 | `d4am.png` |
| 食物页 | 旧喂食板直喂 | 猫居中 + 狗屋/食盆站 + 底部「兑换食物」气泡（仅库存空时显示） | `ef.png` |
| 兑换弹窗 | 旧 dialog 结构 | 底部 bottom-sheet：标题 ~(255-430,845-895)、产品卡 (30-205,975-1200)、qty 步进器 minus(517,1277)、支付按钮中心 (360,1425) | `fa.png`（食物）/`sheets.png`（洗护） |
| 购买后行为 | 购买即喂食 | **购买后饼干放在盘子上等待，需再点一下 (360,1300) 才进食**；等待期间「兑换食物」气泡消失 | `afterpay.png` |
| 上课页面 | 教室页（learning 探针 ROI） | 户外地图 + 底部「正在学习/剩余」面板，面板在开课后明显延迟才滑出 | `lrn.png`、`lrn2.png` |
| 成绩单页 | — | 「宠物成绩单」，旧 resultProbe (225,234,500,284) 仍命中 1.0 | `res1.png` |

## 二、新模板资产与 ROI

| 资产 | ROI (720×1600) | 自分 | 负样本（同 ROI 得分） |
|---|---|---|---|
| `pet_experimental/room.png` | 左侧图标列 (40,120,130,560) | 0.9991 | 跨会话稳定；出门按钮区域 0.876→0.963 漂移历史弃用 |
| `pet_mvp/food_scene.png` | 狗屋+食盆站 (480,690,690,960) | 1.0 | bath 0.840 / room 0.850 / sheet 开 0.800 / 购买后等待 0.996 |
| `pet_exchange/dialog_food_title.png` | (255,845,430,895) | — | 与 soap title 同几何 |
| `pet_exchange/dialog_soap_title.png` | (255,845,430,895) | — | — |
| `pet_exchange/food_cookie_selected.png` | (30,975,205,1200) | — | — |
| `pet_exchange/soap_soap_selected.png` | (30,975,205,1200) | — | — |
| `pet_mvp/learning_initial.png` | 面板头 (60,925,420,1000) | 1.0 | room 0.809 / course-detail 0.855 |
| `pet_mvp/learning_expanded.png` | (330,925,530,1000) | — | room 0.844 / course-detail 0.891 |
| `pay_digits.txt` 追加 0/2/5 | — | 同字 0.915 | 交叉字 ~0.55 |

matcher 公式（离线验证用）：24×24 下采样后 `1 - sum(|a-b|)/(24*24*3*255)`，阈值 0.97。

## 三、数字读取（DialogDigitReader）

- `QTY_ROI_W` 74→70（ROI 555..625）；`PAY_ROI_W` 145→50（ROI 395..445）。
- 旧 ROI 抓到步进器 plus 圆环边缘导致 DIALOG_ROI_SIZE 软性 unknown 循环（EXCHANGE_QUANTITY_STEPS:12 停机 2 次）。
- 支付金额 ROI 曾含按钮底部高光行（全列 bright），收敛到 (395,1383,445,1413) 后离线重放 `fa.png` 读出 "25" 置信 1.0。
- 单元测试同步更新 ROI 宽度断言（173 tests 全绿）。

## 四、现场停机记录 → 修复对照

| 停机原因 | 根因 | 修复 |
|---|---|---|
| INITIAL_PAGE_UNKNOWN ×4 | 出门按钮 ROI 漂移 | room 探针换图标列 + matcher 纵向漂移重试 |
| PAGE_MISMATCH enter-food-after 0.911 | 猫停留在旧 cushion ROI 上 | 食物页身份改狗屋站 |
| EXCHANGE_QUANTITY_STEPS:12 ×2 | qty ROI 捕获 plus 边缘 | ROI 收敛 + QTY_ROI_W=70 |
| EXCHANGE_TOTAL_UNREADABLE ×2 | 支付 ROI 含按钮外白底/高光、字体更新低置信 | ROI 收敛 + 重录 0/2/5 掩码 |
| EXCHANGE_PAY_UNCONFIRMED | 购买后饼干放盘上、气泡消失 | 身份探针改狗屋站 + 喂食加 tap(360,1300) |
| LEARNING_NOT_CONFIRMED ×4 | 教室页被地图+面板替代；面板延迟出现；window 8s/30s 不够 | learning 探针移至面板头 + deadline 120s |

## 五、当前全链路状态（真机验收）

已通过的单段闭环（session 1791776598728 起，evidence 文件名对应）：
喂食（含库存空→sheet 购买→进食，`exchange-after-pay.png`、`feed-health.png`）
→ 清洁（肥皂拖拽 + 洗护 sheet 购买，`clean-health.png`）
→ 户外→学园→选课→上课（`course-before-start.png`）
→ 成绩单 B+、学分+10（`res1.png`）。
183→173 单元测试全绿；`BUILD SUCCESSFUL` 多轮。

**当前唯一阻塞：金币余额 8，课程学费 22/节**（提示「你的金币不足，请打补充金币」，
`now4s.png`）。喂食/清洁/兑换代码路径均已验证；金币充值需 QQ 支付人工完成，
充值后可直接重跑 10 轮批次（app 内自主，无 ADB 代打）。
