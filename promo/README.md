# promo · 宣传物料

给 B 站做的三条片子，以及各自的封面与数据出处。

## 目录

```
promo/
  films/                     成片（不入库，只留本机）
  fonts/                     三个项目共用的一份字体（硬链接进各项目，磁盘只占一份）
  projects/
    v3/                      ① 宣传片 15 秒
      cover-bilibili/        它的 B 站封面（4:3 + 16:9）
    compare/                 ② 体积对比 12 秒
      cover/                 它的 B 站封面
    tree/                    ③ 控件树 20 秒
      cover/                 它的 B 站封面
  legacy/                    早期版本（15s Python / 60s / 90s / onetake），只留不维护
  README.md                  这一份
```

## 三条片子

| 片子 | 时长 | 讲什么 | 说明 |
|---|---|---|---|
| `films/纸盒-宣传片-15s-横屏.mp4` | 15s | 说五个字，它自己下载、安装、打开 | `projects/v3/` |
| `films/纸盒-体积对比-12s.mp4` | 12s | 都能操作手机、效果一样，体积差 61 倍 | `projects/compare/发布信息.md` |
| `films/纸盒-控件树-20s.mp4` | 20s | 为什么只发控件树不发截图 | `projects/tree/brief.md` |

每个项目目录里的 `brief.md` 记着**每个数字的出处**与分镜；`发布信息.md` 是可直接粘贴的
标题与简介。改片之前先看 `brief.md`，别把已经核对过的数改错。

## 重出一条片子

```bash
cd promo/projects/tree          # 换成要重出的项目
node "$HOME/.workbuddy/skills/motion-showreel/scripts/audio.mjs" build score.mjs
sh build.sh                      # 渲染 + 技术验收，产物在 out/final.mp4
cp out/final.mp4 ../../films/纸盒-控件树-20s.mp4
```

重出封面：

```bash
cd promo/projects/tree/cover
node render.mjs square.html 1600x1200 cover-4x3.png    # 4:3 首页与分区列表
node render.mjs wide.html   1920x1080 cover-16x9.png   # 16:9 播放页与推荐流
```

需要 `$SKILL` 指向 motion-showreel 技能目录；不设就用 `~/.workbuddy/skills/motion-showreel`。

## 约定

- **成片只放 `films/`**，不要散在项目里或根目录。
- **命名 = 内容-时长**；与版本号无关的片子不写版本号。
- **字体只存一份**在 `promo/fonts/`，各项目用硬链接引；换字体三处一起换。
- `out/` `qa/` `audio/` 是可重算的中间产物，不入库。
- 片子里出现的数字必须追得到出处；追不到就别往画面里写。
