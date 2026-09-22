package com.cloudbox.app.feature.main

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 侧栏抽屉内容（对齐原版 blue 云侧滑栏，home_layout.lua:1290-2400）。
 *
 * 只放**导航入口**，不放原版那些与 CloudBox 无关或已废弃的项
 * （会员个性化、捐赠、分享应用——见第三批调研的「不做清单」）。
 *
 * @param onClose 点击菜单项后先关抽屉，再执行导航（避免抽屉留在栈上）
 */
@Composable
fun MainDrawerContent(
    accountName: String,
    updateAvailable: Boolean,
    announcementUnread: Boolean,
    onClose: () -> Unit,
    onOpenFullLoad: () -> Unit,
    onOpenDownload: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecycle: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenAnnouncement: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    onLogout: () -> Unit
) {
    ModalDrawerSheet {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(accountName, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                "云匣",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider()

        DrawerEntry("查看全盘文件", Icons.Filled.List, onClick = { onClose(); onOpenFullLoad() })
        DrawerEntry("下载管理", Icons.Filled.Download, onClick = { onClose(); onOpenDownload() })
        DrawerEntry("收藏夹", Icons.Filled.Star, onClick = { onClose(); onOpenFavorites() })
        DrawerEntry("回收站", Icons.Filled.Delete, onClick = { onClose(); onOpenRecycle() })
        DrawerEntry(
            "公告",
            Icons.Filled.Email,
            badge = announcementUnread,
            onClick = { onClose(); onOpenAnnouncement() }
        )
        HorizontalDivider()
        DrawerEntry("账号信息", Icons.Filled.Person, onClick = { onClose(); onOpenAccount() })
        DrawerEntry("设置", Icons.Filled.Settings, onClick = { onClose(); onOpenSettings() })
        DrawerEntry(
            "关于",
            Icons.Filled.Info,
            badge = updateAvailable,
            onClick = { onClose(); onOpenAbout() }
        )
        HorizontalDivider()
        DrawerEntry("退出登录", Icons.AutoMirrored.Filled.ExitToApp, onClick = { onClose(); onLogout() })
    }
}

@Composable
private fun DrawerEntry(
    label: String,
    icon: ImageVector,
    badge: Boolean = false,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = false,
        onClick = onClick,
        icon = { androidx.compose.material3.Icon(icon, null) },
        badge = if (badge) ({ Badge() }) else null
    )
}
