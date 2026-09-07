package com.cloudbox.app.common

/**
 * 页面 HTML 正则提取工具。
 *
 * 为什么单独封装：蓝奏云多个接口的关键参数（t/k/sign/fid/wp_sign）散落在
 * HTML/JS 里且格式随版本变化，集中管理便于接口变更时一处修改。
 * 提取失败一律返回 null，由调用方决定降级策略，绝不抛异常。
 *
 * ─────────────────────────────────────────────────────────────
 * 2026-09 实测校准（本文件大幅改写的依据）
 * ─────────────────────────────────────────────────────────────
 * 蓝奏云在 2025-2026 改版，页面结构与旧版 LanZouCloud-API 文档已完全不同：
 *
 * 1) 单文件分享页现在**不含** sign，只有一个 iframe：
 *      <iframe class="ifr2" name="1788754062" src="/fn?VzFVPwltBGZUOl…_c_c"></iframe>
 *      <script>var fid = 96810913;</script>
 *    sign 藏在 iframe 页（/fn?…）里：
 *      var wp_sign  = 'VzFXaQEwDz5UXQE_bBjYGOgZ…_c_c';
 *      var ajaxdata = 'asXy';
 *      var kdns     = 1;
 *    旧版的 'sign':xxx / sign=xxx& / data-id="xxx" 全部失效（旧实现解析必挂的根因）。
 *
 * 2) 文件夹分享页的 t/k 变量名是**随机 5~12 位、可含下划线**（实测 ibligv / _hjuio），
 *    旧版正则 `var [0-9a-z]{6} = '…'` 要求恰好 6 位且不含下划线 → k 永远匹配不到。
 *
 * 3) filemoreajax.php 需要 URL 查询 `?file=<fid>`，表单还需 uid/puid/rep/up
 *    （页面 JS 实证），缺任一都可能被判非法请求。
 *
 * 因此每个提取器都是"新版优先 + 旧版兜底"双形态，任一命中即可。
 */
object HtmlExtractor {

    // ==================== 分享页（文件夹）t / k / fid / uid / puid ====================

    /** 新版 t：随机变量名 = '10 位时间戳'（实测 var ibligv = '1788754271';） */
    private val RE_T_NEW = Regex("""var\s+[0-9a-zA-Z_]+\s*=\s*'(\d{10})';""")

    /** 旧版 t：var xxxxxx = '(\d{10})'; */
    private val RE_T_OLD = Regex("""var [0-9a-z]{6} = '(\d{10})';""")

    /** 新版 k：随机变量名 = '16+ 位 hex'（实测 var _hjuio = '368d7ce8…';）
     *  变量名允许下划线、长度不限 —— 旧版 {6} 定长无下划线正是匹配失败的原因 */
    private val RE_K_NEW = Regex("""var\s+[0-9a-zA-Z_]+\s*=\s*'([0-9a-fA-F]{16,})';""")

    /** 旧版 k：var xxxxxx = '([0-9a-z]{15,})'; */
    private val RE_K_OLD = Regex("""var [0-9a-z]{6} = '([0-9a-z]{15,})';""")

    /** 文件夹 fid（新版来自 filemoreajax 的 URL 查询 ?file=3990616） */
    private val RE_FID_FILE = Regex("""filemoreajax\.php\?file=(\d+)""")

    /** 旧版 fid：'fid':'?(\d+)'?, */
    private val RE_FID_OLD = Regex("""'fid':'?(\d+)'?,""")

    /** var fid = 123;（单文件页与部分旧页面均用此形态） */
    private val RE_FID_VAR = Regex("""var\s+fid\s*=\s*(\d+)\s*;""")

    /** 分享页 lx："lx':'?(\d)'?," */
    private val RE_LX = Regex("""'lx':'?(\d)'?,""")

    /** 文件夹分享页 uid：'uid':'556911' 或 'uid':556911 */
    private val RE_UID_SHARE = Regex("""['"]uid['"]\s*:\s*'?(\d+)'?""")

    /** 文件夹分享页 puid（加密串，含下划线、以 _c_c 结尾） */
    private val RE_PUID = Regex("""['"]puid['"]\s*:\s*'([^']+)'""")

    // ==================== 单文件页 iframe / 直链参数 ====================

    /** 单文件页 iframe：<iframe class="ifr2" … src="/fn?…">
     *  （与原版 Lua `a2:match("<iframe class(.-)</iframe>")` 同款思路，此处直接取 src） */
    private val RE_IFRAME_SRC = Regex("""<iframe[^>]*\ssrc="([^"]+)"""", RegexOption.IGNORE_CASE)

    /** iframe 页签名：var wp_sign = '…'; */
    private val RE_WP_SIGN = Regex("""var\s+wp_sign\s*=\s*'([^']+)'""")

    /** iframe 页附加校验串：var ajaxdata = 'asXy';（downprocess 的 websignkey/signs） */
    private val RE_AJAXDATA = Regex("""var\s+ajaxdata\s*=\s*'([^']*)'""")

    /** iframe 页 kdns：var kdns = 1;（页面 JS 语义：killdns 未定义时降级为 0） */
    private val RE_KDNS = Regex("""var\s+kdns\s*=\s*(\d+)""")

    /** 旧版直链 sign：'sign':(.+?), */
    private val RE_SIGN_COLON = Regex("""'sign':(.+?),""")

    /** 旧版直链 sign：sign=(\w+?)&（有提取码分支） */
    private val RE_SIGN_EQ = Regex("""sign=(\w+?)&""")

    /** 旧版直链 sign：var xxxx = 'sign值';（兜底） */
    private val RE_SIGN_VAR = Regex("""var [0-9a-z]{6}\s*=\s*'(.+?)';""")

    /**
     * 回收站 formhash（mydisk.php 页面）。
     * 原版 recycle.lua 用的是宽松的 `"formhash" value="(.-)"`；
     * 这里两种写法都试，避免页面把 value 写在 name 之前（或属性顺序变化）时扣不到。
     */
    private val RE_FORMHASH = Regex("""name="formhash"\s+value="([^"]+)"""")
    private val RE_FORMHASH_ALT = Regex("""["']formhash["']\s+value=["']([^"']+)["']""")

    /** acw_sc__v2 挑战页入参（比整串 contains 精确：arg1 存在即处于挑战） */
    private val RE_ACW_ARG1 = Regex("""arg1='([^']+)'""")

    // ==================== 对外 API ====================

    fun extractT(html: String): String? =
        RE_T_NEW.find(html)?.groupValues?.get(1) ?: RE_T_OLD.find(html)?.groupValues?.get(1)

    fun extractK(html: String): String? =
        RE_K_NEW.find(html)?.groupValues?.get(1) ?: RE_K_OLD.find(html)?.groupValues?.get(1)

    /**
     * 文件夹 fid：优先 filemoreajax 的 ?file=，其次 'fid':'…'，最后 var fid = …;
     * 单文件页没有 filemoreajax，自然落到 var fid 形态。
     */
    fun extractFid(html: String): String? =
        RE_FID_FILE.find(html)?.groupValues?.get(1)
            ?: RE_FID_OLD.find(html)?.groupValues?.get(1)
            ?: RE_FID_VAR.find(html)?.groupValues?.get(1)

    fun extractLx(html: String): String? = RE_LX.find(html)?.groupValues?.get(1)

    fun extractFormhash(html: String): String? =
        RE_FORMHASH.find(html)?.groupValues?.get(1)
            ?: RE_FORMHASH_ALT.find(html)?.groupValues?.get(1)

    fun extractUid(html: String): String? = RE_UID_SHARE.find(html)?.groupValues?.get(1)

    /** 文件夹分享页 puid（filemoreajax 必需；缺失时返回 null，调用方降级为空串） */
    fun extractPuid(html: String): String? = RE_PUID.find(html)?.groupValues?.get(1)

    /**
     * 单文件页 fid（var fid = 96810913;）。
     * 旧实现的 data-id="…" 在新版页面已不存在，保留仅作兜底。
     */
    fun extractFileId(html: String): String? =
        RE_FID_VAR.find(html)?.groupValues?.get(1)
            ?: Regex("""data-id="(\d+)"""").find(html)?.groupValues?.get(1)

    /** 提取 iframe 地址（新版单文件页取直链的第一步） */
    fun extractIframe(html: String): String? = RE_IFRAME_SRC.find(html)?.groupValues?.get(1)

    /** 新版签名（iframe 页）：var wp_sign = '…' */
    fun extractWpSign(html: String): String? = RE_WP_SIGN.find(html)?.groupValues?.get(1)

    /** 新版附加校验串（iframe 页）：var ajaxdata = 'asXy' */
    fun extractAjaxData(html: String): String? = RE_AJAXDATA.find(html)?.groupValues?.get(1)

    /** kdns 值（iframe 页）：1 = 正常域，0 = 降级备用域 */
    fun extractKdns(html: String): Int? = RE_KDNS.find(html)?.groupValues?.get(1)?.toIntOrNull()

    /** 是否处于 acw_sc__v2 挑战页（返回 arg1；无挑战返回 null） */
    fun extractAcwArg1(html: String): String? = RE_ACW_ARG1.find(html)?.groupValues?.get(1)

    /**
     * 提取直链 sign（旧版形态，按优先级尝试）。
     * 新版流程请改用 [extractWpSign] —— 新版分享页本身不含 sign。
     */
    fun extractSign(html: String): String? {
        RE_SIGN_EQ.find(html)?.let { return it.groupValues[1] }
        RE_SIGN_COLON.find(html)?.let {
            val v = it.groupValues[1].trim().trim('\'', '"')
            if (v.length >= 20) return v
        }
        RE_SIGN_VAR.find(html)?.let { return it.groupValues[1] }
        return null
    }
}
