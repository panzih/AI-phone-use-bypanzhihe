# 设备冒烟测试

`app/src/androidTest/` 下的测试需要一台真机或模拟器。它们不替代人工验收，
只覆盖三条最容易在打包/合并时坏掉的链路：

| 测试 | 覆盖什么 | 设备前提 |
|---|---|---|
| `AccessibilityServiceSmokeTest` | manifest 里的无障碍服务声明、`accessibility_service_config.xml` 的能力位；服务已授权时能否连上并读到当前窗口 | 建议先启用「纸盒」无障碍 |
| `OverlayServiceSmokeTest` | 悬浮条服务能进入前台、常驻通知出现；有悬浮窗权限时发布实例，没有权限时按设计不画窗口但继续活着 | 建议先打开「显示在其他应用上层」 |
| `LogExportSmokeTest` | 导出的 zip 含 `manifest.txt` / `device.txt` / `index.csv` / `runs.txt`；`run.log` 里的 `sk-` Key 和 Bearer 真的被脱敏 | 无 |

## 运行

1. 手机开启开发者选项和 USB 调试，连上电脑，确认 `adb devices` 能看到设备。
2. 在手机上安装一次 debug 包并完成引导：

   ```bash
   ./gradlew :app:installDebug
   ```

3. 打开「纸盒」，在「权限」页启用无障碍；需要测悬浮条时再打开悬浮窗权限。
4. 跑设备测试：

   ```bash
   ./gradlew :app:connectedDebugAndroidTest
   ```

   只跑某一个类：

   ```bash
   ./gradlew :app:connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.aiphone.assistant.log.LogExportSmokeTest
   ```

5. 没有设备时，至少确认测试代码能编译：

   ```bash
   ./gradlew :app:compileDebugAndroidTestKotlin
   ```

## 说明

- 无障碍未启用时，`accessibilityService_whenEnabled_canReadCurrentWindow` 会 **skip**，
  不会判失败；manifest/config 那条始终会跑。
- 悬浮条测试会临时用 `appops` 打开悬浮窗权限（需要 adb shell 身份，`UiAutomation`
  自带），结束后停止服务。没有权限的设备走「无权限」分支。
- 日志导出测试会在应用私有目录里造一个假的运行目录，导出后立即删掉 zip 和目录。
- 这些测试**不进入 CI**：GitHub 的 ubuntu runner 没有设备，模拟器冷启动成本高且不稳定。
  CI 只跑 JVM 单测、`lintDebug` 和 `assembleDebug`（见 `.github/workflows/ci.yml`）。
