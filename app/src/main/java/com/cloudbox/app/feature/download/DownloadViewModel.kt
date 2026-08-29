package com.cloudbox.app.feature.download

import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudbox.app.common.DownloadHelper
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
    val records: StateFlow<List<DownloadTask>> = _records.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.observeRecords().collect { list ->
                _records.value = list
            }
        }
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
