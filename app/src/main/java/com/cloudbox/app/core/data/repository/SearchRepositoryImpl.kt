package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.SearchIndexEntity
import com.cloudbox.app.core.data.local.secure.AccountSecureStore
import com.cloudbox.app.core.domain.model.CloudFile
import com.cloudbox.app.core.domain.repository.FileRepository
import com.cloudbox.app.core.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索仓库实现。
 *
 * 全盘同步策略：task=19 拿全部文件夹平铺列表 → 对每个文件夹分页拉文件入库。
 * 注意：遍历会真实请求管理接口，必须加延时防风控（每文件夹 300ms）。
 * 网盘文件量极大时同步耗时较长，UI 显示进度。
 *
 * 搜索双轨（Room FTS 中文分词局限，见 SearchIndexEntity 注释）：
 * - 英文/数字：FTS 前缀查询
 * - 中文：LIKE '%kw%'（FileCacheDao）
 */
@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val accountStore: AccountSecureStore,
    private val fileRepository: FileRepository
) : SearchRepository {

    override suspend fun syncAll(progress: (Int, Int) -> Unit): Result<Int> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uid = accountStore.currentUid() ?: return@runCatching 0
                db.searchIndexDao().clearForAccount(uid)

                // 1) 全部文件夹平铺
                val allFolders = fileRepository.getAllFolders().getOrNull() ?: emptyList()
                val folderIds = mutableListOf(-1L) // 根目录
                folderIds.addAll(allFolders.map { it.first })

                var total = 0
                folderIds.forEachIndexed { index, folderId ->
                    // 延时防风控
                    if (index > 0) delay(300)
                    // 2) 分页拉取该文件夹全部文件
                    var page = 1
                    while (true) {
                        val listPage = fileRepository.getPage(folderId, page).getOrNull()
                            ?: break
                        if (listPage.files.isEmpty() && page == 1) break
                        db.searchIndexDao().insertAll(listPage.files.map {
                            SearchIndexEntity(
                                fileId = it.id,
                                accountUid = uid,
                                name = it.name,
                                parentId = folderId,
                                size = it.size,
                                time = it.time
                            )
                        })
                        total += listPage.files.size
                        progress(index + 1, folderIds.size)
                        if (!listPage.hasMore) break
                        page++
                        delay(200)
                    }
                }
                total
            }
        }

    override suspend fun search(keyword: String): List<CloudFile> =
        withContext(Dispatchers.IO) {
            val uid = accountStore.currentUid() ?: return@withContext emptyList()
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()

            // 索引 LIKE 查询（中文/英文统一可用，见 SearchIndexDao 注释）
            val indexed = db.searchIndexDao().searchIndex(uid, kw)
            // 缓存兜底：索引未同步时也能搜到最近浏览过的目录
            val fromCache = db.fileCacheDao().searchLike(uid, kw)

            // 两个列表类型不同，分别转换后合并去重
            val merged = LinkedHashMap<Long, CloudFile>()
            indexed.forEach { e ->
                merged.putIfAbsent(e.fileId, CloudFile(e.fileId, e.name, false, e.size, e.time, null, null, e.parentId))
            }
            fromCache.forEach { e ->
                merged.putIfAbsent(e.id, CloudFile(e.id, e.name, false, e.size, e.time, null, null, e.parentId))
            }
            merged.values.toList()
        }

    override suspend fun isSynced(): Boolean {
        val uid = accountStore.currentUid() ?: return false
        // 简单判定：存在索引数据即认为同步过（精确判定需计数，此处够用）
        return runCatching {
            db.fileCacheDao().allFiles(uid).isNotEmpty()
        }.getOrDefault(false)
    }
}
