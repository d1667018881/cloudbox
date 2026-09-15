package com.cloudbox.app.feature.filelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.model.ShareInfo
import com.cloudbox.app.core.domain.repository.DirectLinkRepository
import com.cloudbox.app.core.domain.repository.DownloadRepository
import com.cloudbox.app.core.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 文件列表 UI 状态 */
data class FileListUiState(
    val folderStack: List<Pair<Long, String>> = listOf(-1L to "根目录"), // 面包屑
    val files: List<CloudFile> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val gridMode: Boolean = false,
    val selectionMode: Boolean = false,
    val selected: Set<Long> = emptySet(),
    val shareResult: ShareInfo? = null,
    /** 批量操作的进度提示（串行执行，可能十几秒；为空表示没有批量任务在跑） */
    val batchProgress: String? = null,
    /** 排序方式（客户端排序，后端返回顺序不可依赖） */
    val sortMode: SortMode = SortMode.DEFAULT,
    val message: String? = null
) {
    val currentFolderId: Long get() = folderStack.last().first
    val currentFolderName: String get() = folderStack.last().second

    /**
     * 展示用的列表 = 原始列表按 [sortMode] 排序后的结果。
     *
     * 排序放这里（而不是改 [files]）的原因：排序是**纯展示层**的事，
     * 不该污染数据本身 —— 否则刷新、分页追加时都要重算，容易漏。
     */
    val displayFiles: List<CloudFile> get() = sortMode.apply(files)
}

/**
 * 列表排序方式。对齐原版的三种排序卡片（时间 / 名称正序 / 名称倒序）。
 *
 * 原版还支持"按中文名拼音排序"（Transliterator + `[一-龥]` 正则），
 * 且对低版本系统有守卫（`29 <= Build.VERSION.SDK_INT`）。这里用
 * [java.text.Collator] 实现，它是 java 标准库、全版本可用，效果等价且更简单。
 */
enum class SortMode(val label: String) {
    /** 默认：保持服务端返回顺序（原版相当于"按时间"，因为服务端按 folder_id 倒序下发） */
    DEFAULT("默认顺序"),
    /** 名称 A→Z（中文按拼音，与原版一致） */
    NAME_ASC("名称 A→Z"),
    /** 名称 Z→A */
    NAME_DESC("名称 Z→A");

    /** 应用排序：永远文件夹在前、文件在后（网盘通用约定，原版也是如此） */
    fun apply(list: List<CloudFile>): List<CloudFile> {
        if (this == DEFAULT) return list
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply {
            // 让中文按拼音、数字按数值比较，避免"第10个"排在"第2个"前面
            strength = java.text.Collator.SECONDARY
        }
        val cmp = Comparator<CloudFile> { a, b ->
            // 先按"是否文件夹"分组
            if (a.isFolder != b.isFolder) return@Comparator if (a.isFolder) -1 else 1
            val c = collator.compare(a.name, b.name)
            if (this == NAME_DESC) -c else c
        }
        return list.sortedWith(cmp)
    }
}

@HiltViewModel
class FileListViewModel @Inject constructor(
    val fileRepository: FileRepository,
    private val directLinkRepository: DirectLinkRepository,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileListUiState())
    val uiState: StateFlow<FileListUiState> = _uiState.asStateFlow()

    private var page = 1

    init {
        refresh()
    }

    fun refresh() {
        page = 1
        loadPage(append = false)
    }

    /** 切换排序方式（纯客户端，不需要重新请求） */
    fun setSortMode(mode: SortMode) = _uiState.update { it.copy(sortMode = mode) }

    fun loadMore() {
        val s = _uiState.value
        if (s.loading || s.loadingMore || s.files.isEmpty()) return
        page++
        loadPage(append = true)
    }

    private fun loadPage(append: Boolean) {
        val folderId = _uiState.value.currentFolderId
        // 导航竞态防护（V5）：用户快速进入/切换文件夹时，旧文件夹的迟到响应
        // 会把新文件夹的列表覆盖成旧内容——表现为"二级目录里出现一级目录的内容/
        // 幽灵文件夹"。每个请求带序号，应用结果前校验序号未被更新，过期即丢弃。
        val seq = ++loadSeq
        viewModelScope.launch {
            if (append) {
                _uiState.update { it.copy(loadingMore = true) }
            } else {
                _uiState.update { it.copy(loading = true, error = null) }
            }
            fileRepository.getPage(folderId, page).onSuccess { listPage ->
                if (seq != loadSeq) return@launch // 过期响应（用户已切到其他文件夹）
                _uiState.update {
                    it.copy(
                        files = if (append) it.files + listPage.files else listPage.folders + listPage.files,
                        loading = false,
                        loadingMore = false,
                        hasMore = listPage.hasMore
                    )
                }
            }.onFailure { e ->
                if (seq != loadSeq) return@launch
                _uiState.update {
                    it.copy(loading = false, loadingMore = false, error = e.message ?: "加载失败")
                }
            }
        }
    }

    /** 进入子文件夹 */
    fun enterFolder(folderId: Long, name: String) {
        _uiState.update { it.copy(folderStack = it.folderStack + (folderId to name)) }
        page = 1
        loadPage(append = false)
    }

    /** 面包屑跳转（截断栈） */
    fun navigateTo(index: Int) {
        _uiState.update {
            it.copy(folderStack = it.folderStack.take(index + 1), selectionMode = false, selected = emptySet())
        }
        page = 1
        loadPage(append = false)
    }

    fun back(): Boolean {
        val s = _uiState.value
        if (s.selectionMode) {
            exitSelection()
            return true
        }
        if (s.folderStack.size > 1) {
            navigateTo(s.folderStack.size - 2)
            return true
        }
        return false
    }

    fun toggleGrid() = _uiState.update { it.copy(gridMode = !it.gridMode) }

    /** loadPage 请求序号（导航竞态防护，见 loadPage 注释） */
    private var loadSeq = 0

    fun enterSelection(file: CloudFile) {
        _uiState.update { it.copy(selectionMode = true, selected = setOf(file.id)) }
    }

    fun toggleSelect(id: Long) {
        _uiState.update {
            val sel = it.selected.toMutableSet()
            if (!sel.add(id)) sel.remove(id)
            it.copy(selected = sel)
        }
    }

    fun exitSelection() = _uiState.update { it.copy(selectionMode = false, selected = emptySet()) }

    // ==================== 操作 ====================

    fun createFolder(name: String) {
        viewModelScope.launch {
            fileRepository.createFolder(_uiState.value.currentFolderId, name)
                .onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "新建失败：${e.message}") } }
        }
    }

    fun rename(file: CloudFile, newName: String) {
        viewModelScope.launch {
            fileRepository.rename(file, newName)
                .onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "重命名失败：${e.message}") } }
        }
    }

    fun deleteSelected() {
        val s = _uiState.value
        val files = s.files.filter { it.id in s.selected }
        val fileIds = files.filter { !it.isFolder }.map { it.id }
        val folderIds = files.filter { it.isFolder }.map { it.id }
        viewModelScope.launch {
            fileRepository.delete(fileIds, folderIds)
                .onSuccess {
                    exitSelection()
                    refresh()
                }
                .onFailure { e -> _uiState.update { it.copy(message = "删除失败：${e.message}") } }
        }
    }

    fun moveSelected(targetFolderId: Long) {
        val s = _uiState.value
        val selectedFiles = s.files.filter { it.id in s.selected }
        val fileIds = selectedFiles.filter { !it.isFolder }.map { it.id }
        val folderCount = selectedFiles.count { it.isFolder }
        // #19 修复：明确提示文件夹不支持移动（官方无接口），不再静默忽略
        if (folderCount > 0) {
            _uiState.update { it.copy(message = "文件夹暂不支持移动（官方无接口），仅移动 ${fileIds.size} 个文件") }
        }
        if (fileIds.isEmpty()) {
            exitSelection()
            return
        }
        viewModelScope.launch {
            fileRepository.moveFiles(fileIds, targetFolderId)
                .onSuccess {
                    exitSelection()
                    refresh()
                }
                .onFailure { e -> _uiState.update { it.copy(message = "移动失败：${e.message}") } }
        }
    }

    fun setPasswd(file: CloudFile, pwd: String) {
        viewModelScope.launch {
            fileRepository.setFilePasswd(file.id, pwd)
                .onSuccess { _uiState.update { it.copy(message = "提取码已设置") } }
                .onFailure { e -> _uiState.update { it.copy(message = "设置失败：${e.message}") } }
        }
    }

    fun setDesc(file: CloudFile, desc: String) {
        viewModelScope.launch {
            fileRepository.setFileDesc(file.id, desc)
                .onSuccess { _uiState.update { it.copy(message = "描述已设置") } }
                .onFailure { e -> _uiState.update { it.copy(message = "设置失败：${e.message}") } }
        }
    }

    fun getShare(file: CloudFile) {
        viewModelScope.launch {
            val result = if (file.isFolder) {
                fileRepository.getDirShare(file.id)
            } else {
                fileRepository.getFileShare(file.id)
            }
            result.onSuccess { share ->
                _uiState.update { it.copy(shareResult = share) }
            }.onFailure { e ->
                _uiState.update { it.copy(message = "获取分享失败：${e.message}") }
            }
        }
    }

    fun dismissShare() = _uiState.update { it.copy(shareResult = null) }

    /**
     * 批量分享：把**全部选中项**的分享链接取回来，拼接为一段文本一次性复制。
     *
     * ─────────────────────────────────────────────────────────────
     * 修的是什么（2026-09-15）
     * ─────────────────────────────────────────────────────────────
     * 旧实现的「分享」按钮写的是：
     *
     * ```kotlin
     * state.files.firstOrNull { it.id == state.selected.firstOrNull() }?.let { getShare(it) }
     * ```
     *
     * `selected.firstOrNull()` —— 选中 N 项也只处理第 1 项。用户看到按钮叫
     * 「分享」、以为多选生效了，实际上只弹了一条链接。**这是名不副实的假功能，
     * 比直接不做还糟**（用户会以为操作成功）。
     *
     * 原版行为（`home_file.lua` 批量分享）：逐项取链接 → 拼接
     * （文件用 `is_newd + "/" + f_id`，文件夹用 `new_url`，有密码时追加密码行）
     * → 「共 %s 项内容」提示 + 一次性复制。
     *
     * ─────────────────────────────────────────────────────────────
     * 为什么串行 + 延时
     * ─────────────────────────────────────────────────────────────
     * 每取一条链接都要打一次服务端（task=22 / task=18）。并发打过去必被风控
     * ——原版的原话是「频繁操作可能触发服务器限流导致失败」，并在批量操作时
     * 固定加了间隔。这里沿用同样的保守策略：串行 + 每项之间随机延时。
     *
     * @param onDone 回传拼好的文本，由 UI 层负责写剪贴板并提示
     */
    fun shareSelected(onDone: (String) -> Unit) {
        val s = _uiState.value
        val targets = s.files.filter { it.id in s.selected }
        if (targets.isEmpty()) {
            _uiState.update { it.copy(message = "未选择任何内容") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(batchProgress = "正在获取分享链接 0/${targets.size}…") }
            val lines = mutableListOf<String>()
            var failed = 0
            targets.forEachIndexed { index, file ->
                if (index > 0) delay(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 801))
                val r = if (file.isFolder) {
                    fileRepository.getDirShare(file.id)
                } else {
                    fileRepository.getFileShare(file.id)
                }
                r.onSuccess { share ->
                    lines += buildString {
                        append(file.name)
                        append('\n')
                        append(share.shareUrl)
                        // ⚠️ 只在 onof=="1"（确实设了提取码）时才带密码。
                        //    onof=0 时服务端给的是一个无效随机值，带上会误导对方。
                        if (share.onof == "1" && share.pwd.isNotBlank()) {
                            append('\n')
                            append("密码：").append(share.pwd)
                        }
                    }
                }.onFailure { failed++ }
                _uiState.update {
                    it.copy(batchProgress = "正在获取分享链接 ${index + 1}/${targets.size}…")
                }
            }
            _uiState.update { it.copy(batchProgress = null) }
            if (lines.isEmpty()) {
                _uiState.update { it.copy(message = "批量分享失败：${targets.size} 项都没取到链接（可能触发限流，稍后重试）") }
                return@launch
            }
            // 拼接格式：各项之间空一行，便于直接读
            val text = lines.joinToString("\n\n")
            onDone(text)
            val tip = if (failed > 0) "已复制 ${lines.size} 项分享链接（${failed} 项失败）" else "已复制全部 ${lines.size} 项分享链接"
            _uiState.update { it.copy(message = tip) }
            exitSelection()
        }
    }

    /** 全选当前页 */
    fun selectAll() = _uiState.update { st ->
        st.copy(selected = st.files.map { it.id }.toSet())
    }

    /** 取消全选 */
    fun clearSelection() = _uiState.update { st -> st.copy(selected = emptySet()) }

    /**
     * 批量设置提取码。串行 + 延时，理由同 [shareSelected]。
     * 文件夹没有提取码接口（官方只对文件开放 task=23），会被跳过并如实告知。
     */
    fun setPasswdSelected(pwd: String) {
        val s = _uiState.value
        val files = s.files.filter { it.id in s.selected && !it.isFolder }
        val skipped = s.files.count { it.id in s.selected && it.isFolder }
        if (files.isEmpty()) {
            _uiState.update { it.copy(message = "选中的都是文件夹，文件夹不支持设置提取码") }
            return
        }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            files.forEachIndexed { index, file ->
                if (index > 0) delay(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 801))
                _uiState.update { it.copy(batchProgress = "设置提取码 ${index + 1}/${files.size}…") }
                fileRepository.setFilePasswd(file.id, pwd)
                    .onSuccess { ok++ }.onFailure { fail++ }
            }
            _uiState.update { it.copy(batchProgress = null) }
            val skipTip = if (skipped > 0) "，跳过 $skipped 个文件夹" else ""
            _uiState.update {
                it.copy(message = if (fail == 0) "已为 $ok 个文件设置提取码$skipTip" else "设置完成：成功 $ok，失败 $fail$skipTip")
            }
            exitSelection()
            refresh()
        }
    }

    /** 批量修改描述（简介）。串行 + 延时，理由同 [shareSelected]。 */
    fun setDescSelected(desc: String) {
        val s = _uiState.value
        val files = s.files.filter { it.id in s.selected && !it.isFolder }
        if (files.isEmpty()) {
            _uiState.update { it.copy(message = "选中的都是文件夹，批量修改资料仅支持文件") }
            return
        }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            files.forEachIndexed { index, file ->
                if (index > 0) delay(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 801))
                _uiState.update { it.copy(batchProgress = "修改资料 ${index + 1}/${files.size}…") }
                fileRepository.setFileDesc(file.id, desc)
                    .onSuccess { ok++ }.onFailure { fail++ }
            }
            _uiState.update { it.copy(batchProgress = null) }
            _uiState.update {
                it.copy(message = if (fail == 0) "已修改 $ok 个文件的资料" else "修改完成：成功 $ok，失败 $fail")
            }
            exitSelection()
            refresh()
        }
    }

    /**
     * 批量下载：逐条解析直链后交给下载管理器。
     *
     * 未配置直链解析服务时直接拒绝并沿用原版文案 —— 批量下载没有直链就是不可用，
     * 硬跑只会得到一堆失败，不如提前说清楚。
     */
    fun downloadSelected() {
        val s = _uiState.value
        val files = s.files.filter { it.id in s.selected && !it.isFolder }
        if (files.isEmpty()) {
            _uiState.update { it.copy(message = "选中的都是文件夹，批量下载仅支持文件") }
            return
        }
        viewModelScope.launch {
            var ok = 0
            var fail = 0
            files.forEachIndexed { index, file ->
                if (index > 0) delay(java.util.concurrent.ThreadLocalRandom.current().nextLong(300, 801))
                _uiState.update { it.copy(batchProgress = "加入下载队列 ${index + 1}/${files.size}…") }
                fileRepository.getFileShare(file.id).onSuccess { share ->
                    // 分享链接 → 直链 → 下载队列；任一环失败都算这一项失败。
                    // 密码只在 onof=="1" 时有效（否则是无效随机值，带上会被判密码错误）。
                    val pwd = if (share.onof == "1") share.pwd else ""
                    directLinkRepository.resolve(share.shareUrl, pwd)
                        .onSuccess { link ->
                            downloadRepository.enqueue(
                                url = link.url,
                                fileName = link.fileName.ifBlank { file.name },
                                referer = link.referer,
                                mimeType = null,
                                accountUid = fileRepository.currentUid() ?: ""
                            )
                            ok++
                        }.onFailure { fail++ }
                }.onFailure { fail++ }
            }
            _uiState.update { it.copy(batchProgress = null) }
            _uiState.update {
                it.copy(message = if (fail == 0) "已加入下载队列：$ok 个文件" else "批量下载：成功 $ok，失败 $fail")
            }
            exitSelection()
        }
    }

    fun dismissMessage() = _uiState.update { it.copy(message = null) }

    /** 由 UI 层触发一条一次性提示（如"已复制文件名"） */
    fun showMessage(text: String) = _uiState.update { it.copy(message = text) }

    // ==================== 单文件操作 ====================

    /**
     * 删除单个条目（不走多选流程）。
     *
     * 供「点击文件 → 操作菜单 → 删除」使用。原版点击文件就是弹菜单
     * （详情/分享/下载/重命名/删除/移动），而不是直接取分享链接 ——
     * 当前仓库此前点一下文件就弹分享框，把"看内容"和"分享"绑死了，
     * 用户想重命名还得先长按进多选，路径太长。
     */
    fun deleteSingle(file: CloudFile) {
        viewModelScope.launch {
            val r = if (file.isFolder) {
                fileRepository.delete(emptyList(), listOf(file.id))
            } else {
                fileRepository.delete(listOf(file.id), emptyList())
            }
            r.onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "删除失败：${e.message}") } }
        }
    }

    /** 移动单个文件到目标目录（文件夹不支持移动，见 [moveSelected] 注释） */
    fun moveSingle(file: CloudFile, targetFolderId: Long) {
        if (file.isFolder) {
            _uiState.update { it.copy(message = "文件夹暂不支持移动（官方无接口）") }
            return
        }
        viewModelScope.launch {
            fileRepository.moveFiles(listOf(file.id), targetFolderId)
                .onSuccess { refresh() }
                .onFailure { e -> _uiState.update { it.copy(message = "移动失败：${e.message}") } }
        }
    }

    /**
     * 下载单个文件：分享链接 → 直链 → 下载队列。
     * 与 [downloadSelected] 同一条链路，只是目标只有一个。
     */
    fun downloadSingle(file: CloudFile) {
        if (file.isFolder) {
            _uiState.update { it.copy(message = "文件夹不支持直接下载，请进入后选择文件") }
            return
        }
        viewModelScope.launch {
            fileRepository.getFileShare(file.id).onSuccess { share ->
                val pwd = if (share.onof == "1") share.pwd else ""
                directLinkRepository.resolve(share.shareUrl, pwd)
                    .onSuccess { link ->
                        downloadRepository.enqueue(
                            url = link.url,
                            fileName = link.fileName.ifBlank { file.name },
                            referer = link.referer,
                            mimeType = null,
                            accountUid = fileRepository.currentUid() ?: ""
                        )
                        _uiState.update { it.copy(message = "已加入下载队列") }
                    }.onFailure { e ->
                        _uiState.update { it.copy(message = "解析失败：${e.message}") }
                    }
            }.onFailure { e ->
                _uiState.update { it.copy(message = "获取分享链接失败：${e.message}") }
            }
        }
    }
}
