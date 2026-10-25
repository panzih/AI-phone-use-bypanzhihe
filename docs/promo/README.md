# 纸盒 · 宣传片（60 秒）

**成片**：`纸盒-宣传片.mp4` · 1080×1920 · 30fps · **60.0 秒** · H.264 + AAC · 56MB
**封面**：`封面-结尾标语.png`（另有 `封面-悬浮条.png`、`封面-两种模型.png`、`封面-主界面.png`）

## 片子结构（9 段）

| 时间 | 内容 |
|---|---|
| 0.0–5.0 | 开场：logo 线稿逐段画出（与 App 内 `res/drawable/ic_box_logo.xml` 同一份几何）→「纸盒」→ 不需要 root / 电脑 / Shizuku |
| 5.0–11.0 | 痛点：「帮我把 Wi-Fi 关了」→ 你还是自己戳了 8 下屏幕 →「这件事，本该它自己来」 |
| 11.0–14.6 | **真机录屏**：主界面空态（AI 操作手机 / By 潘纸盒） |
| 14.6–23.0 | **真机录屏**：设置页——两种模型 + 思考强度滑块，一路滑到「更多设置」 |
| 23.0–32.0 | **真截图** 02-悬浮窗：悬浮条高亮框 + 落点水波 +「随时可以急停」 |
| 32.0–40.0 | 排版段：「两种模型，随便切」双卡 + 思考强度 OFF/LOW/HIGH/MAX 药丸 |
| 40.0–46.6 | **真截图** 权限页：「它只在你手机上跑」+ Key 加密 / 无广告无统计 / 日志可导出 |
| 46.6–52.0 | 三步上手：装 APK → 开一次无障碍 → 说一句话 |
| 52.0–60.0 | 收尾：logo →「装一个 APK，开一次无障碍权限 / 然后就不用管了」→ **「不绑定厂商，不限定机型」** → 仓库地址 + MIT |

## 素材（都是这台机器上真机上截的，不是画的）

`raw/` 里全部来自 Android 模拟器 AIPhone_API35 上真跑的 0.10.5：

- `clip2.mp4`：本次 `adb shell screenrecord` 录的 25.0s 界面走查（主界面空态 → 侧边栏 → 设置页上滑 → 权限页）
- `clip1.mp4`：第一版录的 12.8s（**作废**：那轮点过「发送」，主界面挂着红色「无障碍未开启」报错卡，不能用）
- `01-主界面.png` `02-悬浮窗.png` `03-设置页.png`：仓库原有截图（`docs/screenshots/`）
- `settings-top/mid/bottom.png`、`permissions.png`、`about.png`、`sidebar.png`、`main-empty.png`：本次重新截的

## 上一版被指出「中间几个镜头是坏的」，坏在这三处

1. **内嵌录屏里有报错卡**：第一版从 `clip1.mp4` 取了 0.6s 起的画面，正好把主界面上的
   红色「无障碍未开启 / 还没开启无障碍服务」整段录了进去 —— 宣传片里出现报错，最刺眼。
   现在换成本次重录的 `clip2.mp4`（`pm clear` 后干净启动，**全程没有触发过发送**），
   开场只取 0.35→2.9s 的空态主界面。
2. **两个手机壳是黑的**：`loadImage()` 读的是 `raw/01-主界面.png` 等三张图，但当时它们
   还在 `docs/screenshots/` 没拷进 `raw/` —— `CGImageSourceCreateWithURL` 返回 nil，
   「点在哪，看得见」（悬浮窗）和「两种模型」（双机位）两段的手机屏幕**整块是黑的**。
   现在三张图已拷进 `raw/`，并且 `loadImage()` 载入失败会往 stderr 打 `!! 载入失败`。
3. **悬浮窗那段两行字幕叠在一起**：第一版第二条字幕（「觉得不对？左上角悬浮条随时急停」）
   出现时，第一条的副字幕还没退场，同一位置压出双层字。现在统一用 `Caption` + `drawCaptions`：
   一条完全退场（end-0.4 起淡出）后下一条才进场，时间上不再重叠。

另外「两种模型」那段原来用的是 `01-主界面.png`，但它本身几乎是空屏，双机位一边是空的 ——
现在改成纯排版的「自定义模型 / 免费模型」双卡，配一张思考强度药丸。

## 怎么重新生成

需要 macOS + Xcode 命令行工具（CoreGraphics / CoreText / AVFoundation / Vision，无第三方依赖）。

```bash
cd docs/promo
swiftc -O -o /tmp/promorender render.swift && /tmp/promorender       # 画面 → paperbox-promo-video.mp4（无声）
swiftc -O -o /tmp/audiogen  audio.swift   && /tmp/audiogen          # 配乐 → /tmp/promo.wav
swiftc -O -o /tmp/mux mux.swift && /tmp/mux paperbox-promo-video.mp4 /tmp/promo.wav 纸盒-宣传片.mp4
```

- `render.swift`：全部画面 CoreGraphics 现画（渐变背景、手机外壳、排版、logo 线稿、水波、悬浮条高亮框）；
  文字自动缩宽防溢出。时间线是文件里的 `scenes` 数组，改文案只动 `s1…s9`。
- `audio.swift`：配乐**算出来的**（Am–F–C–G pad + 低频心跳 + 场景切换风铃/whoosh），不依赖音频素材。
  改时长只需改 `dur` 与 `chords`。
- `mux.swift`：AVMutableComposition 合成音轨。

## 验收方式（没有眼睛也能查）

```bash
# 逐帧 OCR：看每一秒画面上到底有什么字、会不会压字
swift OCR 工具渲染 → 见会话脚本 /tmp/ocr.swift
```

这次每条都验过：全片 0→60s 每 1.5s 抽一帧做 Vision OCR，确认①每一段的字幕齐全且不重叠、
②手机壳里是真实界面文字（设置页 / 权限页 / 悬浮条 / 主界面空态）、③没有任何报错文案。

## 已知取舍

- 模拟器上「点发送」跑不起来：无障碍服务在 `dumpsys` 里已 bound，但 App 侧 `AutoService.isConnected`
  仍为 false，一律提示「无障碍未开启」。所以片子里没有「AI 真跑完一轮任务」的镜头。
  真机上补 10 秒实录（悬浮条推进 + 水波纹 + 完成），这条片子会强一个档次。
- 悬浮窗截图里的悬浮条文案是「第1/30步 正在：等待25000ms」——是仓库里那张实测截图的原样内容，
  片中用高亮框把它圈出来，不做改字（不 P 假数据）。

## 关于「自己开模拟器跑一段真任务」（试过了，卡在两处系统坑）

这一轮确实把模拟器起来跑了（AVD `AIPhone_API35`，无窗口 + swiftshader），过程记录：

1. **先把网络修好**：模拟器里 `airplane_mode_on=1`，guest 只有 `lo`、`Active default network: none` ——
   `cmd connectivity airplane-mode disable` + `svc wifi enable` 后拿到 `10.0.2.15`，`opencode.ai` 能解析（214ms）。
   顺带确认宿主机能打通 `https://opencode.ai/zen/v1/models`（HTTP 200）。
2. **无障碍**：`settings put secure enabled_accessibility_services ...` 能把服务拉起来，日志有
   `AutoService: 无障碍服务已连接`；但服务随进程反复 `已断开/已连接`。
3. **卡点 A**：点「发送」时 App 侧 `AutoService.isConnected` 仍为 false（系统 `dumpsys accessibility`
   说 bound，但静态实例是 null）→ 一律提示「还没开启无障碍服务」，任务在第 0 步就 return。
4. **卡点 B**：等 a11y 过了的那次，App 立刻抛
   `ForegroundServiceDidNotStartInTimeException: OverlayService` —— 无窗口 swiftshader 下启动悬浮窗
   FGS 超过 5s，进程被系统干掉（MainActivity 跟着退出）。

所以「AI 真跑完一轮」的镜头这一版还是没拿到。可行的下一步（任选）：
- 用**带窗口 + 硬件 GPU** 的模拟器（这台机器上我起窗口模式时 QEMU 主循环挂住，才退回无窗口）；
- 或者连**真机**，adb 一插就能录，10 秒素材足够；
- 或者把 `tools/mock_llm.py` 起在宿主机、App 填 `http://10.0.2.2:8765`，用脚本模型跑一轮——
  界面上悬浮条、水波、步数是真的，只是思考内容按脚本走。
