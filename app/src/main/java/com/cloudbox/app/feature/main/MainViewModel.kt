package com.cloudbox.app.feature.main

import androidx.lifecycle.ViewModel
import com.cloudbox.app.common.ClipboardLinkWatcher
import com.cloudbox.app.core.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 主界面 ViewModel：仅聚合依赖（剪贴板监听器 + 认证仓库） */
@HiltViewModel
class MainViewModel @Inject constructor(
    val clipboardWatcher: ClipboardLinkWatcher,
    val authRepository: AuthRepository
) : ViewModel()
