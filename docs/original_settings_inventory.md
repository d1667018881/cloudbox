# 原版「蓝云」App 设置项清单：下载设置页 & 消息设置页

> 版本：原版蓝云 AndroLua / Lua 5.3，v1.3.4.10
> 依据：反编译后明文 Lua 源码。**只写能从源码直接读到的东西，读不出来写「未确认」。**
>
> 涉及文件（下列简写）：
> - `ds.lua` = `apk_analysis/src_readable/settings/download_settings.lua`
> - `ds_layout.lua` = `apk_analysis/src_readable/layout/download_settings_layout.lua`
> - `ms.lua` = `apk_analysis/src_readable/settings/message_settings.lua`
> - `ms_layout.lua` = `apk_analysis/src_readable/layout/message_settings_layout.lua`
> - `ty_core.lua` = `apk_analysis/src_readable/ty_core.lua`（「检查设置」函数内 `if 设置.xxx == nil then 设置.xxx = 默认值 end`）
> - `home_func.lua` = `apk_analysis/src/home_func.lua`（"检查设置"外的写入处，用于确认域名/上传弹窗键名）
>
> 页面标题：下载设置页标题为「连接与下载」（分「连接」「下载」两组，`ds_layout.lua:58` / `ds_layout.lua:904`）；消息设置页标题为「通知与提醒」（组名「通知」，`ms_layout.lua:51`）。

---

## 一、下载设置页（连接与下载）

| 页面 | 显示名（中文） | 原版设置键 | 类型 | 默认值 | 作用 | 出处(文件:行) |
|---|---|---|---|---|---|---|
| 下载设置 | 注意（信息提示，非设置项） | 无 | — | — | 提示「以下选项如设置不当会影响相关功能正常使用」 | ds_layout.lua:107-113 |
| 下载设置·连接 | 蓝奏云域名 | `domain_name` | 点击弹窗 | `pc.woozooo.com` | 蓝奏云接口域名（可切 `up.woozooo.com`） | 读取 ds.lua:47；默认 ty_core.lua:890-891；写入 home_func.lua:1305-1310 |
| 下载设置·连接 | 文件上传网址 | `upload_url` | 点击弹窗 | `/html5up.php` | 文件上传接口路径（可切 `/fileup.php`） | 读取 ds.lua:48；默认 ty_core.lua:1046-1047；写入 home_func.lua:1589-1596 |
| 下载设置·连接 | 文件域名替换 | `link_replacement` + `link_replacement_new` | 点击弹窗 | `false` / `lanzoux` | 将文件域名替换为指定域名（不替换=false） | 弹窗 ds.lua:160；写入 ds.lua:331-338；显示 ds.lua:552-555；默认 ty_core.lua:1152-1159 |
| 下载设置·连接 | 自定义 UserAgent | `user_agent` + `auto_user_agent` | 点击弹窗（内含文本输入） | 设备 WebView 默认 UA / `true` | 自定义请求 UserAgent；内容=默认UA时 auto_user_agent=true | 弹窗 ds.lua:1855；写入 ds.lua:1980-1988；默认 ty_core.lua:989-993 |
| 下载设置·连接 | 蓝云直链解析服务 | `use_app_api` | 开关 | `true` | 是否使用内置（蓝云）直链解析服务（开关控件 id=应用直链开关） | 开关 handler ds.lua:54-75；初始 ds.lua:40；默认 ty_core.lua:1128-1129 |
| 下载设置·连接 | 第三方直链解析服务（卡片「直链解析 API」） | 见下方子项（api_*） | 点击弹窗 | 见下方 | 配置第三方直链解析 API；`use_app_api==true` 时该卡片被禁用变灰 | 卡片 ds_layout.lua:608-892；弹窗 ds.lua:557；保存 ds.lua:112-130（保存api数据） |

### 第三方直链解析服务 弹窗内的子项（`ds.lua:112-130` 保存映射）

| 页面 | 显示名/字段 | 原版设置键 | 类型 | 默认值 | 作用 | 出处(文件:行) |
|---|---|---|---|---|---|---|
| 下载设置·连接 | 直链解析开关 | `api_enabled` | 开关 | `false` | 是否启用第三方直链解析 | ds.lua:115；默认 ty_core.lua:1010-1011 |
| 下载设置·连接 | 地址（地址文字） | `api_url` | 文本输入 | 未确认 | API 地址 | ds.lua:116（及 738 回填） |
| 下载设置·连接 | 链接（链接文字） | `api_link` | 文本输入 | 未确认 | 链接参数键名 | ds.lua:117（及 1108 回填） |
| 下载设置·连接 | 密码（密码文字） | `api_pass` | 文本输入 | 未确认 | 密码参数键名 | ds.lua:118（及 1155 回填） |
| 下载设置·连接 | 直接下载开关 | `api_download` | 开关 | 未确认 | 是否直接下载 | ds.lua:119 |
| 下载设置·连接 | post 开关 | `api_enable_post` | 开关 | 未确认 | 是否启用 POST 请求 | ds.lua:120 |
| 下载设置·连接 | header 开关 | `api_enable_header` | 开关 | 未确认 | 是否启用自定义 Header | ds.lua:121 |
| 下载设置·连接 | 状态码（状态码文字） | `api_code` | 文本输入 | 未确认 | 状态码参数键名 | ds.lua:122（及 1367 回填） |
| 下载设置·连接 | 成功码（成功码文字） | `api_ok_code` | 文本输入 | 未确认 | 成功码值 | ds.lua:123（及 1414 回填） |
| 下载设置·连接 | 消息（消息文字） | `api_msg` | 文本输入 | 未确认 | 消息字段键名 | ds.lua:124（及 1461 回填） |
| 下载设置·连接 | 直链（直链文字） | `api_download_url` | 文本输入 | 未确认 | 直链字段键名 | ds.lua:125（及 1508 回填） |
| 下载设置·连接 | 其他（其他文字） | `api_more` | 文本输入 | 未确认 | 其他补充参数 | ds.lua:126（及 1203 回填） |
| 下载设置·连接 | header（header文字） | `api_header` | 文本输入 | 未确认 | 自定义 Header 内容 | ds.lua:127（及 1017 回填） |
| 下载设置·连接 | post（post文字） | `api_post_data` | 文本输入 | 未确认 | POST 数据内容 | ds.lua:128（及 899 回填） |

> 说明：`api_*` 中除 `api_enabled` 外，其余键在 `ty_core.lua` 的「检查设置」里均**未见初始化**，故默认值一律「未确认」（不推测）。`ds.lua:1630` 有一段填表校验逻辑，可作为字段用途的旁证。

### 下载组

| 页面 | 显示名（中文） | 原版设置键 | 类型 | 默认值 | 作用 | 出处(文件:行) |
|---|---|---|---|---|---|---|
| 下载设置·下载 | 系统下载器保存位置 | `download_folder` | 点击弹窗 | `""` | 保存位置：`""`=内部存储/Download；`/LanCloud`=内部存储/Download/LanCloud | 卡片 ds_layout.lua:919-1260；弹窗写入 ds_layout.lua:1132-1148；显示 ds.lua:49-53；默认 ty_core.lua:947-948 |
| 下载设置·下载 | 使用第三方下载器 | `use_third_party_downloader` | 开关 | `false` | 启用后应用内置下载管理失效（开关 id=下载开关） | 开关 handler ds.lua:91-111；默认 ty_core.lua:1022-1023 |
| 下载设置·下载 | 下载文件方式 | `custom_downloader` + `custom_downloader_pack` + `custom_downloader_activity` | 点击弹窗 | `false` / `""` / `""` | 选择下载器：系统下载器 / 调用其他应用 / 指定应用（填包名+活动） | 卡片 ds_layout.lua:1344-1406；弹窗 ds.lua:3193；写入 ds.lua:3613-3615；显示 ds.lua:3760-3765；默认 ty_core.lua:1058-1065 |
| 下载设置·下载 | 禁用应用内置下载管理 | `click_open_download_manager` | 开关 | `false` | 关闭后点击「下载列表」不再打开应用内置下载管理器（开关 id=下载管理开关） | 开关 handler ds.lua:76-90；初始 ds.lua:39；默认 ty_core.lua:1113-1114 |
| 下载设置·下载 | 内置下载管理器 | `click_open_download_manager` | 点击弹窗（启用/停用单选） | `false` | 启用=点击首页「下载列表」按钮打开内置下载管理器 | 卡片 ds_layout.lua:1493-1561；弹窗 ds.lua:2882；写入 ds.lua:3101-3109；显示 ds.lua:3188-3191；默认 ty_core.lua:1113-1114 |
| 下载设置·下载 | 外部下载管理器 | `custom_download_manager` + `custom_download_manager_pack` + `custom_download_manager_activity` | 点击弹窗 | `false` / `""` / `""` | 系统管理器 / 指定应用（填包名+活动）接管下载 | 卡片 ds_layout.lua:1564-1633；弹窗 ds.lua:2000（2420 处重定义）；写入 ds.lua:2786-2794；显示 ds.lua:2877-2880；默认 ty_core.lua:1049-1056 |

> ⚠️ 注意（重要、据实记录）：**「禁用应用内置下载管理」开关与「内置下载管理器」弹窗都操作同一个键 `click_open_download_manager`**（分别见 `ds.lua:76-90` 与 `ds.lua:3101-3109`）。原版把同一设置做了两处入口，此点在移植时需确认是否要合并，**不要凭印象自行拆分键**。

---

## 二、消息设置页（通知与提醒）

| 页面 | 显示名（中文） | 原版设置键 | 类型 | 默认值 | 作用 | 出处(文件:行) |
|---|---|---|---|---|---|---|
| 消息设置 | 通知栏提醒 | `send_message` | 开关 | `true` | 发送任务提醒、消息提醒等（开关 id=推送开关） | 卡片 ms_layout.lua:66-140；开关 handler ms.lua:133-147；默认 ty_core.lua:899-900 |
| 消息设置 | 管理应用通知 | 无（跳转系统设置） | 跳转页面 | — | 跳转到系统应用通知设置页；附带显示当前是否开启（已开启/已关闭） | 卡片 ms_layout.lua:143-237；onClick ms_layout.lua:151-168；状态 ms.lua:29-46 |
| 消息设置 | 通知测试 | 无 | 点击（动作） | — | 发送一条测试通知与进度通知；**仅 debug_mode==true 时显示** | 卡片 ms_layout.lua:240-443；onClick ms_layout.lua:357-366；显示条件 ms.lua:47-50 |
| 消息设置 | 定期检查收藏夹更新 | `auto_check_favorites` | 开关 | `false` | 打开应用后定期检查收藏的文件夹是否有更新并通知（开关 id=检查更新开关） | 卡片 ms_layout.lua:474-579；开关 handler ms.lua:66-117；默认 ty_core.lua:926-927 |
| 消息设置 | 检查更新间隔 | `auto_check_favorites_time` | 点击弹窗 | `7`（天） | 自动检查间隔，选项 1/3/7/14/30 天 | 卡片 ms_layout.lua:581-1211；弹窗选项 ms_layout.lua:668/726/784/842/900；写入 ms_layout.lua:968-990；显示 ms.lua:54；默认 ty_core.lua:923-924 |
| 消息设置 | 复制分享链接后提醒打开 | `get_clipboard` | 开关 | `true` | 复制蓝奏云分享链接后提示查看或下载（开关 id=剪贴板开关） | 卡片 ms_layout.lua:1213-1314；开关 handler ms.lua:118-132；默认 ty_core.lua:908-909 |
| 消息设置 | 使用移动数据传输时提醒 | `mobile_data_warning` | 开关 | `true` | 使用移动数据（蜂窝网络）传输时提醒（开关 id=流量开关） | 卡片 ms_layout.lua:1324-1393；开关 handler ms.lua:148-162；默认 ty_core.lua:1019-1020 |
| 消息设置 | 删除需要二次确认 | `delete_secondary_confirmation` | 开关 | `true` | 删除文件/文件夹时二次确认（开关 id=删除开关） | 卡片 ms_layout.lua:1394-1463；开关 handler ms.lua:178-192；默认 ty_core.lua:902-903 |
| 消息设置 | 传输完成铃声提醒 | `ringtone` | 开关 | `false` | 上传、下载完成后播放系统通知铃声（开关 id=铃声开关） | 卡片 ms_layout.lua:1464-1531；开关 handler ms.lua:163-177；默认 ty_core.lua:968-969 |
| 消息设置 | 重置功能提示 | 无（重置 4 个一次性提示键） | 点击（动作） | — | 把 `home_bookmark_tip`/`load_all_file_tip`/`favorites_tip`/`upload_tip` 置 nil，重新显示一次性提示 | 卡片 ms_layout.lua:1532-1603；onClick ms_layout.lua:1540-1548 |

> 备注（据实记录，非设置项）：消息设置页「检查更新间隔」卡片内有一个「详情」小按钮（`ms_layout.lua:539-548`），它读取的是 `设置.last_auto_check_time` 用于显示「上次检查日期」。但 `ty_core.lua` 里初始化的是 `last_auto_check_favorites_time`（`ty_core.lua:929-930`，默认 `false`），两者键名**不一致**；`last_auto_check_time` 的实际写入在 `home.lua:559-562`、`home_func.lua:5665`。这是一个只读展示值，非用户可设置项，故不计入清单，但移植时需留意键名拼写差异。

---

## 三、分类小结

### A. 真正需要「本地持久化存储」的开关类（布尔值，绑定 `Switch`/`RadioButton`，改动即 `保存设置()`）

下载设置页（3 个）：
1. `use_app_api`（蓝云直链解析服务）
2. `use_third_party_downloader`（使用第三方下载器）
3. `click_open_download_manager`（禁用应用内置下载管理 / 内置下载管理器）— 两处入口同一键

消息设置页（6 个）：
1. `send_message`（通知栏提醒）
2. `auto_check_favorites`（定期检查收藏夹更新）
3. `get_clipboard`（复制分享链接后提醒打开）
4. `mobile_data_warning`（使用移动数据传输时提醒）
5. `delete_secondary_confirmation`（删除需要二次确认）
6. `ringtone`（传输完成铃声提醒）

> 上述 9 项是核心布尔开关，CloudBox 必须做本地持久化。
> 另有若干「开关型」子项位于弹窗内、也需持久化：`api_enabled`、`api_download`、`api_enable_post`、`api_enable_header`、`auto_user_agent`、`link_replacement`（+`link_replacement_new`）、`custom_downloader`、`custom_download_manager`（及其 pack/activity 文本）。

### B. 只是「弹窗 / 跳转入口」，本身不需要存设置值（但弹窗内的字段需要存）

下载设置页：
- 蓝奏云域名（弹窗 → 存 `domain_name` 文本）
- 文件上传网址（弹窗 → 存 `upload_url` 文本）
- 文件域名替换（弹窗 → 存 `link_replacement`/`link_replacement_new`）
- 自定义 UserAgent（弹窗 → 存 `user_agent`/`auto_user_agent` 文本）
- 第三方直链解析服务 / 直链解析 API（弹窗 → 存 14 个 `api_*` 字段）
- 系统下载器保存位置（弹窗 → 存 `download_folder`）
- 下载文件方式（弹窗 → 存 `custom_downloader*`）
- 内置下载管理器（弹窗 → 存 `click_open_download_manager`）
- 外部下载管理器（弹窗 → 存 `custom_download_manager*`）

消息设置页：
- 管理应用通知（**跳转页面**，无键，不存值；值来自系统通知权限状态）
- 检查更新间隔（弹窗 → 存 `auto_check_favorites_time`）

### C. 点击即执行、不存设置值的「动作」项
- 通知测试（消息页，仅 debug_mode 显示，发送测试通知）
- 重置功能提示（消息页，把 4 个 tip 键置 nil）

---

## 四、数量统计

- **下载设置页**：卡片级设置项 **12 项**（不含顶部「注意」提示）；其中「第三方直链解析服务」再展开 **14 个 api_* 子字段**。
- **消息设置页**：共 **10 项**。
- **默认值「未确认」的项**：**13 项**（全部是下载设置页 api 弹窗内的 `api_*` 子字段，除 `api_enabled` 外）；消息设置页 **0 项**未确认。
- 未能在给定明文目录定位到弹窗函数定义的：`域名弹窗`、`上传网址弹窗`（键名已通过读写处 `ds.lua:47-48` + `home_func.lua` 确认，仅弹窗函数体所在文件未定位），已在表中注明。
