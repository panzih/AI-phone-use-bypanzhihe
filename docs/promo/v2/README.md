# v2 · 90 秒解说版（源码与素材）

成片在上一级：`docs/promo/纸盒-宣传片-90秒解说版.mp4`
制作说明：`docs/promo/宣传片-90秒版-制作方案.md`

## 文件

| 文件 | 作用 |
|---|---|
| `make_timeline.py` | 解说词在这里改。逐段 `say` 合成语音，按语音长度算出每段场景起止，产出 `timeline.json` |
| `timeline.json` | 场景时间表（渲染器与混音都读它）——**改文案后重跑脚本即可，别手改** |
| `render2.swift` | 画面渲染：1080×1920 / 30fps / H.264，全部 CoreGraphics 现画 + 内嵌录屏与截图 |
| `mix.swift` | 音轨：解说摆位 + 算法生成配乐 + 人声一起就压低配乐（ducking）+ 归一 |
| `mux2.swift` | 把画面和音轨合成 mp4 |
| `raw/` | 素材（录屏与截图），**不进 git** |
| `audio/` | 每段解说 aiff，可随时重新生成，**不进 git** |
| `paperbox-promo-v2.mp4` | 无声中间产物，**不进 git** |

## 一条命令重做

```bash
cd docs/promo/v2
python3 make_timeline.py
swiftc -O -o /tmp/promo2 render2.swift && /tmp/promo2
swiftc -O -o /tmp/promomix mix.swift && /tmp/promomix
swiftc -O -o /tmp/promux mux2.swift && \
  /tmp/promux paperbox-promo-v2.mp4 /tmp/promo2.wav ../纸盒-宣传片-90秒解说版.mp4
```

## raw/ 里的东西是哪来的

- `task-loop.mp4`：BlueStacks（Android 13，900×1600）`adb shell screenrecord` 实录 0.10.7 跑任务，
  未剪辑未加速。可用的动态只有 **9.4s–14.5s**，后面画面被模拟器合成器冻住了。
- `main-empty.png`：`pm clear` 后的全新空态。
- `settings-model.png` / `permissions.png`：同一台机器现截，0.10.7。
