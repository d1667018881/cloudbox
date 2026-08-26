package com.cloudbox.app.core.data.repository

/**
 * acw_sc__v2 反爬 cookie 计算。
 *
 * 背景：蓝奏云部分分享页会在首次访问时下发 JS 挑战（页面含 acw_sc__v2 相关脚本），
 * 要求浏览器执行一段混淆 JS 计算出 cookie 后才能拿到真实内容。
 *
 * 算法来源：LanZouCloud-API utils.py（MIT，commit 3bb917f）calc_acw_sc__v2 原版逐行移植
 * （审查 CODE_REVIEW #4 修正：旧实现缺 unsbox 置换、误用 2 轮 XOR、密钥取值粒度错误）。
 * 原版逻辑：
 *   1. unsbox：40 位置换表 v1 重排 arg1（输入第 i 个字符放到 v1 中值为 i+1 的位置）
 *   2. hex_xor：单轮，每 2 字符（1 字节）与固定密钥同位置 2 字符异或，输出 2 位 hex
 *
 * ⚠️ 若蓝奏云更新挑战算法，本实现会失败——届时解析流程自动跳过（不阻塞），
 * 用户可改用第三方解析服务或手动打开一次分享页导入 cookie。
 */
object AcwScV2 {

    private const val HEX_XOR_KEY = "3000176000856006061501533003690027800375"

    /** 原版 unsbox 置换表（长度 40，值 1-40，表示输入字符的目标位置） */
    private val UNSBOX = intArrayOf(
        15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
        39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36
    )

    /** 从分享页 HTML 计算 acw_sc__v2 cookie 值；无法计算返回 null */
    fun compute(html: String): String? {
        return runCatching {
            // 原版正则 arg1='([0-9A-Z]+)'；兼容旧版 \x 转义形态（保留兼容逻辑）
            var arg1 = Regex("""arg1='([0-9A-Z]+)'""").find(html)?.groupValues?.get(1)
                ?: Regex("""arg1='([^']+)'""").find(html)?.groupValues?.get(1)
                ?: return null
            if (arg1.contains("\\x")) arg1 = arg1.replace("\\x", "")

            // 1) unsbox：输入第 i 个字符放到置换表中值为 i+1 的位置
            val unboxed = CharArray(UNSBOX.size)
            for (i in arg1.indices) {
                if (i >= UNSBOX.size) break
                val target = UNSBOX.indexOf(i + 1)
                if (target >= 0) unboxed[target] = arg1[i]
            }
            val permuted = String(unboxed)

            // 2) 单轮字节级 XOR：每 2 字符与密钥同位置 2 字符异或（原版逐行一致）
            val sb = StringBuilder()
            for (idx in 0 until minOf(permuted.length, HEX_XOR_KEY.length) step 2) {
                val v1 = permuted.substring(idx, idx + 2).toInt(16)
                val v2 = HEX_XOR_KEY.substring(idx, idx + 2).toInt(16)
                sb.append((v1 xor v2).toString(16).padStart(2, '0'))
            }
            "acw_sc__v2=${sb}"
        }.getOrNull()
    }
}
