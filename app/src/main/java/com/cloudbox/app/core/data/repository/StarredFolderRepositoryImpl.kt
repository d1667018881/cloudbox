package com.cloudbox.app.core.data.repository

import com.cloudbox.app.core.data.local.db.AppDatabase
import com.cloudbox.app.core.data.local.db.StarredFolderEntity
import com.cloudbox.app.core.domain.model.StarredFolder
import com.cloudbox.app.core.domain.repository.StarredFolderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** 星标文件夹仓库实现（Room 持久化，按账号分桶） */
@Singleton
class StarredFolderRepositoryImpl @Inject constructor(
    private val db: AppDatabase
) : StarredFolderRepository {

    private fun dao() = db.starredFolderDao()

    override fun observe(uid: String): Flow<List<StarredFolder>> =
        dao().observeAll(uid).map { list -> list.map { it.toModel() } }

    override suspend fun isStarred(uid: String, folderId: Long): Boolean =
        withContext(Dispatchers.IO) { dao().get(uid, folderId) != null }

    override suspend fun star(uid: String, folderId: Long, name: String) = withContext(Dispatchers.IO) {
        // 已星标：保留原快照（原版语义，见接口注释），只补一个空名兜底
        val existing = dao().get(uid, folderId)
        if (existing != null) return@withContext
        dao().insert(StarredFolderEntity(accountUid = uid, folderId = folderId, name = name))
    }

    override suspend fun unstar(uid: String, folderId: Long) =
        withContext(Dispatchers.IO) { dao().delete(uid, folderId) }

    override suspend fun rename(uid: String, folderId: Long, name: String) =
        withContext(Dispatchers.IO) { dao().updateName(uid, folderId, name) }

    override suspend fun setRemark(uid: String, folderId: Long, remark: String) =
        withContext(Dispatchers.IO) { dao().updateRemark(uid, folderId, remark) }

    /**
     * 按名称排序并落盘。
     *
     * 逐个 UPDATE 而不是一条 SQL 排完：SQLite 没有稳定的「按另一列顺序写序号」
     * 单语句写法，而星标条目通常只有个位数到几十条，逐条更新完全够用；
     * 放在同一 IO 上下文里串行执行，避免并发交叉导致序号错乱。
     */
    override suspend fun sortByName(uid: String) = withContext(Dispatchers.IO) {
        val sorted = dao().getAllOnce(uid).sortedBy { it.name }
        sorted.forEachIndexed { index, item ->
            dao().updateSortOrder(uid, item.folderId, index)
        }
    }

    override suspend fun clearForAccount(uid: String) =
        withContext(Dispatchers.IO) { dao().clearForAccount(uid) }

    private fun StarredFolderEntity.toModel() = StarredFolder(
        folderId = folderId,
        name = name,
        remark = remark,
        sortOrder = sortOrder,
        createdAt = createdAt
    )
}
