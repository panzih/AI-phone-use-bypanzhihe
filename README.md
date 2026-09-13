# AI 手机助手

用大模型（多模态视觉模型）直接操作你的安卓手机。
程序自动截取手机屏幕 → 发给 AI → AI 返回操作指令 → 自动在手机上执行 → 循环直到任务完成。

**当前版本：桌面端（Windows / macOS）。** 将来会移植为安卓端 App，架构上已经预留。

---

## 目录

- [一、它是怎么工作的](#一它是怎么工作的)
- [二、五分钟跑起来](#二五分钟跑起来)
- [三、打包成 Mac 应用（.app）](#三打包成-mac-应用app)
- [四、config.json 每一项的含义](#四configjson-每一项的含义)
- [五、中文输入](#五中文输入)
- [六、常见问题排查](#六常见问题排查)
- [七、已知限制](#七已知限制)
- [八、项目结构](#八项目结构)

---

## 一、它是怎么工作的

```
   ┌──────────┐   截图    ┌──────────────┐
   │  你的手机 │ ────────> │  本程序       │
   └──────────┘           │              │
        ▲                 │  1. 截图      │
        │                 │  2. 压缩      │
        │  执行操作        │  3. 问 AI     │
        └──────────────── │  4. 解析指令   │
                          │  5. 执行      │
                          └──────┬───────┘
                                 │ 图片 + 屏幕尺寸
                                 ▼
                          ┌──────────────┐
                          │  多模态大模型  │
                          │  返回 JSON 指令│
                          └──────────────┘
```

每一轮循环：
1. 用 ADB 截一张手机屏幕
2. 把截图压缩到模型能接受的范围内
3. 把「截图 + 屏幕分辨率 + 最近几步做过什么」发给 AI
4. AI 返回一个 JSON，比如 `{"action":"tap","x":540,"y":1200}`
5. 程序用 ADB 执行这个操作
6. 回到第 1 步，直到 AI 说任务完成或达到最大步数

---

## 二、五分钟跑起来

### 第 1 步：装 Python

**先检查有没有装：** 打开终端（Windows 是「命令提示符」或 PowerShell），输入：

```bash
python3 --version
```

如果显示 `Python 3.8` 或更高版本，跳过这一步。

如果没有，去 https://www.python.org/downloads/ 下载安装。

> **Windows 用户特别注意**：安装时**必须勾选**「Add Python to PATH」，
> 否则装完之后终端里还是找不到 python。

**本程序是零第三方依赖的**，装完 Python 就能跑。
Pillow 是可选的，装了有两个好处：本地压缩截图更省流量、以及能开启网格标尺。

```bash
python3 -m pip install Pillow
```

> 不装也完全能用：
> - macOS 会自动调用系统自带的 `sips` 来缩放截图
> - Windows 上没有 sips，会直接发送原图 —— **功能不受影响**，
>   因为服务端自己会把图缩放到约 1300×1300，只是每步上传量大一些（约 2-3 MB）
> - 唯一的损失是**网格标尺需要 Pillow**，不装就用不了
>   （网格标尺能帮 AI 建立坐标感，建议装上）

### 第 2 步：装 ADB

ADB（Android Debug Bridge）是谷歌官方的安卓调试工具。

**macOS：**

```bash
brew install --cask android-platform-tools
```

没有 Homebrew？去 https://developer.android.com/tools/releases/platform-tools
下载 `platform-tools-latest-darwin.zip`，解压到任意目录（比如 `~/platform-tools`），
然后在终端执行：

```bash
echo 'export PATH=$PATH:~/platform-tools' >> ~/.zshrc
source ~/.zshrc
```

**Windows：**

去 https://developer.android.com/tools/releases/platform-tools
下载 `platform-tools-latest-windows.zip`，解压到 `C:\platform-tools`。

然后把这个目录加入系统 PATH（这一步不能省）：
1. 按 `Win` 键，搜索「环境变量」，打开「编辑系统环境变量」
2. 点「环境变量」按钮
3. 在「系统变量」里找到 `Path`，双击
4. 点「新建」，输入 `C:\platform-tools`
5. 一路点确定，**然后关掉并重新打开终端**（这一步很关键）

**验证装好了：**

```bash
adb version
```

能看到版本号就成功了。如果提示「不是内部或外部命令」/「command not found」，
说明 PATH 没配对 —— 你也可以跳过 PATH，直接在 `config.json` 里填 adb 完整路径。

### 第 3 步：手机开启 USB 调试

1. 手机「设置 → 关于手机」，找到「版本号」，**连续点 7 次**，
   会提示「您已处于开发者模式」
2. 返回设置，找到「开发者选项」（不同手机位置不同，
   通常在「设置 → 系统」或「设置 → 更多设置」里）
3. 打开「USB 调试」
4. 用数据线连接手机和电脑
5. 手机上会弹出「**允许 USB 调试吗？**」→ 勾选「一直允许」→ 确定

> **如果没弹出授权窗口**：到「开发者选项」里点「撤销 USB 调试授权」，
> 然后拔掉数据线重新插上，弹窗就会再次出现。

**验证设备连上了：**

```bash
adb devices
```

看到类似这样就可以了（`device` 表示可用）：

```
List of devices attached
ABCD1234EFGH    device
```

如果显示 `unauthorized`，说明你还没在手机上点「允许」。
如果显示 `offline`，执行 `adb kill-server` 后重新插线。

### 第 4 步：填 API Key

编辑 `config.json`，把 `api_key` 填上：

```json
{
  "base_url": "https://api.deepseek.com",
  "api_key": "sk-你的密钥",
  "model": "deepseek-flash"
}
```

API Key 在 https://platform.deepseek.com 的「API Keys」页面创建。

> **不想把 Key 写进文件？** 也可以留空 `api_key`，改用环境变量：
>
> ```bash
> # macOS / Linux
> export DEEPSEEK_API_KEY="sk-你的密钥"
>
> # Windows CMD
> set DEEPSEEK_API_KEY=sk-你的密钥
>
> # Windows 永久生效（设置后要重开终端）
> setx DEEPSEEK_API_KEY "sk-你的密钥"
> ```

> ⚠️ **模型名必须是 `deepseek-flash`。**
>
> 2026-09-10 起 DeepSeek 发布了 V4.1-Flash（原生多模态），**模型 id 就叫 `deepseek-flash`**。
> 旧的 `deepseek-v4-flash-vision-exp` 已经退役，虽然名字还能用（服务端会转发到 V4.1-Flash），
> 但新的名字才是官方推荐的。
>
> | 模型 | 能看图吗 |
> |---|---|
> | `deepseek-flash` | ✅ 能（V4.1-Flash，推荐） |
> | `deepseek-v4-flash-vision-exp` | ⚠️ 能用但已退役，会被转到 V4.1-Flash |
> | `deepseek-v4-flash` | ❌ 纯文本 |
> | `deepseek-v4-pro` | ❌ 纯文本 |

### 第 5 步：自检

这一步会检查 Python、ADB、设备连接、截屏、模型接口，
**把问题提前暴露出来**，强烈建议第一次用一定要跑：

```bash
python3 main.py --check
```

全部通过会看到：

```
✓ 全部检查通过，可以开始使用
```

如果某一步失败，它会告诉你具体原因和解决办法。

**其他有用的命令：**

```bash
python3 main.py --list-devices    # 列出设备并给出排查建议
python3 main.py --shot            # 截一张图保存下来，确认画面正常
python3 selftest.py               # 离线自测框架逻辑（不需要手机和 Key）
```

`--shot` 会保存两张图，和这次的诊断日志放在一起：
- `shot_raw.png` —— 手机原始截图
- `shot_to_model.png` —— 实际发给模型的那张

命令跑完会打印具体路径。建议打开对比一下，
确认「发给模型的图虽然变小了但文字还看得清」。

### 第 6 步：开始用

**两种方式，随你选。**

**方式 A：图形界面**（推荐，有窗口，好看日志）

```bash
python3 macapp.py
```

会弹出一个窗口：上面输入任务、点「开始」，下面实时显示 AI 每一步在想什么。
界面用的是 Python 自带的 tkinter，**不需要额外装任何东西**。

**方式 B：命令行**

```bash
python3 main.py "打开设置，看看当前连接的是哪个 WiFi"
```

程序会把每一步的过程打印到终端。

**命令行交互模式**（连续下多个任务）：

```bash
python3 main.py -i
```

---

## 三、打包成 Mac 应用（.app）

上面的两种方式都要先开终端敲命令。如果你想做成**双击就能用的应用**：

```bash
cd "/Users/panzhihe/DSH/AI手机"
bash build_app.sh
```

**只要这一条命令。** 脚本会自动建虚拟环境、装依赖、打包。第一次跑大约 1-2 分钟。

**产物：** `dist/AI手机助手.app`（约 21MB）

> **为什么需要虚拟环境？** macOS 自带的 `/usr/bin/python3` 属于系统组件，
> 不允许往里装第三方包（会报 `Operation not permitted: ~/Library/Python`）。
> 所以脚本会在项目目录里建一个 `.venv`，不污染系统。
> 不想要了直接 `rm -rf .venv` 删掉即可。

### 怎么用这个 .app

1. 打开 `dist` 文件夹，双击「AI手机助手.app」
2. **首次打开时** macOS 会提示「无法验证开发者」——
   到「系统设置 → 隐私与安全性」，在下面找到它，点「仍要打开」
   （应用没做苹果签名，自己用完全没问题）
3. 应用里点「打开配置」填写 API Key，然后**重启应用**
4. 回到应用点「环境自检」，通过后就能下任务

可以拖到「应用程序」文件夹，不影响使用。

> ⚠️ **这个应用需要电脑上已经装好 adb**（见第 2 步）。
> 应用本身不带 adb，它是调用你系统里的 adb 去连手机的。

### 启动失败怎么办

`.app` 是窗口模式，双击启动时没有终端，所以**万一启动就崩，你会看到「闪一下就没了」**。
为此程序会把所有输出和异常都写进一个文件：

```
~/Library/Application Support/AI手机助手/logs/startup.log
```

打开这个文件就能看到崩溃原因。也可以在终端里手动启动看到实时输出：

```bash
"./dist/AI手机助手.app/Contents/MacOS/AI手机助手"
```

### 配置和日志在哪

打包成应用后，配置和日志**不再放在项目目录**，而是按 macOS 规范放在：

```
~/Library/Application Support/AI手机助手/
├── config.json      ← 点应用里的「打开配置」直接打开
└── logs/            ← 点「打开日志目录」直接打开
```

应用的「打开配置」和「打开日志目录」两个按钮会自动定位到这里，不用自己去找。

> **为什么开发模式下配置文件在项目目录，打包后却换了位置？**
> 因为双击 `.app` 时，程序的当前工作目录会变成 `/`（不是 .app 所在目录）。
> 如果还用相对路径，配置和日志会被写到系统根目录去，必然失败。
> `paths.py` 统一处理了这个差异，而且做了多级回退 ——
> 万一标准目录不可写，会自动退到下一个可写位置，绝不因为「存不了文件」而启动失败。
>
> 想手动指定位置，可以用环境变量：
> ```bash
> export AIPHONE_DATA_DIR="$HOME/aiphone-data"
> ```

### 打包相关的常见问题

| 问题 | 原因和解决 |
|---|---|
| `PyInstaller 安装失败` | 网络问题。换镜像：`.venv/bin/python -m pip install pyinstaller -i https://pypi.tuna.tsinghua.edu.cn/simple`，然后重跑 |
| 虚拟环境坏了 | `rm -rf .venv && bash build_app.sh` 重新来 |
| 双击闪一下就没了 | 看 `~/Library/Application Support/AI手机助手/logs/startup.log` |
| 打包后连不上手机 | 电脑上没装 adb，或 adb 不在 PATH。在配置里填 `adb_path` 完整路径 |
| 网格标尺没生效 | 打包时 Pillow 没装上。看构建输出那一行是不是「跳过」 |
| 界面字体/控件很旧 | 系统自带 Python 绑的是 Tk 8.5（十多年前的版本）。装新版 Python 3.12 + Tk 后重新打包即可 |

### 改了代码要重新打包吗

要。`.app` 里是打包那一刻的代码快照，源码改了不会自动生效。重新跑一次：

```bash
bash build_app.sh
```

---

## 四、config.json 每一项的含义

```json
{
  "adb_path": "",
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

  "log_dir": "logs",
  "save_screenshots": true,
  "verbose": true
}
```

### 设备相关

| 项 | 说明 |
|---|---|
| `adb_path` | adb 路径。留空 = 自动查找。自动找不到时在这里填完整路径 |
| `device_serial` | 只连了一台手机就留 `null`；连了多台时填 `adb devices` 显示的序列号 |

### 模型相关

| 项 | 说明 |
|---|---|
| `base_url` | 接口地址。支持三种写法：`https://api.deepseek.com`、`https://api.deepseek.com/v1`、或完整的 `/v1/chat/completions` |
| `api_key` | 留空则读环境变量 |
| `api_key_env` | 按顺序尝试的环境变量名 |
| `model` | 必须是能看图的模型。DeepSeek 官方推荐 `deepseek-flash` |
| `temperature` | 随机性，0–2。**官方视觉指南的评测参数是 1.0，建议保持 1.0**（这是视觉理解场景的推荐值，不要照搬代码生成的低温度习惯） |
| `reasoning_effort` | 思考强度，留空不带该参数。可选 `low`/`high`/`max` |
| `extra_body` | 透传厂商私有参数。比如开思考模式：`{"thinking": {"type": "enabled"}}` |
| `max_tokens` | 单次回复长度上限。太小会被截断，建议 >= 1024 |
| `request_timeout` | 单次请求超时秒数 |

> `temperature` 和 `reasoning_effort` 是**两个不同的东西**，别混。
> 前者控制随机性，后者才是「思考强度」。
> 不同厂商的思考参数命名不一样，所以留了 `extra_body` 给你手动适配，不用改代码。

### 循环控制

| 项 | 说明 |
|---|---|
| `max_steps` | 单个任务最多执行多少步。防止无限循环 |
| `wait_after_action` | 每步操作后等几秒。手机卡就调大（如 2.5） |
| `stagnant_threshold` | 画面连续几次没变就警告 AI 换策略（默认 3） |
| `repeat_threshold` | 同一动作重复几次就警告 AI（默认 3） |

### 图像与坐标

| 项 | 说明 |
|---|---|
| `coordinate_mode` | `"pixel"` = AI 直接输出像素坐标（默认）；`"normalized"` = AI 输出 0–1000 的千分比 |
| `detail` | 官方参数，控制服务端怎么处理图。`original` 保留原图（**读小字必需，默认**）；`low` 降到 512×512，快且便宜，适合只判断「这是什么界面」 |
| `image_shrink` | 是否在本地先缩小。**这不是功能必需** —— 服务端自己会把图归一到约 1300×1300。开着只是省上传流量，觉得没必要可以设为 `false` |
| `image_pixel_budget` | 本地缩放的目标像素数，默认 1690000（约等于服务端的 1300×1300） |
| `image_max_bytes` | 单张图字节上限，默认 4MB。官方单图上限是 32MiB |
| `draw_grid_overlay` | 是否在截图上叠加网格标尺，帮 AI 建立坐标感。**建议保持开启** |
| `grid_divisions` | 网格划分格数，默认 10×10 |

> **如果 AI 点的位置总是偏**，试试把 `coordinate_mode` 改成 `"normalized"`（千分比坐标
> 对模型来说更容易表达准确），同时确认 `draw_grid_overlay` 是 `true`。
> 也可以把 `detail` 确认为 `original` —— 设成 `low` 会让图被压到 512×512，小字就看不见了。

---

## 五、中文输入

**`adb shell input text` 只支持英文数字，输入中文会失败。** 这是安卓系统的限制。

程序会自动按三种方案降级，但**中文输入需要你先装一个辅助输入法**：

### 安装 ADBKeyboard（推荐）

1. 搜索下载 `ADBKeyboard.apk`（开源项目，作者 senzhk）
   地址：https://github.com/senzhk/ADBKeyBoard
2. 安装到手机：
   ```bash
   adb install ADBKeyboard.apk
   ```
3. 在手机上启用它：「设置 → 系统 → 语言和输入法 → 虚拟键盘 → 管理键盘」，
   找到「ADB Keyboard」并打开
4. 验证：
   ```bash
   python3 main.py --check
   ```
   看到「测试中文输入能力 ... 支持」就成功了

> 装好之后，程序会自动检测并使用它，**不需要把它设成默认输入法**
> （程序是通过广播给它发指令的，不影响你日常打字）。

### 没装会怎样

- 输入英文数字：正常
- 输入中文：程序会明确报错，并告诉你怎么装，**不会静默失败**

---

## 六、常见问题排查

### 1. 提示「找不到 adb 可执行文件」

**原因**：装是装好了，但不在系统 PATH 里。

**解决**：找到 adb 的实际位置，填进 `config.json`：

```json
{ "adb_path": "/Users/你的用户名/Library/Android/sdk/platform-tools/adb" }
```

Windows 上类似 `"C:\\platform-tools\\adb.exe"`（注意 JSON 里反斜杠要写两个）。

### 2. `adb devices` 列表是空的

按顺序检查：

1. **数据线问题** —— 有些线只能充电不能传数据，换一根
2. **USB 调试没开** —— 见上面的第 3 步
3. **没点授权弹窗** —— 看手机屏幕有没有弹窗，勾「一直允许」
4. **USB 用途不对** —— 手机下拉通知栏，把「USB 用途」从「仅充电」改成「文件传输 / MTP」
5. **驱动问题（仅 Windows）** —— 需要装手机厂商的 USB 驱动
6. 执行 `adb kill-server` 然后重新运行

### 3. 显示 `unauthorized`

手机上没有点「允许 USB 调试」。如果手机没弹窗：

到「开发者选项」→ 点「撤销 USB 调试授权」→ 拔线重插 → 弹窗就会出现。

### 4. 显示 `offline`

```bash
adb kill-server
adb devices
```

还不行就拔插数据线，或者重启手机。

### 5. AI 点击的位置总是偏

按可能性排序：

1. **确认 `draw_grid_overlay` 是 `true`** —— 有网格标尺 AI 的坐标感会好很多
2. **把 `coordinate_mode` 改成 `"normalized"`** —— 千分比坐标比绝对像素更容易被模型表达准确
3. **确认屏幕分辨率识别正确** —— 跑 `python3 main.py --shot`，
   看日志里打印的分辨率和你手机实际的是否一致
4. **折叠屏 / 分屏 / 横屏** —— 程序会自动以实际截图尺寸校准坐标，
   看日志里有没有「已按实际截图尺寸校准坐标」的提示
5. **任务描述更具体** —— 「点击屏幕下方的蓝色登录按钮」比「点登录」好

> **关于坐标精度的说明**：`deepseek-flash`（V4.1-Flash）是原生多模态模型，
> RefCOCO 定位基准得分 86.0，看图找元素的能力不弱。
> 但它接收的图会被服务端归一到约 **1300×1300** 像素，单图最多 1024 token。
> 一张 1080×2400 的长截图按比例缩放后约 872×1938 再归一到 1300×1300，
> 密集列表里的小字仍然可能糊掉。
> 如果发现点不准，优先试这三招：
> 1. 确认 `detail` 是 `"original"`（设成 `low` 会被压到 512×512，小字直接没了）
> 2. 让任务描述更具体，比如「点击屏幕下方蓝色的登录按钮」
> 3. 把 `coordinate_mode` 改成 `"normalized"`
>
> 更彻底的解法是读取安卓的 UI 控件树（`uiautomator dump`），
> 直接从系统拿到每个控件的精确坐标，不依赖模型的空间推理。
> 方案见 `DESIGN.md`，`adb.py` 里已经预留了 `ui_dump()` 接口。

### 6. 中文输入失败

见上面的 [五、中文输入](#五中文输入)。装 ADBKeyboard 即可。

### 7. 模型报 400 错误

先看错误信息，程序会自动翻译成中文提示。常见情况：

| 提示 | 原因 | 解决 |
|---|---|---|
| 「该模型不接受图片输入」 | 选了纯文本模型 | 改成 `deepseek-flash` |
| 「接口地址不存在」 | base_url 写错 | 检查 `base_url` |
| 「鉴权失败」 | Key 错或过期 | 检查 `api_key` |
| 「请求参数有问题」 | 图片太大 / 参数拼错 | 官方单图上限 32MiB，一般不会超。检查 `extra_body` 里的参数拼写 |
| 「图片只能放在 user 消息里」 | 把图放进了 system/assistant 消息 | 程序已正确处理，若出现请反馈 |
| 「账户余额不足」 | 没钱了 | 去控制台充值 |

### 8. 截图是全黑的

日志里会提示「截图几乎是全黑的」。这是**系统的 DRM 保护**：

部分页面（视频播放器、银行 App、支付页面）禁止截屏，这是安卓的安全机制，
**没有任何办法绕过**。遇到这种页面只能手动操作。

### 9. ADB 命令超时 / 程序卡住

```bash
adb kill-server
adb start-server
adb devices
```

还不行就拔插数据线或重启手机。程序所有 ADB 调用都有超时保护，
不会永久卡死（最多等 40 秒）。

### 10. AI 一直在原地打转

程序有防死循环机制，会在画面连续不变时主动警告 AI 换策略。
如果还是打转：

- 调大 `wait_after_action`（有些页面加载慢，操作太快界面还没出来）
- 调小 `stagnant_threshold` 让它更早干预（比如改成 2）
- 把任务拆细一点，一次只做一件事
- 看日志（见下一条），里面有每一步 AI 的原始输出和执行结果

### 11. 怎么知道 AI 到底做了什么 —— 看日志

**每次运行都会单独存一份日志，永远不会互相覆盖。**

日志位置（点应用里的「打开日志目录」直接打开）：

```
~/Library/Application Support/AI手机助手/logs/     ← 打包成 .app 后
项目目录/logs/                                      ← 源码运行时

logs/
├── runs/                                  每次任务一个独立目录
│   ├── 20260912_143022_打开设置看WiFi/
│   │   ├── run.log        完整文字记录（窗口里看到的全部内容，带时间戳）
│   │   ├── report.json    结构化记录（每步动作 + AI 原始输出 + 执行结果）
│   │   └── screenshots/
│   │       ├── step_01.png    第 1 步的屏幕
│   │       └── step_02.png    第 2 步的屏幕
│   └── 20260912_150311_发消息给文件传输助手/
│       └── ...
├── diag/                                  自检 / 设备列表 / 截图的输出
│   ├── 20260912_141500_check.log
│   └── 20260912_141730_shot.log
└── latest -> runs/最近那一次               软链接，快速找到最近一次
```

**三个文件各有各的用处：**

| 文件 | 什么时候看 |
|---|---|
| `run.log` | 「AI 当时到底在想什么」—— 完整对话过程，和界面上看到的一字不差 |
| `report.json` | 「它到底执行了什么」—— 每步的动作、AI 的原始输出、成功还是失败 |
| `screenshots/` | 「操作有没有生效」—— 对比相邻截图，一眼就能看出画面变没变 |

**日志文件开头就记了环境信息**，包括 Python 版本、有没有 Pillow、用的哪个模型、
什么参数、API Key 的前后几位（中间打码）。所以以后要排查问题，
直接把 `run.log` 发出来就行，不用再回忆当时的配置。

**每写一行立即存盘**，所以哪怕程序崩溃或者被强制关掉，已经产生的日志也不会丢。

在应用里：

- 点「**打开本次日志**」→ 直接用系统默认程序打开最近这次的 `run.log`
- 点「**打开日志目录**」→ 打开总目录，能看到所有历史运行记录
- 窗口底部会显示最近一次日志的完整路径

命令行同理，每次跑完会打印：

```
日志已保存：/Users/你/DSH/AI手机/logs/runs/20260912_143022_打开设置/run.log
```

---

## 七、已知限制

这些不是 bug，是当前方案的能力边界，提前知道能少走弯路：

| 限制 | 原因 |
|---|---|
| 密集小字可能看不清 | 服务端会把图归一到约 1300×1300，单图最多 1024 token。手机长截图按比例缩放后小字会糊 |
| 点不准时坐标有误差 | 纯视觉定位依赖模型的空间推理。解法是读 UI 控件树（见 `DESIGN.md`） |
| 每步耗时 3–8 秒 | 截图 + 请求模型 + 执行 + 等待，链路本身就长 |
| 每步都有 API 成本 | 每步要传一张图，约 1000 token 上下 |
| 复杂界面可能识别不准 | 尤其是密集列表、弹窗叠加、动态内容 |
| DRM 保护页面无法截屏 | 系统安全机制，无解 |
| 部分 App 无响应 | 有些 App 检测到模拟点击会拒绝响应 |

**关于「为什么不用 UI 控件树」**：读 `uiautomator dump` 能拿到每个按钮的精确坐标，
准确率会大幅提升，这是正解。当前版本按需求先做了纯视觉的最小可用框架，
`DESIGN.md` 里已经写好了接入方案（`uiauto.py` 模块），随时可以加。

---

## 八、项目结构

```
AI手机/
├── macapp.py         图形界面版（tkinter，双击应用跑的就是它）
├── main.py           命令行版（自检 / 截图 / 跑任务 / 交互模式）
├── config.py         配置加载，api_key 读取与脱敏
├── paths.py          路径解析（区分开发模式和 .app 打包模式）
├── run_logger.py     日志落盘（每次运行独立目录，永不覆盖）
├── adb.py            ADB 封装（纯 subprocess，零依赖）
├── image.py          截图缩放、压缩、网格标尺
├── llm.py            OpenAI 兼容接口客户端（urllib 实现）
├── tools.py          动作执行器（白名单校验 + 坐标边界保护）
├── agent.py          主循环、提示词、JSON 解析、防死循环
├── selftest.py       离线自测（71 项，不需要真机和 Key）
├── build_app.sh      一键打包成 macOS .app
├── macapp.spec       PyInstaller 打包配置
├── config.json       配置文件
├── DESIGN.md         详细设计文档
├── PROMPT.md         需求提示词（修正版）
└── logs/             日志目录（runs/ 每次任务一份，diag/ 诊断输出）
```

### 架构上的两个重要约定

**约定 1：`adb.py` 是唯一直接接触 ADB 的模块。**

而且只向上层暴露语义化接口（`screenshot()` / `tap()` / `swipe()` / `input_text()`）。
上层代码里**没有任何一处**出现 ADB 原始命令。这不是洁癖，是为了移植：

将来做安卓端 App 时，把这个文件整体替换成
**Shizuku（提供 ADB 级权限，负责执行输入和截屏）
+ 无障碍服务（负责读控件树、事件驱动监听）**
的双实现即可，`agent.py` / `tools.py` / `llm.py` 一行都不用改。

这也是为什么要把 Shizuku 和无障碍放在同一条路径上考虑 ——
它们不是二选一：无障碍能读控件树但截不了屏，Shizuku 能截屏执行但读不到无障碍节点，
**最优组合是两个都要**。

**约定 2：`agent.py` 不直接 print，而是通过回调输出。**

`Agent` 接收 `log_cb` 和 `stop_cb`：
- 命令行版（`main.py`）把 `log_cb` 接到 `print`
- 图形界面版（`macapp.py`）把它接到窗口日志区，`stop_cb` 接到「停止」按钮

所以同一套核心逻辑能被两种前端复用，改一处两边都生效。

---

## 快速命令速查

```bash
python3 macapp.py                          # 打开图形界面
python3 main.py --check                    # 环境自检（第一次必跑）
python3 main.py --check --skip-llm         # 只查设备和 ADB，不查模型
python3 main.py --list-devices             # 列出设备
python3 main.py --shot                     # 截一张图看看
python3 main.py "打开设置看看 WiFi"         # 跑一个任务（命令行）
python3 main.py -i                         # 交互模式
python3 selftest.py                        # 离线自测
bash build_app.sh                          # 打包成 macOS .app
```
