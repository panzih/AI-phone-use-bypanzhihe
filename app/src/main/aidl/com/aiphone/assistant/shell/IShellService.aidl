// 跑在特权进程里的"命令执行服务"。
//
// ## 它为什么存在
//
// 应用自己（普通 App 的 uid）既开不了虚拟屏，也不能执行 screencap / input ——
// 那些都要 shell 身份。Shizuku 的作用就是：**借一个以 shell（uid 2000）
// 身份运行的进程**给我们用，我们通过 binder 调它。
//
// ## 为什么不用 Shizuku.newProcess
//
// 官方 changelog 明确写着 `Shizuku#newProcess` 准备移除：
//
//     newProcess uses texts to communicate, which is not efficient and
//     unreliable... Prepare to remove Shizuku#newProcess
//
// 而且它走的是**文本**通道 —— 截图是二进制，过一遍文本编码必然毁数据。
// 所以这里用官方的 UserService + AIDL，拿的是原始字节。
//
// ## 关于方法编号
//
// Shizuku 约定：AIDL 里声明 `destroy()` 并给它固定的 transaction id
// 16777114，它在解绑时会调这个方法让我们清理。这个数字是 Shizuku 定的，
// 不能改。
//
// 注意：AIDL 编译器要求**要么每个方法都写编号，要么一个都不写**。
// 只给 destroy 写、别的空着会直接报
// "You must either assign id's to all methods or to none of them"。
// 所以下面 exec / execBytes 也各自给了编号。
package com.aiphone.assistant.shell;

interface IShellService {

    /** 解绑时由 Shizuku 调用 */
    void destroy() = 16777114;

    /**
     * 执行一条 shell 命令。
     *
     * @return 标准输出（标准错误会拼在后面）；命令不存在或超时也返回文本，
     *         不抛异常 —— 调用方看到的应该是"命令说了什么"，而不是 binder 异常
     */
    String exec(String command) = 1;

    /**
     * 执行一条命令，把标准输出当**二进制**收回来。
     *
     * 截图专用：screencap 吐的是 PNG，走文本通道会被编码毁掉。
     *
     * @return 原始字节；失败返回空数组
     */
    byte[] execBytes(String command) = 2;
}
