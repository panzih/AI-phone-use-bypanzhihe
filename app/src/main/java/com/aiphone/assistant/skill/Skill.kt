package com.aiphone.assistant.skill

import android.content.Context
import com.aiphone.assistant.ChannelController
import org.json.JSONObject

/**
 * 技能（skill）—— 让模型能拿到"界面之外"的信息。
 *
 * ## 为什么要有这一层
 *
 * 模型只看得到控件树和截图，于是有两类事它做不好：
 *
 *   1. **它不知道手机里有什么。** 想打开微信，只能凭记忆编一个包名
 *      `com.tencent.mm` —— 编错了就是启动失败，而且报错是"没找到应用"，
 *      完全看不出是"名字记错了"。
 *   2. **它记不住事情。** 上下文一清就全忘了（记忆、用户洞察属于这类）。
 *
 * 技能的定位就是：**模型主动要、系统去取、把结果塞回上下文**。
 * 和"动作"的区别在于动作改的是手机状态，技能只是取信息。
 *
 * ## 怎么扩展
 *
 * 新加一个技能 = 实现这个接口 + 注册进 [SkillRegistry]。系统提示词里的
 * 目录是从注册表生成的，不用改提示词代码。
 *
 * 每个技能必须自带两样东西：
 *   - [summary] 一行话，进系统提示词的目录（模型靠它决定要不要用）
 *   - [doc] 完整说明文档，模型用 `use_skill: "list_skills"` 按需要
 *
 * 分成两层的理由和截图一样：目录很便宜（几十个 token）可以常驻，
 * 完整文档只在真要用的时候拉一次。
 */
interface Skill {

    /** 调用时用的 id，模型就写这个。小写 + 下划线 */
    val id: String

    /** 一行话说明，进系统提示词的技能目录 */
    val summary: String

    /** 完整说明文档：用途、参数、返回什么、什么时候该用、什么时候不该用 */
    val doc: String

    /**
     * 执行。
     *
     * @param args 模型给的参数，可能为 null。没有参数的技能直接忽略它
     * @return 要回灌给模型的文本。**这段文本会留在上下文里**，
     *         所以既要够用又别啰嗦 —— 它每一轮都会被重发（缓存命中，但仍有成本）
     */
    suspend fun run(ctx: SkillContext, args: JSONObject?): String
}

/**
 * 技能运行时能拿到的东西。
 *
 * 刻意保持很小：技能不该直接操作界面（那是动作的事），
 * 它只负责"取信息"。真需要操作时应该反过来让模型给动作。
 */
class SkillContext(
    /** 读 PackageManager、文件这些要用 */
    val context: Context,
    /** 想知道当前前台是哪个应用时用 */
    val controller: ChannelController,
)

/** 技能执行结果。失败不抛异常，而是一段能直接回给模型看的中文说明 */
data class SkillOutcome(
    val ok: Boolean,
    val text: String,
)
