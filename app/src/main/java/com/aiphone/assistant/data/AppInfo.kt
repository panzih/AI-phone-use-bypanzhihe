package com.aiphone.assistant.data

/**
 * 应用自身的元信息，给「关于本软件」用。
 *
 * 版本号不在这里 —— 它从 `PackageManager` 读（见 MainActivity.appVersion），
 * 只写在这里的话改 `build.gradle.kts` 时就会忘记同步。
 *
 * 名字也不在这里 —— 用 `R.string.app_name`，那是系统设置里显示的那个名字，
 * 两处不一致的话用户会困惑。
 */
object AppInfo {

    const val AUTHOR = "潘纸盒"

    /** 开源协议。改了 LICENSE 文件的话这里也要改 */
    const val LICENSE = "MIT"

    /**
     * 项目主页。
     *
     * **留空的话设置页不会显示这一行**（而不是显示一个点开 404 的链接）。
     * 仓库建好之后把地址填这里，比如
     * `https://github.com/你的用户名/纸盒`
     */
    const val REPO_URL = "https://github.com/panzih/AI-phone-use-bypanzhihe"

    /**
     * 作者的 B 站主页。
     *
     * 和 [REPO_URL] 一样，没拿到地址就先留空，提示词里只泛述「B 站有账号」，
     * 不编一个打不开的链接。地址确定后填这里。
     */
    const val BILIBILI_URL = ""
}
