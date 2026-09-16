package com.aiphone.assistant.skill

import com.aiphone.assistant.memory.MemoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 技能：取回之前存下来的记忆。
 *
 * ## 为什么记忆要做成"技能"而不是预置进提示词
 *
 * 记忆是**越长越多**的。全塞进系统提示词有两个问题：
 *
 *   1. 每一轮都要重发，token 一直涨（而且大部分跟当前任务无关）
 *   2. 它会破坏系统提示词的静态性 —— 刚存完一条，前缀就变了，
 *      上下文缓存全部失效
 *
 * 做成技能之后，模型在**需要的时候**要一次：任务涉及"我平时怎么做的"
 * 才会去取，取回来的内容进历史，之后靠缓存复用。
 *
 * ## 为什么不按相关性挑几条给
 *
 * 没有向量检索，就只能按关键词粗筛 —— 而用户的说法和记忆文件里的用词
 * 经常对不上（"照老规矩" vs "发送前确认一下"），粗筛很容易把最该给的
 * 那几条筛掉。记忆本来就是纯文本，条数也不多（一次任务最多沉淀一条），
 * 所以**直接给全文**最稳。真涨到几百条再谈检索。
 */
object MemorySkill : Skill {

    override val id: String = "recall_memory"

    override val summary: String =
        "查看用户的全部记忆（偏好、常用应用、这台机器上踩过的坑）。" +
            "想知道用户是什么样的人、以前是怎么操作的，就用它"

    override val doc: String = """
# 技能：recall_memory —— 取回记忆

## 用途
拿到之前任务里沉淀下来的「用户洞察」。这些是**跨任务成立**的东西：
用户的偏好和习惯、常用应用、这台设备上哪些页面有坑、用户对任务的固定要求。

**什么时候该用：**
- 用户说"照我平时的习惯""还用上次那个"这类话
- 你不确定用户的偏好（比如用哪个应用、要不要确认）
- 任务开始前想先看看有没有相关经验

**什么时候不该用：**
- 已经取过一次了 —— 结果就在上下文里，不要重复取
- 任务跟用户偏好完全无关（比如"打开设置"）

## 参数
无。

## 返回
记忆文件的**全文**（markdown，按时间从早到晚，最新的在最后）。
一条都没有时明确说明"还没有记忆"。

## 示例
{"use_skill": "recall_memory"}

## 注意
- 记忆是**过去**的认知，可能过时。跟当前界面冲突时以当前界面为准。
- 里面可能有用户自己写的规则，优先遵守。
""".trimIndent()

    override suspend fun run(ctx: SkillContext, args: JSONObject?): String =
        withContext(Dispatchers.IO) {
            if (!MemoryStore.exists(ctx.context)) {
                return@withContext "还没有存下任何记忆。可能是「开启记忆」还没打开，" +
                    "或者你还没完成过任务。"
            }

            // 记忆是**只增不减**的一个文件，所以这里直接把全文给它。
            // 不做检索、不分段挑：现在没有向量检索，而按关键词粗筛
            // 反而可能把最相关的那几条筛掉（用户的口语表达和文件里的
            // 用词经常对不上）。条数真有几百条再考虑换方式。
            val text = MemoryStore.read(ctx.context).trim()
            val (count, chars) = MemoryStore.stats(ctx.context)

            buildString {
                appendLine("用户的记忆（共 $count 条，$chars 字符，最新的在最后）：")
                appendLine()
                appendLine(text)
                appendLine()
                append("记忆是过去的认知，可能已经过时；和当前界面对不上时以当前界面为准。")
            }
        }
}
