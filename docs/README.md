# 文档索引

仓库里给人读的文档统一放在 `docs/`。宣传物料的脚本与成片在根目录
[`promo/`](../promo/)，不混在文档里。

## 目录

| 路径 | 内容 |
|---|---|
| [`ROADMAP.md`](ROADMAP.md) | 待办路线图：还没做的、明确不做的 |
| [`architecture/`](architecture/) | 架构与通道设计（`agent-loop`、`free-lane-wire`、`oc-free-transport`） |
| [`plan/`](plan/) | 整改清单、代码审查 bug 清单 |
| [`evaluation/`](evaluation/) | 项目评估报告 |
| [`release-notes/`](release-notes/) | 每个版本的发布说明，Release workflow 从这里取 |
| [`testing-device.md`](testing-device.md) | 真机 / 模拟器冒烟测试说明 |
| [`screenshots/`](screenshots/) | README 与宣传素材用的界面截图 |

## 相关位置

| 路径 | 内容 |
|---|---|
| [`../CHANGELOG.md`](../CHANGELOG.md) | 按版本汇总的变更记录 |
| [`../promo/`](../promo/) | 宣传片与封面工程（脚本入库，成片/素材只留本机） |
| `../else/` | 内部资料（任务清单、证据、归档），Git 忽略，只在本机 |
| [`../update-site/`](../update-site/) | 自动更新站 |

## 约定

- **发布说明**按 `release-notes/v<版本>.md` 命名，例如 `v0.14.1.md`；
  发版时推一个 `v<版本>` 标签，workflow 会自动取用。
- 状态类内容只写一处：版本号、待办状态以 `ROADMAP.md` / `CHANGELOG.md` 为准。
- 历史评估与计划只读，不要当作现状。
