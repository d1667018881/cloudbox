package com.cloudbox.app.feature.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.BuildConfig
import com.cloudbox.app.core.data.update.UpdateStatusStore
import com.cloudbox.app.core.domain.model.AppUpdate
import com.cloudbox.app.core.domain.model.UpdateLogEntry
import com.cloudbox.app.core.domain.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AboutUiState(
    val versionName: String = "",
    val versionCode: Int = 0,
    val checking: Boolean = false,
    val updateResult: String? = null,
    /** 检测到的新版本；null = 无更新或未检查 */
    val update: AppUpdate? = null,
    val downloading: Boolean = false,
    /** 下载进度 0..100 */
    val progress: Int = 0,
    /** 下载好的安装包（就绪后可安装） */
    val downloadedFile: File? = null,
    val message: String? = null
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val updateStatusStore: UpdateStatusStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AboutUiState(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE
        )
    )
    val uiState: StateFlow<AboutUiState> = _uiState.asStateFlow()

    /**
     * 检查更新。
     *
     * 与旧实现的关键差别：
     * ① 用**数字版号**比较（旧实现比 tag 字符串，判不出 0.1.130 与 0.1.131 的新旧）；
     * ② 拿到 APK 直链后可下载安装（旧实现只显示一句文案）；
     * ③ 「检查失败」与「已是最新」严格区分，不再把失败说成最新。
     *
     * 检查结果同步写入 [UpdateStatusStore]，供"关于"入口的红点使用。
     */
    fun checkUpdate() {
        if (_uiState.value.checking) return
        _uiState.update { it.copy(checking = true, updateResult = null, downloadedFile = null) }
        viewModelScope.launch {
            updateRepository.checkUpdate().fold(
                onSuccess = { update ->
                    updateStatusStore.set(update)
                    _uiState.update {
                        it.copy(
                            checking = false,
                            update = update,
                            updateResult = if (update == null) {
                                "已是最新版本（${BuildConfig.VERSION_NAME}）"
                            } else {
                                "发现新版本 ${update.versionName}"
                            }
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            checking = false,
                            updateResult = "检查失败（${e.message ?: "网络不可达"}，不影响正常使用）"
                        )
                    }
                }
            )
        }
    }

    /** 下载更新包（进度写入 state.progress），成功后 downloadedFile 就绪可安装 */
    fun download() {
        val update = _uiState.value.update ?: return
        if (_uiState.value.downloading) return
        _uiState.update { it.copy(downloading = true, progress = 0, message = null) }
        viewModelScope.launch {
            updateRepository.download(update) { pct ->
                _uiState.update { it.copy(progress = pct) }
            }.fold(
                onSuccess = { file ->
                    _uiState.update {
                        it.copy(downloading = false, downloadedFile = file, message = "下载完成，点「安装」继续")
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(downloading = false, message = "下载失败：${e.message}") }
                }
            )
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

        /**
         * 常见问题（对齐原版 about.lua 的「常见问题」入口）。
         *
         * 原版是跳转兔小巢 FAQ 页（已停运），这里改为内置文本：内容围绕
         * 本 App 的真实使用场景（下载/上传/解析/登录），比外链更有用。
         */
        val FAQ: List<Pair<String, String>> = listOf(
            "下载下来的文件只有几 KB，打不开？" to
                "这是下载请求未携带登录 Cookie、被服务端风控返回了挑战页的表现。" +
                "当前版本已把解析阶段的 Cookie 一并交给下载器（详见 DownloadRepositoryImpl 注释），" +
                "若仍出现，请先在「设置 → 网络与解析」确认域名可用，再重试。",
            "分享链接解析失败？" to
                "蓝奏云域名会漂移。请到「设置 → 域名配置」做一次连通性测试，或开启远程域名配置。" +
                "带提取码的链接请连同提取码一起粘贴。",
            "上传的文件名被加了 .zip 后缀？" to
                "这是对不支持格式（如 exe/apk）的伪装，服务端才允许上传。" +
                "在「设置 → 文件与上传」可关闭或调整需要伪装的后缀；下载端会按原后缀还原。",
            "提示「未登录或登录已过期」？" to
                "Cookie 有效期约 20 天。到登录页重新登录即可；本 App 也会在临期时自动尝试静默重登。",
            "一定要开「安装未知应用」权限吗？" to
                "只有应用自更新下载安装新版本时才需要。其余功能不受影响。"
        )

        /**
         * 开源许可（对齐原版 about.lua 的「许可」入口）。
         * 列出本 App 直接依赖的主要开源库及其许可协议。
         */
        val LICENSES: List<Pair<String, String>> = listOf(
            "AndroidX / Jetpack Compose" to "Apache License 2.0",
            "Kotlin & Coroutines" to "Apache License 2.0",
            "Hilt (Dagger)" to "Apache License 2.0",
            "Room" to "Apache License 2.0",
            "Retrofit / OkHttp" to "Apache License 2.0",
            "Gson" to "Apache License 2.0",
            "Jsoup" to "MIT License",
            "CameraX" to "Apache License 2.0",
            "ML Kit (Barcode Scanning)" to "Apache License 2.0",
            "ZXing" to "Apache License 2.0",
            "Zip4j" to "Apache License 2.0",
            "WorkManager" to "Apache License 2.0"
        )
    }
}
