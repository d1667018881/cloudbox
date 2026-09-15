package com.cloudbox.app.feature.download

import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.DownloadHelper
import com.cloudbox.app.core.domain.model.DownloadSortMode
import com.cloudbox.app.core.domain.model.DownloadTask
import com.cloudbox.app.core.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 下载记录页状态：直接订阅 Repository 的 Flow */
@HiltViewModel
class DownloadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    private val _records = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 原始记录（服务端/Room 顺序） */
    val records: StateFlow<List<DownloadTask>> = _records.asStateFlow()

    private val _sortMode = MutableStateFlow(DownloadSortMode.TIME_DESC)

    /** 排序方式；持久到内存即可（页面重进回到默认，和原版一致） */
    val sortMode: StateFlow<DownloadSortMode> = _sortMode.asStateFlow()

    /**
     * 展示用列表 = 原始队列按当前排序方式排列。
     *
     * 排序放在这里而不是 Repository：排序是纯展示层关注点，
     * 与文件列表（[com.cloudbox.app.feature.filelist.FileListViewModel]）保持同一套做法。
     */
    private val _displayRecords = MutableStateFlow<List<DownloadTask>>(emptyList())
    val displayRecords: StateFlow<List<DownloadTask>> = _displayRecords.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.observeRecords().collect { list ->
                _records.value = list
                _displayRecords.value = _sortMode.value.apply(list)
            }
        }
    }

    fun setSortMode(mode: DownloadSortMode) {
        _sortMode.value = mode
        _displayRecords.value = mode.apply(_records.value)
    }

    fun cancel(downloadId: Long) {
        viewModelScope.launch { downloadRepository.cancel(downloadId) }
    }

    fun pause(downloadId: Long) {
        viewModelScope.launch { downloadRepository.pause(downloadId) }
    }

    fun resume(downloadId: Long) {
        viewModelScope.launch { downloadRepository.resume(downloadId) }
    }

    /** 清空全部下载记录（不删除已下载到本地的文件，语义见 Repository 注释） */
    fun clearAll() {
        viewModelScope.launch { downloadRepository.clearAll() }
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() { _message.value = null }

    /** 重命名本地下载（只改本地副本名，不动云端） */
    fun renameLocal(downloadId: Long, newName: String) {
        viewModelScope.launch {
            downloadRepository.renameLocal(downloadId, newName)
                .onSuccess { _message.value = "已重命名" }
                .onFailure { e -> _message.value = "重命名失败：${e.message}" }
        }
    }

    /** 复制下载直链（直链有效期有限，复制后要尽快用） */
    fun copyUrl(task: DownloadTask, context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("直链", task.url))
        _message.value = "已复制直链（有效期有限，请尽快使用）"
    }

    fun openTask(task: DownloadTask) {
        val uri = DownloadHelper.getCompletedFileUri(context, task.downloadId)
        DownloadHelper.openFile(context, uri, task.mimeType)
    }

    /** 状态文本 */
    fun statusText(status: Int): String = when (status) {
        DownloadManager.STATUS_PENDING -> "等待中"
        DownloadManager.STATUS_RUNNING -> "下载中"
        DownloadManager.STATUS_PAUSED -> "已暂停"
        DownloadManager.STATUS_SUCCESSFUL -> "已完成"
        DownloadManager.STATUS_FAILED -> "失败"
        else -> "未知"
    }
}
