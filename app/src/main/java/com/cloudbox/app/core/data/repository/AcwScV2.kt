package com.cloudbox.app.core.data.repository

/**
 * acw_sc__v2 反爬 cookie 计算。
 *
 * 背景：蓝奏云部分分享页会在首次访问时下发 JS 挑战（页面含 acw_sc__v2 相关脚本），
 * 要求浏览器执行一段混淆 JS 计算出 cookie 后才能拿到真实内容。
 *
 * 算法来源：LanZouCloud-API utils.py（社区逆向，2025 年仍有效的实现）。
 * 原理：页面脚本中 var arg1='<hex>'（或 \x 转义形态），把 hex 逐字节与固定密钥
 * "3000176000856006061501533003690027800375" 异或，迭代 2 轮得到 cookie 值。
 *
 * ⚠️ 若蓝奏云更新挑战算法，本实现会失败——届时解析流程自动跳过（不阻塞），
 * 用户可改用第三方解析服务或手动打开一次分享页导入 cookie。
 */
object AcwScV2 {

    private const val HEX_XOR_KEY = "3000176000856006061501533003690027800375"

    /** 从分享页 HTML 计算 acw_sc__v2 cookie 值；无法计算返回 null */
    fun compute(html: String): String? {
        return runCatching {
            // 页面两种形态：arg1='\x6e\x62...' 或 arg1='6e6232...'
            val arg1 = Regex("""arg1='([^']+)'""").find(html)?.groupValues?.get(1) ?: return null
            var hex = if (arg1.contains("\\x")) arg1.replace("\\x", "") else arg1
            // 迭代 2 轮异或（源码 calc_acw_sc__v2 实现）
            repeat(2) {
                val sb = StringBuilder()
                for (i in hex.indices step 2) {
                    val b = hex.substring(i, i + 2).toInt(16)
                    val k = HEX_XOR_KEY[(i / 2) % HEX_XOR_KEY.length].digitToInt(16)
                    sb.append((b xor k).toString(16).padStart(2, '0'))
                }
                hex = sb.toString()
            }
            "acw_sc__v2=$hex"
        }.getOrNull()
    }
}
