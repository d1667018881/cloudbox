package com.cloudbox.app.feature.fullload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 全盘加载条目：文件 + 其所属文件夹名（对齐原版列表项的「所属文件夹」标签） */
data class FullLoadEntry(val file: CloudFile, val folderName: String)

data class FullLoadUiState(
    val loading: Boolean = false,
    /** 是否已点过「继续」（未点之前显示风险提示） */
    val hasStarted: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val entries: List<FullLoadEntry> = emptyList(),
    val failedFolders: Int = 0,
    val finished: Boolean = false,
    val error: String? = null
)

/**
 * 全盘加载（原版「查看全盘文件」，home_file.lua:859 / home_func.lua:147-240）。
 *
 * 遍历顺序严格对齐原版：
 * 1. task=19 取全部文件夹（**手工在最前面补根目录 -1**，原版 home_func.lua:185-195）；
 * 2. 逐目录 task=5 分页拉文件，目录之间 delay 300ms、页之间 200ms —— **串行 + 节流**。
 *
 * ⚠️ 绝不能并发：原版自己都在提示「短时间内会大量请求服务器，可能导致账号异常」
 * （home_file.lua:882-885）。这里沿用 [com.cloudbox.app.core.data.repository.SearchRepositoryImpl.syncAll]
 * 已验证的节流参数。
 *
 * 失败策略：**单个目录失败不中断**（续跑），最后汇总「N 个文件夹加载失败」；
 * 只有 task=19 整体失败才报 error。
 */
@HiltViewModel
class FullLoadViewModel @Inject constructor(
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FullLoadUiState())
    val uiState: StateFlow<FullLoadUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (_uiState.value.loading) return
        job = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true, hasStarted = true, done = 0, total = 0,
                    entries = emptyList(), failedFolders = 0, finished = false, error = null
                )
            }

            val folders = fileRepository.getAllFolders().getOrElse { e ->
                _uiState.update { it.copy(loading = false, error = "获取文件夹失败：${e.message}") }
                return@launch
            }
            val targets = mutableListOf(-1L to "根目录")
            targets.addAll(folders)
            _uiState.update { it.copy(total = targets.size) }

            val acc = mutableListOf<FullLoadEntry>()
            var failed = 0
            targets.forEachIndexed { index, (fid, fname) ->
                if (index > 0) delay(FOLDER_INTERVAL_MS)
                _uiState.update { it.copy(current = fname) }

                val folderFiles = mutableListOf<CloudFile>()
                var page = 1
                var localFail = false
                while (true) {
                    val listPage = fileRepository.getPage(fid, page).getOrElse {
                        localFail = true
                        null
                    } ?: break
                    if (listPage.files.isEmpty() && page == 1) break
                    folderFiles.addAll(listPage.files)
                    if (!listPage.hasMore) break
                    page++
                    delay(PAGE_INTERVAL_MS)
                }
                acc.addAll(folderFiles.map { FullLoadEntry(it, fname) })
                if (localFail) failed++
                // 每完成一个文件夹更新一次（避免每页整表拷贝，大列表下 O(n²)）
                _uiState.update {
                    it.copy(entries = acc.toList(), done = index + 1, failedFolders = failed)
                }
            }
            _uiState.update { it.copy(loading = false, finished = true, failedFolders = failed) }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _uiState.update { it.copy(loading = false) }
    }

    companion object {
        /** 目录间隔（对齐 SearchRepositoryImpl 的 300ms 防风控策略） */
        private const val FOLDER_INTERVAL_MS = 300L
        /** 同目录翻页间隔 */
        private const val PAGE_INTERVAL_MS = 200L
    }
}
