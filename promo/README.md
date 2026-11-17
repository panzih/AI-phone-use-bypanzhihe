# 宣传物料

所有宣传片、封面工程都在这里。**脚本、HTML、说明入库；成片、录制素材、音频、
渲染中间产物不入库**（规则见根目录 [`.gitignore`](../.gitignore)）。
成片只留本机，需要发布时从这里取。

## 当前版本

| 目录 | 内容 | 规格 | 成片 |
|---|---|---|---|
| [`v3/`](v3/) | 15 秒横屏宣传片 | 1920×1080 / 30fps | `v3/纸盒-宣传片-15s-横屏.mp4` |
| [`compare/`](compare/) | 体积对比片：安装包 / 占用 / 提示词 | 1920×1080 / 12s | `compare/纸盒-体积对比-12s.mp4` |
| [`cover/`](cover/) | B 站封面（16:9 与 4:3） | — | `cover/cover-*.png` |

## 旧版（`legacy/`）

| 目录 | 内容 | 规格 |
|---|---|---|
| [`legacy/15s-python/`](legacy/15s-python/) | 早期 15 秒版，Python 逐帧渲染 | 1920×1080 / 15s |
| [`legacy/60s/`](legacy/60s/) | 60 秒竖屏，只有字幕、无解说 | 1080×1920 / 60s |
| [`legacy/90s/`](legacy/90s/) | 90 秒竖屏，中文解说 + 配乐 | 1080×1920 / 90.6s |
| [`legacy/onetake/`](legacy/onetake/) | 60 秒一镜到底，无旁白（onetake + HTML） | 1080×1920 / 60s |

`legacy/60s`、`legacy/90s`、`legacy/onetake` 以及 `v3`、`compare`、`cover`
都自带说明（`README.md` / `brief.md` / `发布信息.md`），写明了用途与重建方式；
`legacy/15s-python/` 只留渲染脚本，已被 `v3/` 取代。旧的 Swift 渲染脚本里用的是本机
绝对路径（`~/DSH/AI手机/promo/...`），换机器要改。

## 备注

- `promo/.venv/` 是本机 Python 虚拟环境，不入库。
- `v3/` 与 `compare/` 用 `build.sh` 一条命令重建，产物在各自的 `out/`（不入库）。
- 成片命名与版本无关时，按「内容-时长」写；发版后需要长期保留的成片再单独归档。
