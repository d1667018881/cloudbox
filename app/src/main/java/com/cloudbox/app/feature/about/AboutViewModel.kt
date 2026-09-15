package com.cloudbox.app.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.BuildConfig
import com.cloudbox.app.core.domain.model.UpdateLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

data class AboutUiState(
    val versionName: String = "",
    val versionCode: Int = 0,
    val checking: Boolean = false,
    val updateResult: String? = null,
    val message: String? = null
)

@HiltViewModel
class AboutViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    /**
     * 检查更新：拉 GitHub Releases 最新 tag 与本地版本比对。
     *
     * ⚠️ 为什么"检查失败"不报错只说一句：本 App 的核心功能（网盘/上传/解析）
     * 完全不依赖这个网络请求。它失败（DNS 被劫持、GitHub 不可达、
     * 仓库还没发过 Release）都是常态，弹错误只会让用户误以为 App 坏了。
     * 所以一律降级成中性文案。
     */
    fun checkUpdate() {
        if (_uiState.value.checking) return
        _uiState.update { it.copy(checking = true, updateResult = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { fetchLatestTag() }
            _uiState.update { it.copy(checking = false, updateResult = result) }
        }
    }

    private fun fetchLatestTag(): String {
        val url = URL("https://api.github.com/repos/d1667018881/cloudbox/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return runCatching {
            conn.use { c ->
                if (c.responseCode != 200) return@use "检查失败（HTTP ${c.responseCode}）"
                val body = c.inputStream.bufferedReader().readText()
                // 直接用 org.json 解析（Android 平台自带），避免为一个字段引入 Gson
                val tag = org.json.JSONObject(body).optString("tag_name", "")
                when {
                    tag.isBlank() -> "检查失败（没有可用的版本信息）"
                    tag.trimStart('v') == BuildConfig.VERSION_NAME -> "已是最新版本（$tag）"
                    else -> "有新版本：$tag（当前 ${BuildConfig.VERSION_NAME}）"
                }
            }
        }.getOrElse {
            "检查失败（网络不可达，不影响正常使用）"
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    companion object {
        /**
         * 更新日志。
         *
         * 原版的 `update_log.lua` 内容本身是加密的（解出来只有 "1.3.4.8" 一个版本号
         * 加一堆乱码），没有可复刻的文本。这里写的是**本项目自己的**改动记录 ——
         * 对自维护来说这才是有用的信息。
         */
        val UPDATE_LOG: List<UpdateLogEntry> = listOf(
            UpdateLogEntry(
                "v0.1.130",
                listOf(
                    "补齐原版缺失功能：下载列表排序与清空、文件点击操作菜单",
                    "收藏夹支持置顶、编辑名称与备注、复制链接",
                    "删除操作增加二次确认",
                    "新增全局错误页（崩溃时展示堆栈，可复制反馈）",
                    "新增二维码扫描（CameraX + MLKit）与二维码保存到相册",
                    "回收站支持查看文件夹内部内容",
                    "修复：粘贴整段分享文案（含中文/空格）无法解析"
                )
            ),
            UpdateLogEntry(
                "v0.1.128",
                listOf(
                    "修复批量分享只处理第一条的假功能",
                    "文件列表支持排序（中文按拼音）",
                    "多选工具栏补齐全选/批量下载/批量提取码/批量改资料"
                )
            ),
            UpdateLogEntry(
                "v0.1.124",
                listOf(
                    "支持从其他 App「分享」文件到云匣直接上传",
                    "上传入口收敛到网盘页，移除独立上传页",
                    "修复 Worker 瞬间失败（缺 hilt-compiler 注解处理器）"
                )
            )
        )
    }
}
