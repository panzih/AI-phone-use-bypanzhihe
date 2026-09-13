# AI 操控安卓手机工具 —— 详细设计文档

> 目标：做一个桌面端（Windows / macOS）工具，通过 ADB 连接安卓手机，循环执行
> 「截图 + 读 UI 树 → 交给多模态大模型 → 解析出操作指令 → 在手机上执行」，
> 让 AI 自主完成用户用自然语言描述的任务。
> 开发阶段跑在桌面上，最终形态是运行在安卓设备上的 App。

---

## ⚠️ 重要更正（2026-09-10 后）

本文档最初写于 V4.1-Flash 发布前后，其中**第 0.2、0.3 节关于模型的数字已经过时**，
依据是已退役的 `deepseek-v4-flash-vision-exp`。请以下面的更正为准：

| 项目 | 本文档旧的（Vision-Exp） | 实际（V4.1-Flash，模型 id `deepseek-flash`） |
|---|---|---|
| 单图 token 上限 | 384 | **1024** |
| 服务端归一目标 | 约 640,000 像素 | **约 1300×1300** |
| 单图大小上限 | 1 MB | **32 MiB**（请求体 48 MiB） |
| `detail` 参数 | 无 | **有**（`low` / `high` / `original` / `auto`） |
| 推荐 temperature | — | **1.0**（官方视觉评测参数） |

**这对设计的影响**：本文档原本的核心论点是
「单图只有 384 token → 纯视觉坐标必然不准 → 必须以 UI 树为主定位手段」。
在 1024 token / 1300×1300 的新模型下，**这个论点的强度大幅下降**：
纯视觉方案已经可用（V4.1-Flash 的 RefCOCO 定位基准得分 86.0）。

因此：
- **UI 树不再是必需的**，而是一个可选的精度增强手段
- 当前实现（`DESIGN.md` 同目录的代码）走的是**纯视觉路线**，符合这个判断
- 第 2.3 节描述的 `uiauto.py` 方案仍然有效，但定位从「必须」降级为「需要更高精度时再加」

下面第 0.2、0.3 节保留了原始分析作为历史记录，请结合本表阅读。

---

## 0. 先说三个决定性的结论

### 0.1 DSH（DeepSeek Harness）不能用作本项目的框架

原始需求里写「基于 DeepSeek Harness Python SDK 开发」。**该 SDK 不存在。** 实测本机安装：

| 项 | 实测结果 |
|---|---|
| 顶层依赖 | `@deepseek-ai/dsh@0.1.5-rc.1` |
| SDK 包 | `@deepseek-ai/dsh-sdk-minimal@0.1.5-rc.1`，入口 `lib/index.js` |
| 依赖树内 `.py` 文件 | **0 个** |
| 运行时 | Cordis（Node.js / TypeScript 插件框架） |
| 包描述 | "The standalone minimal SDK profile bundle: JSON-RPC, one DeepSeek adapter, persistent shell, and JSONL sessions" |

DSH 是一个**编码 agent 运行时**，工具集是读写文件、执行 shell、搜索代码。它不面向 GUI 自动化，
也不提供 Python 绑定。硬要「把手机操作注册成 Harness 的 Tool」，等于让一个编码 agent 去驱动
手机，中间要跨进程跨语言搭桥，收益为零、复杂度翻三倍。

**决策：本项目是独立的 Python 程序，不依赖 DSH。**

### 0.2 可用模型（已过时，见上方更正表）

从官方适配器 `@deepseek-ai/dsh-llm-deepseek` 中确认，模型目录里存在一个多模态路由：

```
id: "deepseek-v4-flash-vision-exp"
name: "DeepSeek-V4-Flash-Vision-Exp"
inputModalities: ["text", "image"]
imagePixelBudget: 640000      // 单请求累计像素预算
imageMaxBytes: 1048576        // 单图编码后 1 MB
```

注意：本机适配器版本是 `0.1.5-rc.1`（8 月版本），**里面还是旧的模型目录**。
实际的 API 端已经迁移到 V4.1-Flash（模型 id `deepseek-flash`），
旧的 `deepseek-v4-flash-vision-exp` 已被服务端转发到新模型。

请求体格式就是标准 OpenAI 视觉格式：

```js
{ type: "image_url", image_url: { url: `data:${mediaType};base64,${base64}` } }
```

### 0.3 ⚠️ 原分析：单张图被压到 384 token（已过时）

> **以下内容是针对已退役的 Vision-Exp 模型的分析，保留作为记录。**
> V4.1-Flash 的实际上限是 1024 token、归一目标约 1300×1300，见上方更正表。

这是整个设计里最容易被忽视、但直接决定成败的一点。

适配器里移植了官方的图像计费算法（注释写明 "ported verbatim" 自 api-docs.deepseek.com
的 Token & Token Usage 页面），关键常量：

```
PATCH_SIZE        = 14      // 视觉 patch 边长 14px
DOWNSAMPLE_RATIO  = 3       // 每轴再 3:1 下采样
MAX_IMAGE_TOKENS  = 384     // 单图 token 上限
MIN_PIXELS        = 384*384 // 小于此值会被放大
MAX_WIDTH_HEIGHT_RATIO = 8  // 宽高比上限
```

**算一笔账。** 一台 1080×2400 的手机，截图 2,592,000 像素。预算 640,000 像素，
所以会被缩到约 **0.497 倍**，即 **537×1193**。再叠上 14px patch + 3:1 下采样，
每轴有效网格约为 `537/(14*3) ≈ 12.8` 格。

也就是说：**模型看到的手机屏幕，横向只有十几个格子。** 这个分辨率足够判断「这是一屏
什么内容、该点哪个大致区域」，但**不足以可靠地读出小号中文文字，更不足以精确到像素坐标**。

结论有三条，后面所有设计都由它推导出来：

1. **绝不让模型直接输出像素坐标。** 537px 宽的画面里偏 5 个像素，映射回 1080px 真实屏
   就是偏 10 个像素以上，加上模型自身的定位误差，点击必然失手。必须走 UI 树拿真实 bounds。
2. **UI 树是主定位手段，视觉是辅助语义理解。** 这与豆包手机的实际做法一致 —— 它读的是
   无障碍节点树，不是靠肉眼猜坐标。
3. **需要看清细节时用裁图放大，而不是缩小整屏。** 对某一区域单独截图并以高分辨率提交，
   让该区域的 token 预算占比最大化。

---

## 1. 总体架构

```
┌──────────────────────────────────────────────────────────┐
│  main.py            CLI 入口：装配依赖、跑主循环、打日志   │
├──────────────────────────────────────────────────────────┤
│  agent.py           Agent 大脑                            │
│    · 系统提示词管理      · 模型输出解析与 schema 校验      │
│    · 任务历史维护        · 防死循环（画面哈希/重复动作）    │
│    · 重试与降级          · 危险动作拦截                    │
├──────────────────────────────────────────────────────────┤
│  llm.py             模型抽象层                            │
│    · OpenAI 兼容 /chat/completions                        │
│    · 多模态消息构造（image_url + data:image/...;base64）   │
│    · 思考强度参数映射（temperature / reasoning_effort）    │
│    · 连通性自检（拿真图打一次真实请求）                     │
├──────────────────────────────────────────────────────────┤
│  uiauto.py          UI 树：uiautomator dump → 节点列表     │
│    · bounds 解析 → 中心点坐标 → 编号化给模型               │
│    · 可交互节点过滤（clickable/scrollable/editable）       │
├──────────────────────────────────────────────────────────┤
│  vision.py          图像处理                              │
│    · 缩放 / 裁剪 / 压缩到预算内（Pillow）                  │
│    · 屏幕坐标 ↔ 归一化坐标换算                            │
├──────────────────────────────────────────────────────────┤
│  tools.py           动作执行器（白名单 + 统一返回）         │
├──────────────────────────────────────────────────────────┤
│  adb.py             ADB 封装（纯 subprocess）             │
│    · 设备枚举/选择  · 截图  · 输入  · 按键  · 启停应用      │
├──────────────────────────────────────────────────────────┤
│  config.py          config.json 加载 + 校验 + 默认值       │
├──────────────────────────────────────────────────────────┤
│  logs/              每步存档：截图 + 模型原文 + 执行结果    │
└──────────────────────────────────────────────────────────┘
```

**分层意图**：`adb.py` 是唯一直接碰 ADB 的模块。将来做安卓端 App 时，把这个文件替换成
Shizuku 实现 或 无障碍服务实现，上面所有层（`tools.py` / `agent.py` / `llm.py`）**一行都不用改**。
这是整个架构最重要的可迁移性设计。

---

## 2. 模块详细设计

### 2.1 `adb.py` —— ADB 封装

只用标准库 `subprocess`，不引入 `adb-shell`、`pure-python-adb` 等第三方库（跨平台兼容坑多）。

```python
class AdbClient:
    def __init__(self, adb_path: str = "adb", serial: Optional[str] = None):
        self.adb = adb_path
        self.serial = serial
```

**核心方法**

| 方法 | 实现命令 | 备注 |
|---|---|---|
| `list_devices()` | `adb devices -l` | 解析出 serial + model，过滤 `unauthorized` / `offline` |
| `_run(args, binary=False)` | — | 统一入口，自动附加 `-s <serial>` |
| `screenshot() -> bytes` | `adb exec-out screencap -p` | **必须用 `exec-out`**：直接返回二进制。用 `adb shell screencap` 会走 PTY，`\n` 会被转成 `\r\n` 从而损坏 PNG，还得再 pull 一次，慢一倍 |
| `screen_size() -> (w, h)` | `adb shell wm size` | 返回 `Physical size: 1080x2400`，正则提取 |
| `density() -> int` | `adb shell wm density` | 备用 |
| `tap(x, y)` | `input tap {x} {y}` | |
| `swipe(x1,y1,x2,y2,ms)` | `input swipe {x1} {y1} {x2} {y2} {ms}` | |
| `long_press(x,y,ms)` | `input swipe {x} {y} {x} {y} {ms}` | 长按 = 起终点相同的 swipe |
| `input_text(text)` | 见 2.1.1 | 中文需要专门处理 |
| `keyevent(code)` | `input keyevent {code}` | BACK=4, HOME=3, ENTER=66, APP_SWITCH=187 |
| `open_app(pkg)` | `monkey -p {pkg} -c android.intent.category.LAUNCHER 1` | 比 `am start` 好，不用知道 Activity 名 |
| `force_stop(pkg)` | `am force-stop {pkg}` | |
| `current_app()` | `dumpsys window \| grep mCurrentFocus` | 判断是否切换成功 |
| `ui_dump() -> str` | `uiautomator dump /sdcard/ui.xml` + `cat` | 见 2.3 |

**Windows 专属处理**：每次 `subprocess.run` 都要带
`creationflags=subprocess.CREATE_NO_WINDOW`，否则每次调用都会闪一个黑色控制台窗口，
一秒钟闪几十次，完全没法用。

**超时**：所有 ADB 调用必须有 timeout（默认 15s，`screencap` 给 30s）。
ADB 卡死是常态，没有 timeout 的程序会永久挂起。

#### 2.1.1 中文输入的三种方案（必须都实现，按序降级）

`adb shell input text` **只支持 ASCII**，直接传中文会静默失败或乱码。这是新手第一个大坑。

```
方案 A（首选）：ADBKeyboard IME
  安装 ADBKeyboard.apk → adb shell ime enable com.android.adbkeyboard/.AdbIME
  → adb shell am broadcast -a ADB_INPUT_B64 --es msg <base64(utf8 text)>
  优点：支持任意 Unicode，稳定，还能发回车
  缺点：需要用户额外装一个 APK

方案 B（降级）：剪贴板 + 粘贴
  adb shell am broadcast -a clipper.set -e text <base64>   # 依赖 Clipper App
  → adb shell input keyevent 279                            # KEYCODE_PASTE
  缺点：同样依赖第三方 App，且部分 ROM 限制后台剪贴板写入

方案 C（兜底）：直接 input text
  仅当文本可 encode('ascii') 时使用；否则明确报错给用户，提示装 ADBKeyboard
```

程序启动时探测当前 IME（`adb shell settings get secure default_input_method`），
自动选择可用方案，并在日志里明确告知用户当前用的是哪种。**绝不静默失败。**

---

### 2.2 `llm.py` —— 模型抽象层

#### 2.2.1 请求体（真实可用的格式）

```python
payload = {
    "model": cfg.model,                       # deepseek-v4-flash-vision-exp
    "messages": [
        {"role": "system", "content": SYSTEM_PROMPT},
        {"role": "user", "content": [
            {"type": "text", "text": "当前屏幕如下。请给出下一步操作。"},
            {"type": "image_url", "image_url": {
                "url": f"data:image/jpeg;base64,{b64}"
            }},
        ]},
    ],
    "temperature": cfg.temperature,
    "max_tokens": cfg.max_tokens,
    "response_format": {"type": "json_object"},   # 支持则开，不支持则自动关掉重试
}
POST {base_url}/chat/completions
Header: Authorization: Bearer {api_key}
```

#### 2.2.2 「思考强度」要分开建模

原需求把 `temperature` 当成思考强度，这不准确。两者是不同东西：

- `temperature` (0–2)：**随机性**。低=稳定可复现，高=发散。
  自动化操作应该**调低**（0–0.3），因为你要的是确定性和格式稳定。
- 真正的「思考强度」是各家不同的开关：
  - OpenAI o 系：`reasoning_effort: "low"|"medium"|"high"`
  - DeepSeek：适配器里是 `thinking: enabled|disabled` + `reasoningEffort: off|low|high|max`
  - 通义/智谱等：`enable_thinking: true|false` 或 `thinking: {type: "enabled"}`

**设计**：config 里两者分开，并加一个 `extra_body` 透传字段兜底：

```json
{
  "temperature": 0.1,
  "reasoning_effort": "high",
  "extra_body": {}
}
```

`extra_body` 里的键值会原样 merge 进请求体。这样不管换哪家模型，
用户都能手动塞进任何厂商私有参数，**不用改代码**。这是「多模型可配」的关键设计。

#### 2.2.3 连通性自检（必须有）

用户配错 base_url / key / 选了个不支持图片的模型，是最常见的失败。
程序启动时先跑一次 `self_check()`：

1. 发一个纯文本的极小请求（`"ping"`, max_tokens=1）→ 验证 URL 与鉴权
2. 生成一张 32×32 的纯色测试 PNG，以 `image_url` 发出 → **验证该模型真的收图**
3. 若第 2 步返回 400/422 且报错提及 image/vision 相关 → 明确提示
   「当前模型不支持图片输入，请换用视觉模型」

不做这一步，用户会卡在莫名其妙的报错上很久。

---

### 2.3 `uiauto.py` —— UI 树（本项目的定位基石）

#### 2.3.1 获取

```bash
adb shell uiautomator dump /sdcard/ui.xml
adb shell cat /sdcard/ui.xml
```

返回 XML，节点形如：

```xml
<node class="android.widget.Button" text="登录"
      resource-id="com.foo:id/login_btn"
      bounds="[120,2100][960,2250]"
      clickable="true" enabled="true" />
```

#### 2.3.2 处理流程

```
原始 XML
  → 解析所有 node
  → 过滤：只保留 clickable="true" / scrollable="true" / 有 text 或 content-desc 的节点
  → 丢弃 bounds 面积 < 阈值（如 20×20）的节点（噪声）
  → 丢弃被完全遮挡的子节点（可选，用 bounds 包含关系粗判）
  → 计算每个节点的中心点 cx=(x1+x2)/2, cy=(y1+y2)/2
  → 按从上到下、从左到右排序，编号 1..N
```

#### 2.3.3 给模型的表示形式

**不要**把原始 XML 丢给模型（又长又吵），而是压缩成表格：

```
[1] 按钮 "登录"          id=login_btn     center=(540,2175) clickable
[2] 输入框 "手机号"       id=phone_input   center=(540,1200) editable
[3] 文本 "忘记密码?"      id=forgot_pwd    center=(880,2300) clickable
[4] 列表                 id=feed_list     center=(540,1400) scrollable
```

模型只需要回 `{"action":"tap","target":1}`。

**为什么这是关键设计**：把「在 537px 模糊画面里猜像素」变成「从编号列表里选一个数字」，
准确率是天壤之别。同时这天然解决了换机型、换分辨率、换 ROM 的问题 —— 坐标永远由
UI 树现场给出，不依赖任何模型的空间推理能力。

#### 2.3.4 视觉与 UI 树的分工

| 场景 | 用什么 |
|---|---|
| 点击可交互控件 | **UI 树**（首选，几乎总是可用） |
| 理解这一屏是什么界面 | 截图（粗粒度足够） |
| 控件无 text/无 id（纯图标、Canvas、游戏、视频） | 截图 + 归一化坐标（降级） |
| 需要读小字详情 | **裁剪该区域单独高分辨率提交** |
| 判断操作有没有生效 | 前后截图哈希对比 + UI 树 diff |

**重要**：有些场景 UI 树会失效 —— Flutter / 部分游戏 / 自定义渲染 —— 此时
`uiautomator dump` 返回的树几乎是空的。程序要能检测到这个情况（节点数 < 3），
自动切到纯视觉模式，并在日志里说明。

---

### 2.4 `vision.py` —— 图像处理

#### 2.4.1 预处理流水线

```
原始 PNG（1080×2400，约 1–3 MB）
  → Pillow 打开，转 RGB
  → 若像素数 > image_pixel_budget(640000)：等比缩小
     缩放因子 = sqrt(640000 / (w*h))
  → JPEG 质量阶梯压缩 85 → 70 → 55 → 40，取第一个 ≤ 1 MB 的结果
  → base64 编码
```

**为什么用 JPEG 不用 PNG**：手机截图用 PNG 经常 2–3 MB（base64 后还要再涨 1/3），
每步都传一次比较浪费带宽。JPEG 在肉眼无差别的画质下体积通常只有 1/3。

**`detail` 参数比本地缩放重要得多**（V4.1-Flash 新增）。官方定义：

| 值 | 行为 | 适用场景 |
|---|---|---|
| `low` | 服务端先降到 512×512 | 只判断「这是哪一屏」，快且省 token |
| `original` / `high` | 保留原图 | **读小字必需**。手机 UI 截图默认用这个 |
| `auto` | 目前等价于 `original` | — |

注意：设成 `low` 会让画面被压到 512×512，手机截图里的按钮文字会直接糊掉 —— 
**这是最容易踩的坑**，配置里默认必须是 `original`。

**加上「网格标尺」**（可配置，默认开）：在缩放后的图上叠加 10×10 的淡色网格线并标注
0–1000 刻度。这能让模型在必须输出坐标时，用千分比而非像素来表达，显著降低误差。
这是很多 GUI agent 项目（如 Set-of-Mark 系列）验证过的技巧。

#### 2.4.2 坐标系统一

内部**只用一种坐标：真实物理像素**（`wm size` 报告的那个）。

- UI 树给的就是物理像素 → 直接用
- 模型给归一化值（0–1000）→ `px = round(v / 1000 * W)`
- 模型给缩放图上的像素 → 先除以缩放因子
- 截图前必须校验实际图尺寸与 `wm size` 一致（折叠屏、旋转、分屏会不一致），
  不一致时以**实际截图尺寸**为准并记录警告

**边界保护**：所有坐标 clamp 到 `[0, W-1]` / `[0, H-1]`，防止越界导致 `input` 报错。

---

### 2.5 `tools.py` —— 动作 schema

#### 2.5.1 统一动作用一套 JSON 描述，每次一条

```json
{
  "thought": "当前在登录页，需要先填入手机号。我看到编号 2 是手机号输入框。",
  "action": "tap",
  "target": 2
}
```

**完整动作白名单**

| action | 必需参数 | 说明 |
|---|---|---|
| `tap` | `target`(节点编号) 或 `x,y` | 优先节点编号；无编号时用 `x,y`(0–1000) |
| `swipe` | `direction`(up/down/left/right) 或 `x1,y1,x2,y2`, 可选 `duration_ms` | 优先方向式，程序按屏幕比例生成起终点 |
| `long_press` | 同 tap，可选 `duration_ms`(默认 1000) | |
| `input_text` | `text` | |
| `open_app` | `package` | |
| `key` | `key`(`back`/`home`/`enter`/`app_switch`) | 用语义名，不用数字 keycode |
| `wait` | `seconds`(默认 1, 上限 10) | |
| `scroll_to` | `target` | 目标不在当前屏时，滚动查找 |
| `finish` | `summary` | **任务完成，退出循环** |
| `fail` | `reason` | **无法完成，退出循环** |

**关键**：`finish` / `fail` 必须是显式动作。否则模型没有「停下来」的出口，
只能靠撞 `max_steps` 上限退出 —— 那样用户永远不知道任务是成功了还是失败了。

#### 2.5.2 防御性解析（模型输出不可信）

模型 100% 会出这些情况，解析器必须全部处理：

```python
def parse_action(raw: str) -> Optional[dict]:
    # 1. 剥离 markdown 围栏 ```json ... ```
    # 2. 找第一个 { 到最后一个 } 的子串（模型爱加前后解释文字）
    # 3. json.loads，失败则本次重试
    # 4. 校验 action 在白名单内，否则拒绝
    # 5. 按 action 校验必需参数存在且类型正确
    # 6. 数字参数做 range clamp
    # 任何一步失败 → 返回 None → 上层带错误信息重试（最多 2 次）
    # 2 次都失败 → 本轮记为「模型输出非法」，用纯文本追问一次
```

**安全红线**：`action` 不在白名单 → **绝不执行**，即使参数字段看起来合理。
不能出现「模型编了个 `shell` 动作，程序就把字符串当命令跑了」这种事。

---

### 2.6 `agent.py` —— 主循环

#### 2.6.1 循环流程

```
step = 0
prev_hash = None
recent_actions = deque(maxlen=5)

while step < cfg.max_steps:
    step += 1

    # 1. 观察
    shot = adb.screenshot()
    img_hash = sha256(shot)
    ui_tree = uiauto.parse(adb.ui_dump())     # 失败则标记 visual_only

    # 2. 死循环检测
    if img_hash == prev_hash:
        stagnant += 1
        if stagnant >= cfg.stagnant_threshold:   # 默认 3
            注入提示：「画面连续 N 步未变化，说明上一步操作无效，请换一种方式」
    else:
        stagnant = 0

    # 3. 问模型
    prompt = build_prompt(task, ui_tree, step, stagnant, recent_actions)
    raw = llm.chat(prompt, image=preprocess(shot))
    action = parse_action(raw)
    if action is None:
        handle_invalid(raw); continue

    # 4. 重复动作检测
    sig = action_signature(action)      # action + 主要参数
    if recent_actions.count(sig) >= cfg.repeat_threshold:  # 默认 3
        注入提示：「你已重复该操作 N 次且无效，必须换策略」

    # 5. 危险动作拦截
    if is_dangerous(action):
        if not confirm_with_user(action):  # 交互式确认 / 或直接 abort
            finish("危险操作被用户拒绝"); break

    # 6. 执行 + 存档
    result = tools.execute(action)
    save_artifacts(step, shot, raw, action, result)

    # 7. 退出条件
    if action["action"] == "finish": break
    if action["action"] == "fail":   break

    recent_actions.append(sig)
    prev_hash = img_hash
    sleep(cfg.wait_after_action)
```

#### 2.6.2 系统提示词要点

```
你是安卓手机自动化助手。你的唯一输出是一个 JSON 对象，不要输出任何其他文字。

当前屏幕的可交互元素如下（编号 → 元素）：
{ui_tree_table}

可用动作：tap / swipe / long_press / input_text / open_app / key / wait /
          scroll_to / finish / fail
（每个动作的参数 schema 在此展开）

规则：
1. 优先使用元素编号（target）而不是坐标。只有当目标元素不在列表中时才用坐标，
   坐标必须是 0–1000 的归一化值。
2. 每次只输出一个动作，等看到执行后的新屏幕再决定下一步。
3. 如果上一步操作后画面没有变化，说明操作无效，必须换一种方式，不要重复。
4. 操作完成时输出 {"action":"finish","summary":"..."}。
5. 无法完成时输出 {"action":"fail","reason":"..."}，不要硬试。
6. 涉及支付、发送消息、删除数据等不可逆操作前，输出 action="confirm"
   并说明意图，等待人工确认。

输出格式（严格遵守）：
{"thought":"...", "action":"tap", "target":2}
```

注意第 6 条 —— 见第 5 节安全设计。

#### 2.6.3 任务历史怎么给模型

**不要把每步的截图都塞进上下文**。V4.1-Flash 虽然单次可带 600 张图、单图 1024 token，
但每轮都带上历史截图会让每步的输入 token 成倍增长，而历史信息用纯文本摘要表达完全够用。

正确做法：
- **只传当前这一张截图**（始终是最新的屏幕状态）
- 历史用**纯文本**记录：`第1步 tap#2(手机号输入框) → 画面已变化` / `第2步 input_text → 成功`
- 这样 token 占用极低，且模型需要的历史信息（做过什么、有没有效）全都在

---

### 2.7 `config.json` 设计

```json
{
  "adb_path": "adb",
  "device_serial": null,

  "base_url": "https://api.deepseek.com/v1",
  "api_key": "",
  "model": "deepseek-v4-flash-vision-exp",

  "temperature": 0.1,
  "reasoning_effort": "high",
  "extra_body": {},
  "max_tokens": 1024,

  "max_steps": 30,
  "wait_after_action": 1.2,
  "stagnant_threshold": 3,
  "repeat_threshold": 3,

  "prefer_ui_tree": true,
  "detail": "original",
  "image_shrink": true,
  "image_pixel_budget": 1690000,
  "image_max_bytes": 4194304,
  "jpeg_quality": 85,
  "draw_grid_overlay": true,

  "confirm_dangerous": true,
  "dangerous_keywords": ["支付", "付款", "转账", "删除", "卸载", "发送", "确认购买"],
  "dangerous_packages": ["com.eg.android.AlipayGphone", "com.tencent.mm"],

  "log_dir": "logs",
  "save_screenshots": true
}
```

设计原则：**所有魔法数字都在这里**，代码里不出现硬编码的阈值。
`api_key` 支持留空并回退读环境变量（`DEEPSEEK_API_KEY` / `OPENAI_API_KEY`），
避免 key 被写进文件后误提交到 git。

---

## 3. Shizuku / 无障碍：端上版迁移路径（重要澄清）

原需求把 Shizuku 和 ADB 当成并行的两条路，实际上它们的关系是这样：

| 阶段 | 运行位置 | 获取权限的方式 | 需要无障碍吗 |
|---|---|---|---|
| **开发版（现在）** | Windows / Mac | USB 或 TCP 连 ADB | **不需要** |
| **端上版 A** | 手机上的 App | **Shizuku** 提供 ADB 级权限 | **不需要** |
| **端上版 B** | 手机上的 App | 无障碍服务（AccessibilityService） | 需要 |

三个要点：

1. **桌面阶段完全不需要 Shizuku，也完全不需要无障碍。** 手机开着 USB 调试插上电脑就行。
   现在就去搞 Shizuku 是纯粹的复杂度浪费。

2. **Shizuku 和 ADB 是同一套东西，只是换了权限来源。**
   Shizuku 通过 `adb shell sh /sdcard/.../start.sh` 或无线调试启动一个特权进程，
   App 通过 binder 调它，等价于拿到 `shell` 用户的 ADB 权限。
   它能做的：`input tap`（通过 `InputManager.injectInputEvent` 或 `sh` 执行命令）、
   `am start`、`pm` 等。
   **它不能做的**：截屏（`screencap` 是 shell 命令，走 Shizuku 执行 shell 命令可行）
   和 **读无障碍节点树** —— 后者是无障碍服务独有的能力。

3. **所以端上版有个真实的技术取舍**：
   - 只上 Shizuku：定位靠**截图 + 视觉**（V4.1-Flash 下这条路线已可用），
     或用 `uiautomator dump`（Shizuku 有权限执行，可行但慢，约 1–2 秒）
   - 上无障碍：能**事件驱动**（不用轮询截图）、能直接读节点树、能精准定位，
     但**无法截屏**（`AccessibilityService.takeScreenshot()` 需要 API 30+ 且用户授权）

   **最优组合是两者都要**：无障碍负责读树和事件监听，Shizuku 负责执行输入和截屏。
   这一点建议现在就写进架构，因为 `adb.py` 的接口设计要同时满足两者。

**为此的架构约束**：`adb.py` 暴露的方法必须只有语义化接口
（`screenshot()` / `tap()` / `ui_dump()`），不暴露任何 ADB 特有的东西
（比如不要把 `input keyevent 279` 这种原始命令泄漏到上层）。
这样端上版两种实现都能干净替换。

---

## 4. 防死循环：光靠 max_steps 不够

`max_steps` 只是最后一道保险。真正需要的是三层：

| 层 | 机制 | 作用 |
|---|---|---|
| 1 | **画面哈希对比** | 操作后画面没变 → 说明点击没生效（点空了/控件不可点）→ 提示模型换策略 |
| 2 | **重复动作检测** | 最近 5 步内同一动作+同参数出现 ≥3 次 → 强制提示「必须换方式」 |
| 3 | **UI 树 diff** | 精确知道界面变没变、变了哪些元素，比哈希更细粒度（可选，二期） |

另外必须记录 **每步的完整存档**：截图文件 + 模型返回原文 + 解析后的动作 + 执行结果。
出问题时这是唯一能定位的途径。存档要带步号和时间戳，方便回放。

**还有一个隐蔽的失败模式**：模型陷入「截图 → 说'我需要先返回上一页' → 按 back → 又进同一个页面」
的循环。第 2 层检测能抓住它。

---

## 5. 安全与风险控制（不能省）

AI 能点屏幕上任何东西，意味着它能：
- 给微信好友发消息
- 点「确认支付」
- 卸载应用 / 清除数据
- 在设置里改权限

**这是真实风险，不是理论风险。** 开发阶段就要有：

1. **危险动作二次确认**（`confirm_dangerous: true`）
   命中 `dangerous_keywords` 或目标 App 在 `dangerous_packages` 里 → 暂停，
   在终端打印 `即将执行：tap "确认支付" (坐标 xxx)，是否继续？[y/N]`，等人工输入。

2. **App 白名单**（可选，默认关）
   只允许操作指定包名的 App，其他一律 `force-stop` 后返回。

3. **完整审计日志**
   每次操作都落盘：时间、动作、目标元素文案、截图路径。
   事后可追溯「AI 到底点了什么」。

4. **人工接管**
   提供一个热键（如终端里按 `Ctrl+C` 之外的 `p`）暂停循环，让用户手动操作，
   再恢复。这在调试时极其有用。

5. **不做的事**
   不要在提示词里让模型"尽量避免危险操作"然后指望它听话 ——
   拦截必须做在**代码里**，基于确定性的规则，不基于模型的自觉。

---

## 6. 已知限制（提前认知，避免踩坑）

| 限制 | 原因 | 缓解 |
|---|---|---|
| 密集小字可能看不清 | 图被服务端归一到约 1300×1300，单图 1024 token。手机长截图按比例缩小后小字会糊 | 开启 UI 树拿 text（通常准确）；或裁剪局部放大后单独提交 |
| 纯视觉坐标有误差 | 依赖模型空间推理（RefCOCO 86.0，不弱但非完美） | 开启 `draw_grid_overlay`；必要时切 UI 树模式 |
| `detail` 设错会毁掉一切 | 设成 `low` 图被压到 512×512，UI 文字直接糊 | 默认 `original`，不要改 |
| 每步耗时 3–8 秒 | 截图 + dump + 模型推理 + 执行 + 等待 | 缓存 UI 树；能事件驱动就事件驱动 |
| 每步成本 | 每步一张图（约 1000 token 上下）+ 文本 | 用 flash 档；只判断界面类型时可用 `detail: low` |
| 模型选错工具 | 本质是概率模型 | 白名单 + schema 校验 + 重试 |
| 折叠屏/旋转导致坐标错位 | 截图尺寸与 `wm size` 不一致 | 每次以实际截图尺寸为准换算 |
| `screencap` 偶发黑图 | 部分 ROM 在 DRM 保护页面禁止截屏 | 检测全黑图 → 提示用户；该页面无解 |
| 部分页面禁止 uiautomator dump | 安全页面（银行、支付） | 明确报错，不硬试 |

---

## 7. 开发路线（建议顺序）

每个阶段结束都能跑，不要一次写完再调。

**阶段 1 — ADB 打通（半天）**
只做 `adb.py` + 一个 `demo.py`：列出设备、截图存盘、点击固定坐标、中文输入。
目标：确认这台电脑和这台手机能通，中文输入方案可用。
**这一步不通，后面全是空谈。**

**阶段 2 — 模型打通（半天）**
`llm.py` + 自检脚本：发一张真截图给 `deepseek-v4-flash-vision-exp`，
问「这一屏是什么界面」，看回答对不对。顺带验证 base_url / key / 图片格式。

**阶段 3 — 单步闭环（1 天）**
`uiauto.py` + `tools.py` + 极简 `agent.py`：人工喂一个任务，跑一步停一步，
每步打印模型想了什么、要做什么。**不要自动循环。**

**阶段 4 — 自动循环（1 天）**
加主循环、防死循环、日志存档。此时可以跑通「打开设置 → 进入 WLAN → 报告当前连接」这类任务。

**阶段 5 — 稳定性与安全（1 天）**
危险动作确认、重试、异常处理、`finish`/`fail` 收敛、错误提示中文化。

**阶段 6 — 端上版（后续）**
把 `adb.py` 换成 Shizuku + 无障碍双实现，复用 `agent.py` / `llm.py` / `tools.py`。

---

## 8. 依赖清单

```
# requirements.txt
httpx>=0.27          # HTTP 客户端，比 requests 更适合（支持 http2、连接池）
Pillow>=10.0         # 图像缩放、JPEG 压缩、网格叠加
```

**为什么依赖这么少**：
- ADB 走 `subprocess`，不需要 `adb-shell` / `pure-python-adb`
- JSON 解析走标准库 `json`，不需要 pydantic
- 没有 UI 框架依赖 —— 一期是 CLI，界面后加

**Python 版本要求 ≥ 3.10**（本机是 3.9.6，太旧，建议装 3.11/3.12）。
如果必须兼容 3.9，类型标注要写 `Optional[X]` 而不是 `X | None`。

**外部依赖（非 pip）**：
- Android Platform Tools（adb）
- 可选：ADBKeyboard.apk（中文输入）

---

## 9. 对原提示词的修正清单

| 原提示词的说法 | 问题 | 修正 |
|---|---|---|
| 基于 DSH Python SDK，复用其工具注册 | **该 SDK 不存在**，DSH 是 Node.js 编码 agent 运行时 | 删除，改为独立 Python 程序 |
| 截图发给 AI 让它给坐标 | 原以为单图只有 384 token、坐标必然不准；**实际 V4.1-Flash 是 1024 token、归一 1300×1300，纯视觉可用** | 保留纯视觉为主路线；UI 树降级为可选精度增强 |
| temperature 当思考强度 | 概念混淆 | `temperature` 与 `reasoning_effort` 分开配置 |
| 未提 `detail` 参数 | V4.1-Flash 新增，直接影响能否看清小字 | 默认 `original` |
| 未提图片只能放 user 消息 | 放 system/assistant 会 400 | 程序保证放 user 消息 |
| 依赖列表没提图像处理 | 截图 2–3MB，每步上传浪费 | 加 Pillow（可选）；`image_shrink` 开关 |
| input_text 一行带过 | 中文必失败 | 三方案降级（ADBKeyboard / 剪贴板 / ASCII 兜底） |
| 只靠 max_steps 防死循环 | 不够 | 加画面哈希 + 重复动作检测 |
| 没有任务终止语义 | 模型无法主动说"做完了" | 加 `finish` / `fail` 动作 |
| 没有安全设计 | AI 能点付款/发消息 | 危险动作二次确认 + 审计日志 |
| Shizuku 与 ADB 并列 | 桌面阶段用不上 | 明确为端上版路径，且与无障碍是互补非替代 |
| 未验证模型是否收图 | 静默失败 | 启动自检（真图打一次请求） |

---

## 附：一句话总结设计哲学

> **让模型做它擅长的事（理解语义、决定下一步做什么），
> 让代码做代码擅长的事（可靠执行、格式校验、边界保护、安全拦截）。**

V4.1-Flash 是原生多模态模型，看图定位元素这件事它自己能做（RefCOCO 86.0），
所以**不需要为了坐标精度去搭一套 UI 树基础设施** —— 那是为已退役的
Vision-Exp（384 token）设计的方案，在新模型上属于过度工程。

真正必须由代码承担的，是模型做不好的那些部分：
把自然语言决定翻译成确定性的 ADB 调用、校验模型输出的格式、保护坐标边界、
在模型陷入循环时把它拽出来。这个分工才是项目能否稳定可用的分水岭。

UI 树仍然留作**可选增强**：当纯视觉在某个具体 App 上确实不够准时再加，
而不是一开始就背上这个复杂度。