# AI 手机助手

> 用多模态大模型直接操作你的安卓手机：自动截屏 → AI 看懂屏幕并决策 → 自动点击/滑动/输入 → 循环直到任务完成。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Python](https://img.shields.io/badge/Python-3.8%2B-blue.svg)](https://www.python.org/)
[![Platform](https://img.shields.io/badge/Platform-macOS%20%7C%20Windows%20%7C%20Android-green.svg)]()
[![Zero Dependencies](https://img.shields.io/badge/dependencies-0%20(desktop)-success.svg)]()

你只需要用一句话下达任务，例如：

- 「打开设置，看看现在连的是哪个 WiFi」
- 「去天气 App 查一下明天要不要带伞」
- 「把亮度调到最低，再打开护眼模式」

程序会像人一样一边看屏幕、一边动手，一步步把事情做完。

---

## 它是怎么工作的

```
   ┌──────────┐  截屏   ┌─────────────────────┐  图片+屏幕信息  ┌──────────────┐
   │ 安卓手机  │ ──────> │      本程序          │ ─────────────> │  多模态大模型  │
   │          │         │ 截屏·压缩·执行·防循环  │ <───────────── │  返回操作指令  │
   └──────────┘ <────── └─────────────────────┘   JSON 动作     └──────────────┘
```

每一轮：用 ADB（桌面端）或系统无障碍服务（安卓端）截屏 → 把屏幕交给视觉模型 →
模型返回一个动作，如 `{"action":"tap","x":540,"y":1200}` → 程序在手机上执行 →
继续下一屏，直到模型判断任务完成或达到步数上限。

为了避免「模型乱点」，所有动作都经过**白名单校验**和**坐标边界保护**，
并带有原地打转检测、重复动作检测和最大步数限制，必要时会主动停下来而不是无限循环。

---

## 两种形态

| 形态 | 目录 | 技术方案 | 状态 |
|---|---|---|---|
| **桌面端**（电脑连手机） | 仓库根目录 | Python + ADB，纯标准库零依赖 | ✅ 可用 |
| **安卓端**（手机上独立运行） | [`android-app/`](android-app/) | Kotlin + Jetpack Compose + 系统无障碍 | 🚧 开发中（操作通道已打通，AI 循环接入中） |

桌面端提供**图形界面**和**命令行**两种用法；安卓端走无障碍路线，
无需 Shizuku / Root，授权一次即可常驻、重启自动恢复，并以「UI 控件树为主、截图为辅」
来获得比纯视觉更准的点击定位。详见 [`android-app/README.md`](android-app/)。

---

## 快速开始（桌面端，约 5 分钟）

### 1. 准备环境

- **Python 3.8+**：终端运行 `python3 --version` 能看到版本即可。桌面端**只用标准库，无需 pip 安装任何依赖**。
  （可选）安装 Pillow 以获得本地截图压缩和网格标尺：`python3 -m pip install Pillow`
- **ADB**：谷歌官方安卓调试工具。
  - macOS：`brew install --cask android-platform-tools`
  - Windows：从 [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools) 下载并加入 PATH
  - 验证：`adb version` 能打印版本号

### 2. 手机开启 USB 调试

1. 设置 → 关于手机 → 连续点击「版本号」7 次，开启开发者模式
2. 在「开发者选项」中打开「USB 调试」
3. 用数据线连接电脑，手机弹窗时勾选「一律允许」
4. 验证：`adb devices` 中你的设备状态为 `device`

### 3. 配置模型 API Key

本项目兼容 OpenAI 格式的多模态接口，默认使用 DeepSeek。编辑 `config.json`：

```json
{
  "base_url": "https://api.deepseek.com",
  "api_key": "sk-你的密钥",
  "model": "deepseek-flash"
}
```

Key 可在 [DeepSeek 开放平台](https://platform.deepseek.com) 创建；需要一个**能看图**的多模态模型。

> 更推荐用环境变量提供 Key，避免把它写进文件：
> ```bash
> export DEEPSEEK_API_KEY="sk-你的密钥"   # macOS / Linux
> setx DEEPSEEK_API_KEY "sk-你的密钥"     # Windows（设置后重开终端）
> ```

### 4. 自检并运行

```bash
python3 main.py --check     # 首次强烈建议：逐项检查 Python / ADB / 设备 / 截屏 / 模型
python3 macapp.py           # 启动图形界面（推荐）
```

---

## 基本用法

**图形界面**（自带，无需安装 GUI 库）：

```bash
python3 macapp.py
```

上方输入任务、点「开始」，下方实时显示 AI 每一步的想法与动作。

**命令行**：

```bash
python3 main.py "打开设置，看看当前连接的 WiFi"   # 直接执行一个任务
python3 main.py -i                                # 交互模式，连续下达任务
python3 main.py --shot                            # 截一张图，确认画面与坐标
python3 selftest.py                               # 离线自测，无需手机和 Key
```

**打包成双击即用的 macOS 应用**：

```bash
bash build_app.sh        # 产物：dist/AI手机助手.app
```

---

## 特性

- **零第三方依赖**：桌面端仅用 Python 标准库实现网络请求、图像处理与 GUI，拉下来就能跑
- **安全的动作执行**：动作白名单 + 坐标边界保护，模型无法发出越界或非法指令
- **防卡死 / 防打转**：画面停滞检测、重复动作检测、最大步数、每次 ADB 调用都有超时
- **完善的可观测性**：每次运行独立归档日志（完整对话、结构化动作记录、逐步截图），崩溃也不丢
- **Key 不落盘**：默认从环境变量读取，日志中的 Key 自动打码
- **可移植架构**：`adb.py` 是唯一接触设备的层，替换成无障碍实现后，上层 Agent 与模型逻辑零改动
- **离线自测**：`selftest.py` 提供不依赖真机和 Key 的逻辑自测

---

## 项目结构

```
.
├── macapp.py        # 图形界面（tkinter）
├── main.py          # 命令行入口：自检 / 截图 / 执行任务 / 交互
├── agent.py         # Agent 主循环、提示词、动作解析与防循环
├── llm.py           # OpenAI 兼容的多模态客户端（标准库 urllib）
├── adb.py           # 设备操作的唯一出口（截屏 / 点击 / 滑动 / 输入）
├── tools.py         # 动作执行器（白名单 + 边界保护）
├── image.py         # 截图缩放、压缩、网格标尺
├── config.py        # 配置加载与 Key 脱敏
├── paths.py         # 开发态 / 打包态路径处理
├── run_logger.py    # 运行日志归档
├── selftest.py      # 离线自测
├── config.json      # 配置模板
├── build_app.sh     # 一键打包 macOS .app
├── android-app/     # 安卓无障碍端（Kotlin，开发中）
├── docs/            # 设计稿等资料
├── DESIGN.md        # 详细设计文档
└── PROMPT.md        # 需求与提示词记录
```

完整配置项说明、中文输入方案、坐标精度调优与故障排查，详见旧版使用文档与
[`DESIGN.md`](DESIGN.md)；安卓端实现思路见 [`android-app/README.md`](android-app/)。

---

## 路线图

- [x] 桌面端：纯视觉操作闭环（Python + ADB）
- [x] 图形界面与完整日志
- [ ] 安卓端：接入 AI 执行循环，把 UI 控件树接入提示词
- [ ] 读取系统 UI 控件树做精确定位（桌面端已预留 `uiautomator` 接口）
- [ ] 悬浮窗 / 通知栏紧急停止
- [ ] 更多模型与厂商私有参数适配

欢迎在 Issues 提建议和报 bug。

---

## 隐私与安全说明

- 程序在**你自己的电脑/手机**上运行，截屏仅在「你的设备 → 你配置的模型接口」之间传输，作者不收集任何数据。
- API Key 只保存在本地配置或环境变量中，不会被写入日志（日志里仅显示打码后的前后几位）。
- 程序具备自动操控手机的能力，请在**你自己的设备**上使用；运行期间留意它的动作，涉及支付、密码、银行等安全页面时系统本身也会禁止截屏。
- 因自动操作产生的后果由使用者自行承担，建议先用无关紧要的任务熟悉它的行为。

---

## 致谢

思路受 Mobile Agent、「视觉 + UI 树」混合定位等开源工作的启发，详见设计文档。

## 协议

[MIT License](LICENSE) © 潘纸盒（panzih）
