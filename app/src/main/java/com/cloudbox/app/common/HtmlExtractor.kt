package com.cloudbox.app.common

/**
 * 页面 HTML 正则提取工具。
 *
 * 为什么单独封装：蓝奏云多个接口的关键参数（t/k/sign/formhash/uid）散落在
 * HTML/JS 里且格式随版本变化，集中管理便于接口变更时一处修改。
 * 提取失败一律返回 null，由调用方决定降级策略，绝不抛异常。
 */
object HtmlExtractor {

    /** 分享页动态 key k：var xxxxxx = '([0-9a-z]{15,})'（来源：LanZouCloud-API core.py） */
    private val RE_K = Regex("""var [0-9a-z]{6} = '([0-9a-z]{15,})';""")

    /** 分享页时间戳 t：var xxxxxx = '(\d{10})' */
    private val RE_T = Regex("""var [0-9a-z]{6} = '(\d{10})';""")

    /** 分享页文件夹 fid："fid':'?(\d+)'?," */
    private val RE_FID = Regex("""'fid':'?(\d+)'?,""")

    /** 分享页 lx："lx':'?(\d)'?," */
    private val RE_LX = Regex("""'lx':'?(\d)'?,""")

    /** 直链 sign：'sign':(.+?),（无提取码分支） */
    private val RE_SIGN_COLON = Regex("""'sign':(.+?),""")

    /** 直链 sign：sign=(\w+?)&（有提取码分支） */
    private val RE_SIGN_EQ = Regex("""sign=(\w+?)&""")

    /** 直链 sign：var xxxx = 'sign值';（兜底） */
    private val RE_SIGN_VAR = Regex("""var [0-9a-z]{6}\s*=\s*'(.+?)';""")

    /** 回收站 formhash（mydisk.php 页面） */
    private val RE_FORMHASH = Regex("""name="formhash" value="(.+?)"""")

    /** 用户 uid：页面 JS 中 "uid":'xxx' 或 'uid':'xxx' */
    private val RE_UID = Regex("""['"]uid['"]\s*:\s*'(\d+)'""")

    /** 分享页文件 id（用于 ajaxm.php 的 file_id）："data-id":"xxx" 或 data_id */
    private val RE_FILE_ID = Regex("""data-id="(\d+)"""")

    fun extractK(html: String): String? = RE_K.find(html)?.groupValues?.get(1)

    fun extractT(html: String): String? = RE_T.find(html)?.groupValues?.get(1)

    fun extractFid(html: String): String? = RE_FID.find(html)?.groupValues?.get(1)

    fun extractLx(html: String): String? = RE_LX.find(html)?.groupValues?.get(1)

    fun extractFormhash(html: String): String? = RE_FORMHASH.find(html)?.groupValues?.get(1)

    fun extractUid(html: String): String? = RE_UID.find(html)?.groupValues?.get(1)

    fun extractFileId(html: String): String? = RE_FILE_ID.find(html)?.groupValues?.get(1)

    /**
     * 提取直链 sign（按优先级尝试三种形态，与 LanZouCloud-API 顺序一致）。
     * 返回的 sign 去掉引号与首尾空白。
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

    /** 提取分享页 iframe 地址（无提取码文件分支的第一步） */
    fun extractIframe(html: String): String? =
        Regex("""<iframe.*?src="(.+?)"""").find(html)?.groupValues?.get(1)
}
