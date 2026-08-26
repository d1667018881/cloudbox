package com.cloudbox.app.core.domain.repository

import com.cloudbox.app.core.domain.model.CloudFile
import kotlinx.coroutines.flow.Flow

/** 搜索仓库：Room 索引 + 后台同步 */
interface SearchRepository {

    /** 全盘同步：遍历所有文件夹把文件写入索引（后台自动同步） */
    suspend fun syncAll(progress: (Int, Int) -> Unit): Result<Int>

    /** 搜索（FTS 前缀 + LIKE 中文兜底，合并去重） */
    suspend fun search(keyword: String): List<CloudFile>

    /** 索引是否已同步过 */
    suspend fun isSynced(): Boolean
}
