# CloudBox「设置项默认值」对齐原版蓝云 审计报告

> 审计对象：CloudBox 设置存储 `SettingsStore.kt` 中每一项的默认值（形如 `it[keyXxx] ?: 默认值`）。
> 对照基准：原版蓝云 v1.3.4.10 反编译 Lua 源码 `/sandbox/workspace/apk_analysis/src_readable/`（明文可读）。
> 关键基准文件：`ty_core.lua` 的「检查设置」函数（设置初始化，行 881–1193）。
> 审计日期：2026-09-23。**本报告未修改任何源码文件。**

## 判定口径（三选一）

- **一致**：CloudBox 默认与原版默认语义/取值相同。
- **不一致**：默认值与原版不同，**必附原版文件:行 + 原文片段**。
- **无法核对**：找不到原版对应设置项出处；不猜、不编。

---

## 一、逐项对照表

| 设置项 | CloudBox 默认 | 原版默认 | 原版出处(文件:行) | 判定 | 说明 |
|---|---|---|---|---|---|
| userAgent（UA） | `AppConstants.DESKTOP_UA`＝固定桌面 Chrome UA（`"Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/126.0.0.0 Safari/537.36"`） | 设备自带 WebView 的 UA 字符串；且 `auto_user_agent=true` 会在启动时用设备 UA 覆盖它 | `ty_core.lua:992-993`（`设置.user_agent = WebView(...).getSettings().getUserAgentString()`）、`ty_core.lua:989-990`（`auto_user_agent = true`）、`home.lua:86-96`（启动时以 WebView UA 覆盖 user_agent）；`download_settings.lua:1904`「解析直链相关连接仍使用设备默认 UserAgent」 | **不一致** | 原版默认是**设备动态 UA**（且无 Ctrl 的桌面 UA 常量，全库搜 `Mozilla/5.0`/`Windows NT` 无命中）；CloudBox 硬编码了桌面 Chrome UA 作为默认。属**有意偏离**（CloudBox 注释称“需求要桌面 UA 才能拿下载入口”），非“对反”，但默认值确与原版不同；原版另有一个 `auto_user_agent` 自动 UA 开关，CloudBox 无对应项。 |
| suffixSpoofEnabled（上传后缀伪装总开关） | `false`（关） | `false`（关） | `ty_core.lua:914-915`（`rename_unsupported_file = false`）；`settings/file_settings.lua:61,78-92`（开关 `上传开关` ↔ `rename_unsupported_file`）；`update_log.lua:559`「上传不支持文件文件修改后缀为 .zip，且此功能默认关闭」 | **一致** | 现状**已与原版一致**（默认关）。注：`update_log.lua:907` 是**旧版本**(1.1.x)曾“默认开启”，1.2.4.0（`update_log.lua:559`）起改为默认关闭，以最新为准。这正是历史上“对反了”的那一项，现已修正。 |
| spoofSuffixList（需伪装的上传后缀列表） | `"apk,bat,dll,exe,jar,msi,sh"`（`SpoofSuffixUtil.DEFAULT_SUFFIXES` 排序） | 无对应设置项（原版只有布尔开关 + `file_supported` 支持格式列表） | — | **无法核对** | 原版没有“需要伪装的后缀列表”这一配置，只用布尔开关 `rename_unsupported_file` + `file_supported`/`默认支持列表` 判断“不支持格式”。CloudBox 自建的可编辑后缀列表在原版无出处。 |
| thirdPartyResolverUrl（第三方直链解析 API 地址） | `""`（空） | 空（原版 `api_url` 未在「检查设置」中初始化，起始为 nil、UI 输入框显示为空） | `ty_core.lua` 检查设置全段无 `api_url` 初始化；`settings/download_settings.lua:116`（`api_url` 取自「地址文字」输入框）、`download_settings.lua:738` | **一致** | 二者默认都是“未配置（空）”，且都以“功能开关默认关”为前提（`api_enabled=false`，`ty_core.lua:1010-1011`）。语义一致。 |
| darkMode（深色模式） | `"system"`（跟随系统） | 跟随系统：`dark_theme=false` 但 `auto_dark_theme=true`，启动时按系统夜间模式写入 `dark_theme` | `ty_core.lua:998-999`（`auto_dark_theme = true`）、`ty_core.lua:1879-1884`（按 `获取系统夜间模式()` 设定 `dark_theme`）、`settings/customize_settings.lua:28`（“跟随开关”↔`auto_dark_theme`） | **一致** | 原版默认“跟随系统”，CloudBox 默认 `"system"`，语义一致。（CloudBox 用单字段字符串，原版用 `dark_theme`+`auto_dark_theme` 双布尔。） |
| appLanguage（应用内语言） | `"system"` | 无该设置项；`语言()` 是恒等函数（多语言框架空壳） | `ty_core.lua:1365-1369`（`L0_0["语言"] = function(A0_145) … return A0_145 end`） | **无法核对** | 原版**没有**“应用语言”设置项，且 `语言()` 直接返回入参（无词表），无默认值可比。 |
| warnMobileNetwork（移动网络下载前提醒） | `true`（开） | `true`（开） | `ty_core.lua:1019-1020`（`mobile_data_warning = true`）；`settings/message_settings.lua:58`（“流量开关”） | **一致** | 一致。 |
| showFileTypeLabel（显示文件类型标签） | `true`（开） | `true`（开） | `ty_core.lua:1095-1096`（`show_file_type_label = true`） | **一致** | 值一致（默认开）。**注**：CloudBox 注释把出处写成 “ty_core.lua:809”，行号有误（809 是 `默认支持列表` 的 `[38]="lolgezi"`），正确行号是 1095-1096；但取值 `true` 正确。 |
| showAccountButton（显示账号入口按钮） | `true`（开） | `true`（开） | `ty_core.lua:1086-1087`（`show_account_button = true`） | **一致** | 一致。 |
| autoCheckFavoritesDays（收藏夹自动检查间隔天数，0=关） | `0`（=关） | 「是否自动检查」默认关（`auto_check_favorites=false`）；「间隔」字段默认 7 天（`auto_check_favorites_time=7`） | `ty_core.lua:926-927`（`auto_check_favorites = false`）、`ty_core.lua:923-924`（`auto_check_favorites_time = 7`）；`settings/message_settings.lua:54-62`；`layout/message_settings_layout.lua:542-544` | **一致** | 原版把“开关”与“间隔”拆成两字段：**开关默认 false（即默认不自动检查）**，间隔字段默认 7 天。CloudBox 合并为单字段（`0=关`），默认 `0=关`，与原版“默认不检查”**一致**。**字面差异提示**：原版间隔字段 `auto_check_favorites_time` 的字面默认值是 `7`（用户在原版打开开关后默认 7 天），CloudBox 该键默认值是 `0`；两者“默认关闭”的行为一致，但字面取值不同、CloudBox 也无“默认 7 天”概念。 |
| lastAutoCheckTime（上次自动检查时间） | `0L`（=从未检查） | 未初始化（nil）；使用处按 `or 0` 处理（0=从未检查） | `home.lua:561`（`if (设置.last_auto_check_time or 0) < …`）、`layout/message_settings_layout.lua:542`（`if 设置.last_auto_check_time ~= 0`） | **一致** | 原版该字段初始为 nil、读取时按 0 处理，CloudBox 默认 `0L`，语义一致。（此为运行时状态，非开关类设置。） |
| autoLoad（自动加载页面剩余内容） | `true`（开） | `false`（关） | `ty_core.lua:1040-1041`（`auto_load = false`）；`settings/action_settings.lua:125`（“下一页开关”↔`auto_load`） | **不一致** | 原文证据：`ty_core.lua:1040-1041`「`if 设置.auto_load == nil then 设置.auto_load = false`」。原版默认**关**，CloudBox 默认**开**——**方向相反**。CloudBox 注释自认这是有意偏离（去掉底部“加载更多”按钮后，若默认关会“功能死锁”），但默认值确实与原版相反。 |
| lastReadAnnouncementId（最近已读公告 id） | `""`（=从未读过） | 无公告系统 | — | **无法核对** | CloudBox 自建公告系统，原版无对应设置项。 |
| iconPackPath（当前图标包目录） | `""`（=内置 Material 图标） | 内置默认图标包路径 `<luaDir>/icons/color`，名称「默认图标」 | `ty_core.lua:1119-1120`（`icon_pack_path = 活动.getLuaDir().."/icons/color"`）、`ty_core.lua:1116-1117`（`icon_pack_name = "默认图标"`）、`ty_core.lua:2788`（异常时恢复为 `…/icons/color` + “默认图标”） | **一致** | 二者语义都是“使用默认（内置）图标包，非用户导入”，判定一致。**字面差异**：原版默认指向内置彩色图标包目录（非空路径），CloudBox 用空串表示内置 Material 图标（CloudBox 未移植原版内置彩色图标包，改用 Material 矢量图标）。 |
| showDescTag（列表显示简介/密码标记） | `true`（开） | `true`（开） | `ty_core.lua:1070-1071`（`show_desc_tag = true`）；`settings/customize_settings.lua:837`（“简介开关”） | **一致** | 一致。 |
| twoLineTitle（文件标题双行显示） | `false`（关） | `false`（关） | `ty_core.lua:932-933`（`two_line_title = false`）；`settings/file_settings.lua:27`（“双行开关”） | **一致** | 一致。 |
| getClipboard（剪贴板分享链识别） | `true`（开） | `true`（开） | `ty_core.lua:908-909`（`get_clipboard = true`） | **一致** | 一致。 |
| deleteConfirm（删除二次确认） | `true`（开） | `true`（开） | `ty_core.lua:902-903`（`delete_secondary_confirmation = true`） | **一致** | 一致。 |

**合计：检查 18 项 → 一致 13 项，不一致 2 项，无法核对 3 项。**

---

## 二、附录：UI 层初始值 vs 存储层默认值（交叉参考，不计入上表判定）

任务给定的默认值口径是 `SettingsStore.kt`（`it[key] ?: 默认值`），上表以此为准。但审计中发现 **UI 层另有一份初始值**，其中个别项与存储层默认值不一致，且更接近“历史对反”的残留，需提请注意（均有原文证据）：

| 项 | 存储层 `SettingsStore.kt` | UI 层 `SettingsViewModel.kt` `SettingsUiState` | 是否冲突 |
|---|---|---|---|
| suffixSpoof（后缀伪装） | `false`（第 82 行） | **`true`（第 30 行）** | **冲突**：UI 层初始值仍是 `true`（历史“对反”残留）。`init{}` 会异步从 store 读回并覆盖为 `false`（第 140/157 行），但**首帧 / init 异常时设置页开关会显示为“开”**。 |
| showFileTypeLabel（显示文件类型） | `true`（第 132 行） | `false`（第 45 行） | 冲突（注释甚至误称“原版默认也是关”，与原版 `true` 相反）；同样被 `init{}` 覆盖为 `true`。`feature/main/MainScreen.kt:137` 的 `collectAsState(initial = false)` 亦为 `false`。 |

其余项（userAgent/thirdPartyResolver/darkMode/appLanguage/warnMobileNetwork/showAccountButton/autoCheckFavoritesDays/autoLoad/iconPackPath/showDescTag/twoLineTitle/getClipboard/deleteConfirm）UI 层初始值与存储层默认值一致。
说明：这些 UI 层初值在 `SettingsViewModel.init{}`（第 137–175 行）中会被 store 的真实值覆盖，故**用户最终看到/生效的默认值 = 存储层默认值**。此附录仅提示 `suffixSpoof=true` 这一处仍带“对反”痕迹、建议顺手对齐为 `false`。

---

## 三、总结：不一致项中，最可能造成用户可见副作用的（按风险排序）

本次审计只有 2 条判定为**不一致**，另有 3 条无法核对（原版无对应项，风险不成立）。就这 2 条“不一致”的**用户可见副作用**排序如下：

**风险 1（最高）——autoLoad（自动加载页面剩余内容）：原版默认关，CloudBox 默认开。**
- 用户可见副作用：用户一进列表就会被自动续拉下一页，产生**额外网络请求与流量消耗**；对“省流量、不预读”的用户是默认踩坑。这是唯一一条**默认方向与原版相反（对反）**的项，性质与历史已知的“后缀伪装对反”完全相同，最值得优先复核是否需要保留这个有意偏离。
- 备注：CloudBox 注释给出了保留默认开的理由（底部“加载更多”按钮已删除，默认关会功能死锁）。若接受该理由，应视为“有意偏离”并在 UI/文档上显式说明，避免再次被误认为“声称对齐却对反”。

**风险 2（较低但明确）——userAgent（默认 UA）：原版默认设备 WebView UA，CloudBox 默认固定桌面 Chrome UA。**
- 用户可见副作用：默认即以“Windows 桌面 Chrome”身份请求所有站点；若某站点/接口对 UA 有行为差异，可能造成与原版**不一致的页面/接口表现**（属有意设计，通常无害，但会让“对齐原版”的说法在默认值层面不成立）。此条是**取值不同**而非方向相反，用户可见副作用弱于 autoLoad。

**关于 3 条“无法核对”**：`spoofSuffixList`、`appLanguage`、`lastReadAnnouncementId` 均因**原版没有对应设置项**（CloudBox 自建），不存在“与原版对反”的风险；其中 `appLanguage` 属 CloudBox 反超项（原版多语言是空壳），无需对齐。

**结论**：历史“对反”的后缀伪装（`suffixSpoofEnabled`）在存储层已修正为与原版一致的 `false`；当前仍存的“默认反向”只有 `autoLoad`（原版关→CloudBox 开），另有 `userAgent` 取值不同。另建议清理 `SettingsViewModel.kt` 中 `suffixSpoof=true` 这一处 UI 层“对反”残留。
