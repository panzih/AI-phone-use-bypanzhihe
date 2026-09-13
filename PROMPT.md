# 修正后的提示词（可直接复制给其他 AI）

> 说明：这份提示词修正了原版的三处致命错误 ——
> ① DSH 没有 Python SDK；② 错误估计了模型的图像能力；③ 中文输入与防死循环设计缺失。
> 详细论证见同目录 `DESIGN.md`。

---

你是资深 Python 开发工程师，精通 ADB 安卓调试与多模态大模型 API 集成。
请开发一个「AI 自动操控安卓手机」的桌面端工具，输出完整可运行的代码、依赖列表和傻瓜式中文运行教程。

## 一、技术栈（严格遵守）

1. **纯 Python，不依赖任何 agent 框架。** 特别注意：DeepSeek Harness（DSH）是 Node.js/TypeScript
   项目，**不存在 Python SDK**，不要引入、不要提及、不要尝试对接。
2. **ADB 操控使用标准库 `subprocess` 调用系统 adb 命令**，不依赖 `adb-shell`、
   `pure-python-adb` 等第三方库。Windows/macOS 双平台兼容。
   Windows 下所有 subprocess 调用必须带 `creationflags=subprocess.CREATE_NO_WINDOW`，
   避免弹黑框。所有调用必须有 timeout。
3. **AI 接口严格兼容 OpenAI 的 `/chat/completions` 格式**，支持自定义
   `base_url`、`api_key`、`model`、`temperature`。
4. 依赖只允许：`httpx`、`Pillow`。要求 Python ≥ 3.10。
5. 所有配置写在单独的 `config.json`，用户只改这个文件。

## 二、目标模型的真实能力（务必按此设计，不要凭印象）

使用 DeepSeek 的 **`deepseek-flash`**（即 2026-09-10 发布的 V4.1-Flash，原生多模态）。
官方文档给出的关键事实：

| 项目 | 值 |
|---|---|
| 模型 id | `deepseek-flash` |
| 单图 token 上限 | **1024**（不是 384） |
| 服务端归一目标 | 自动缩放到总像素约 **1300×1300** |
| 单图大小上限 | 32 MiB（base64 / URL）；Files API 64 MiB |
| 请求体上限 | 48 MiB |
| 单次最多图片数 | 600 |
| 支持的图片格式 | JPEG、PNG、GIF、WebP |
| 官方推荐的 temperature | **1.0** |
| 图片位置限制 | **只能是 user 消息**，放在 system 或 assistant 会返回 400 |

**`detail` 参数（很重要）**：`image_url` 对象里可以带 `detail` 字段：

- `low` —— 服务端先降到 512×512，快且便宜
- `original` / `high` —— 保留原图
- `auto` —— 目前等价于 `original`

⚠️ **不要自己实现图片缩放当作必需步骤。** 服务端本来就会缩放，
客户端缩放只是为了省上传流量。必须提供一个开关能跳过本地缩放、直接发送原图。
但**必须把 `detail` 默认设为 `original`** —— 设成 `low` 会让手机截图里的
按钮文字直接糊掉，这是最容易踩的坑。

**不要把 temperature 设得很低（如 0.1）。** 官方视觉场景的评测参数是 1.0。

## 三、核心设计原则（这是本项目的关键，务必理解）

### 原则 1：以纯视觉为主，UI 树是可选增强

V4.1-Flash 是原生多模态模型，官方 RefCOCO 定位基准得分 86.0，看图找元素的能力是可用的。
所以**纯视觉方案是可行的主路线**，不要把 UI 树当作必需前提。

在此基础上，把「读 UI 树」实现为一个**可选的精度增强**（配置开关控制）：

- 实现 `uiautomator dump` 获取控件树，解析出 `class / text / content-desc /
  resource-id / bounds / clickable / scrollable`。
- 把可交互节点**编号压缩成表格**附在提示词里，形如：
  ```
  [1] 按钮 "登录"      id=login_btn   center=(540,2175) clickable
  [2] 输入框 "手机号"   id=phone_input center=(540,1200) editable
  ```
- 开启时，模型可优先输出 `target`（节点编号），程序据此点击**真实 bounds 的中心点**；
  关闭时纯靠截图 + 坐标。
- 必须检测 UI 树失效的情况（节点数 < 3，如 Flutter / 游戏 / 自定义渲染），
  自动降级为纯视觉模式并在日志中说明。

**架构要求**：两种模式共用同一套动作 schema，切换不影响上层逻辑。
`adb.py` 里要预留 `ui_dump()` 接口，即使当前不启用。

### 原则 2：坐标系统一

内部**只使用真实物理像素**（`adb shell wm size` 报告的值）。换算规则：

- UI 树给的是物理像素 → 直接用
- 归一化值 (0–1000) → `px = round(v / 1000 * 屏幕宽或高)`
- 所有坐标必须 clamp 到 `[0, W-1]` / `[0, H-1]`

注意折叠屏、横屏、分屏时实际截图尺寸可能与 `wm size` 不一致，
**必须以实际截图的尺寸为准**并记录警告。

### 原则 3：图像处理（缩放是可选的，`detail` 是必需的）

服务端自己会把图缩放到约 1300×1300，**所以客户端缩放不是功能必需**。
但仍要实现，因为截图 PNG 常有 2–3 MB，每步都上传很浪费：

```
原始 PNG → Pillow 转 RGB → 像素数超 1690000（约等于服务端 1300×1300）则等比缩小
→ JPEG 质量阶梯压缩 85/70/55/40，取第一个 ≤ 4MB 的结果 → base64
```

必须提供 `image_shrink` 开关，设为 false 时直接发送原图。

**`detail` 字段默认必须是 `original`**（见第二节），这是比缩放重要得多的参数。

加一个可配置的「网格标尺」：在图上叠加 10×10 淡色网格并标出真实像素刻度，
帮助模型建立坐标感。注意网格要在缩放**之后**叠加，否则会跟着一起缩小。

## 四、功能清单

### 1. 设备连接
- `adb devices -l` 自动检测设备，多设备时提示用户选择
- 区分「未授权」(unauthorized) 和「离线」(offline) 并给出对应中文解决提示
- 自动获取屏幕分辨率 (`wm size`)
- 连接失败给出清晰中文提示和排查步骤

### 2. 动作执行工具集
实现这些动作，每个都要有参数校验和统一的返回结构：

| action | 参数 | 实现 |
|---|---|---|
| `tap` | `target` 或 `x,y` | `input tap x y` |
| `swipe` | `direction`(up/down/left/right) 或 `x1,y1,x2,y2`，可选 `duration_ms` | `input swipe x1 y1 x2 y2 ms` |
| `long_press` | `target` 或 `x,y`，可选 `duration_ms`(默认1000) | `input swipe x y x y ms` |
| `input_text` | `text` | 见下方输入方案 |
| `open_app` | `package` | `monkey -p 包名 -c android.intent.category.LAUNCHER 1` |
| `key` | `key`(back/home/enter/app_switch) | `input keyevent 4/3/66/187` |
| `wait` | `seconds`(默认1，上限10) | sleep |
| `scroll_to` | `target` | 滚动查找目标 |
| `finish` | `summary` | **任务完成，退出循环** |
| `fail` | `reason` | **无法完成，退出循环** |

`finish` / `fail` 必须是显式动作，否则模型没有"停下来"的出口。

**中文输入必须实现三种方案并按序降级**（`adb shell input text` 只支持 ASCII，
直接传中文会静默失败）：

```
方案 A：ADBKeyboard IME
  adb shell ime enable com.android.adbkeyboard/.AdbIME
  adb shell am broadcast -a ADB_INPUT_B64 --es msg <base64(utf8)>
方案 B：剪贴板 + 粘贴（依赖 Clipper 等 App）
  adb shell am broadcast -a clipper.set -e text <base64>
  adb shell input keyevent 279
方案 C：仅当文本可 encode('ascii') 时直接 input text，否则明确报错并提示用户装 ADBKeyboard
```

启动时探测当前输入法，自动选择可用方案，并在日志中明确告知用户在用哪种。
**绝不静默失败。**

**截图必须用 `adb exec-out screencap -p`** 直接拿二进制。
不要用 `adb shell screencap` 再 pull —— 会走 PTY 把 `\n` 转成 `\r\n` 损坏 PNG，而且慢一倍。

### 3. Agent 主循环

```
while step < max_steps:
    1. 截图 + 获取 UI 树
    2. 画面哈希对比：与上一步相同则 stagnant += 1
    3. 构造提示词（当前截图 + UI 节点表 + 历史动作纯文本 + stagnant 提示）
    4. 调用模型
    5. 防御性解析 JSON → 校验 action 白名单与参数 schema
    6. 重复动作检测：最近5步内同一动作同参数出现 ≥3 次 → 强制提示换策略
    7. 危险动作拦截（见下）
    8. 执行 + 存档（截图/模型原文/动作/结果）
    9. 收到 finish 或 fail → 退出
    10. sleep(wait_after_action)
```

**任务历史里不要放历史截图**，只放当前这一张 + 历史动作的纯文本摘要
（`第1步 tap#2(手机号输入框) → 画面已变化`）。传多图会迅速吃满像素预算并稀释注意力。

**防御性 JSON 解析**（模型输出不可信，必须处理）：
1. 剥离 markdown 围栏 ` ```json ... ``` `
2. 取第一个 `{` 到最后一个 `}` 的子串（模型爱加前后解释）
3. `json.loads`，失败则重试
4. 校验 `action` 在**白名单**内 —— 不在白名单**绝不执行**，
   防止模型编造动作导致命令注入
5. 按 action 校验必需参数存在且类型正确
6. 数字参数做 range clamp
7. 最多重试 2 次，仍失败则用纯文本追问一次

### 4. 安全控制（不可省略）

AI 能点屏幕上任何东西 —— 能付款、能发消息、能卸载应用。必须实现：

- **危险动作二次确认**：命中危险关键词（支付/付款/转账/删除/卸载/发送/确认购买）
  或目标 App 在危险包名列表（支付宝/微信）中时，
  在终端打印 `即将执行：tap "确认支付"，是否继续？[y/N]` 并等待人工输入。
- **完整审计日志**：每次操作落盘时间、动作、目标元素文案、截图路径，事后可追溯。
- 安全拦截必须基于**代码里的确定性规则**，不要指望模型在提示词里被叮嘱后就自觉遵守。

### 5. 启动自检（必须有）

程序启动先跑连通性自检：
1. 发一个纯文本极小请求 → 验证 URL 和鉴权
2. 生成一张 32×32 测试 PNG 以 `image_url` 发出 → **验证该模型真的收图**
3. 若返回 400/422 且报错涉及 image/vision → 明确提示
   「当前模型不支持图片输入，请换用视觉模型」

## 五、配置文件

```json
{
  "adb_path": "adb",
  "device_serial": null,
  "base_url": "https://api.deepseek.com",
  "api_key": "",
  "api_key_env": ["DEEPSEEK_API_KEY", "OPENAI_API_KEY"],
  "model": "deepseek-flash",
  "temperature": 1.0,
  "reasoning_effort": "",
  "extra_body": {},
  "max_tokens": 1024,
  "request_timeout": 120,
  "max_steps": 30,
  "wait_after_action": 1.5,
  "stagnant_threshold": 3,
  "repeat_threshold": 3,
  "coordinate_mode": "pixel",
  "detail": "original",
  "image_shrink": true,
  "image_pixel_budget": 1690000,
  "image_max_bytes": 4194304,
  "jpeg_quality": 85,
  "draw_grid_overlay": true,
  "grid_divisions": 10,
  "use_ui_tree": false,
  "log_dir": "logs",
  "save_screenshots": true
}
```

注意 `temperature` 和 `reasoning_effort` 是**两个不同的东西**，
不要混为一谈：前者是随机性，后者才是思考强度。
**`temperature` 保持官方推荐的 1.0**，不要照搬代码生成场景的低温度习惯。
不同厂商的思考参数命名不同（DeepSeek 是 `reasoning_effort`，部分厂商是 `enable_thinking`
或 `thinking: {type: "enabled"}`），所以提供 `extra_body` 字段用于透传厂商私有参数，
用户不用改代码就能适配任何模型。

`api_key` 留空时回退读环境变量 `DEEPSEEK_API_KEY` / `OPENAI_API_KEY`。

## 六、项目结构

```
├── config.json          唯一需要用户修改的文件
├── main.py              CLI 入口 + 主循环
├── config.py            配置加载与校验
├── adb.py               ADB 封装（纯 subprocess）
├── uiauto.py            UI 树解析与节点编号化
├── vision.py            图像缩放/裁剪/压缩/网格叠加
├── llm.py               OpenAI 兼容客户端 + 自检
├── tools.py             动作执行器（白名单）
├── agent.py             提示词、解析、防死循环、安全拦截
└── logs/                每步存档
```

**架构约束（重要）**：`adb.py` 是唯一直接接触 ADB 的模块，且只暴露语义化接口
（`screenshot()` / `tap()` / `ui_dump()`），不要向上层泄漏任何 ADB 特有的原始命令。
因为将来要做安卓端 App 版本时，会把这个文件整体替换为
**Shizuku（提供 ADB 级权限、负责执行输入和截屏）+ 无障碍服务（负责读节点树、
事件驱动监听）** 的双实现，上面所有层一行都不用改。

## 七、输出要求

1. 完整的项目目录结构
2. 所有 `.py` 源代码，关键位置加**中文注释**
3. `requirements.txt`
4. `config.json` 示例
5. **超详细的傻瓜式运行教程**：从安装 Python、安装 ADB Platform Tools、
   手机开启开发者选项和 USB 调试、连接设备、配置参数、启动运行，
   每一步都写清楚，假设用户完全不懂代码。Windows 和 macOS 分别说明。
6. **常见问题排查**：至少覆盖这些
   - `adb devices` 为空 / 显示 unauthorized / offline
   - 点击位置偏移（坐标换算、折叠屏、分辨率不一致）
   - 中文输入失败
   - `uiautomator dump` 报错或返回空树
   - 模型返回 400（图片格式问题 / 模型不支持视觉）
   - 截图全黑（DRM 保护页面）
   - ADB 卡死无响应
7. 说明**已知限制**，尤其是：
   - 图会被服务端归一到约 1300×1300，手机长截图按比例缩放后密集小字可能看不清
   - `detail` 设成 `low` 会让图降到 512×512，小字直接糊掉
   - 纯视觉坐标定位依赖模型空间推理，点不准时优先检查 `detail` 和任务描述的具体程度
   - 更彻底的精确定位手段是读 UI 控件树（`uiautomator dump`），但要说明它
     在 Flutter / 游戏 / Canvas 渲染的页面上会失效
   - 图片只能放在 user 消息里，否则 400
