# 云匣（CloudBox）

轻量级网盘第三方 Android 客户端（自用分发）。通过 OkHttp/Retrofit 模拟网盘 Web 端（woozooo 域名体系）接口交互，绕过官方 App 对非会员手机端隐藏 APK 等格式下载入口的限制。

> ⚠️ **仅供个人学习使用**。本项目的接口逆向实现基于公开开源项目（LanZouCloud-API 等，MIT 协议）与公开抓包资料，请勿用于商业用途或大规模分发。

## 功能总览

| 模块 | 说明 |
|---|---|
| 登录 | 账号密码登录 + Cookie 手动导入；多账号槽位一键切换；Cookie 过期（>18 天）静默重登 |
| 文件列表 | 列表/网格双模式、下拉刷新、分页加载、面包屑导航、长按多选（批量删除/移动/分享） |
| 文件管理 | 新建文件夹、重命名、删除（入回收站）、移动、设置提取码、设置描述、回收站（恢复/彻底删除/清空） |
| 上传 | 单文件直传（100MB 上限）、超限自动分卷（95MB/卷 .zip/.z01/.z02）、WorkManager 后台队列、exe/apk 后缀伪装 |
| 下载 | 系统 DownloadManager、桌面 UA + Referer 防 403、APK 自动跳安装器、下载记录页 |
| 分享 | 生成分享链接（短链）、ZXing 二维码、提取码、收藏夹（备注） |
| 直链解析 | 分享页提取 sign → ajaxm.php → dom+/file/+url；带密码/文件夹递归/批量解析 1-3s 延时防风控；直链缓存 TTL 1h；第三方解析服务可替换 |
| 搜索 | Room 索引全盘同步，FTS（英文/数字）+ LIKE（中文）双轨查询 |
| 其他 | 剪贴板链接自动识别、域名远程配置（Gist JSON）+ 手动覆盖 + 连通性测试、深色模式、动态取色 |

## 编译步骤

### 环境要求
- JDK 17+
- Android SDK（compileSdk 34）
- Android Studio Ladybug 及以上（或命令行 Gradle 8.9）

### 命令行构建
```bash
# 1. 生成 wrapper（若仓库未附带 gradle-wrapper.jar）
gradle wrapper --gradle-version 8.9

# 2. 构建 debug 包
./gradlew assembleDebug

# 3. 构建 release 包（已配置签名，见下）
./gradlew assembleRelease
```

### 签名说明（签名一致，可覆盖安装）
项目内置 `app/keystore/cloudbox-release.keystore`（自用分发场景，密钥已随仓库提交），
release 与 debug 均使用该 keystore 签名，因此：
- CI（GitHub Actions）构建的 APK 与本地构建的 APK 签名一致，可互相覆盖安装
- 升级安装不会提示"签名不一致"

如需更换签名：用 `keytool -genkeypair` 生成新 keystore，替换 `app/keystore/` 下文件并修改 `app/build.gradle.kts` 中的 `signingConfigs` 密码。

### GitHub Actions 自动构建
推送代码到 `main` 分支（或手动触发 workflow_dispatch）即自动执行 `assembleRelease`，
构建产物以 Artifact 形式保存（仓库 Actions 页面 → 对应 run → Artifacts → 下载 APK）。

## 域名配置

蓝奏云域名历史已漂移至少六轮（lanzous → lanzou → lanzoux → lanzoui → lanzoup → lanzouu → lanzouo → lanzouh），
域名打不开时在 **设置 → 域名配置** 中处理：

- **远程配置**：发布一个 JSON 到 GitHub Gist 或任意 HTTPS 地址，格式：
  ```json
  {
    "loginEntry": "https://up.woozooo.com/",
    "diskMain": "https://pc.woozooo.com/",
    "shareBase": "https://www.lanzou.com/",
    "uploadServer": "https://pc.woozooo.com/",
    "fallbackDomains": ["https://www.lanzoui.com/", "https://www.lanzoup.com/", "https://www.lanzoux.com/", "https://www.lanzouo.com/", "https://www.lanzouh.com/"]
  }
  ```
  启动失败自动回落本地默认值；App 内置黑名单拦截 `lanzous.com`（已被第三方抢注，解析到不良站点）。
- **手动覆盖**：直接编辑各字段并保存（用户手动配置优先于远程配置）。
- **连通性测试**：并发 HEAD 请求测 RTT，按延迟排序显示。

## 接口实现说明（逆向来源与差异标注）

主要依据：`zaxtyson/LanZouCloud-API`（MIT，2025 年仍活跃维护，commit 3bb917f）源码逐字核对，另参考 hanximeng/LanzouAPI、xhgzs/LanzouApi 等公开资料交叉验证。与常见旧教程的差异：

| 项目 | 本实现（以近期源码为准） |
|---|---|
| 登录态文件列表 | `doupload.php task=5`（仅 folder_id/pg），**不使用** t/k/up/ls/rep（旧教程方案仅用于分享页 filemoreajax.php） |
| t/k 参数 | 仅分享文件夹列表（filemoreajax.php）需要，每次从分享页 HTML 实时正则提取，禁止缓存 |
| 上传 | `fileup.php` multipart：`task=1&vie=2&ve=2&id=WU_FILE_0&folder_id_bb_n=<folderId>&name=<文件名>`（参数名是 `folder_id_bb_n` 而非 `folder_id`） |
| 直链拼接 | `dom + '/file/' + url`（旧教程直接拼 dom+url 会 404） |
| 分享链接 | 无"生成"接口，只有"获取"：文件 task=22（拼 is_newd+f_id）/ 文件夹 task=18（new_url 直接可用） |
| 回收站 | `mydisk.php` HTML 交互 + formhash（每次操作现取，不可复用） |
| 移动文件夹 | 官方无接口，客户端仅支持文件移动 |

## 已知限制

- **Cookie 约 20 天过期**：App 会在活跃超过 18 天时自动用保存的账密静默重登；若账号改密或静默重登失败需手动重新登录。
- **域名可能漂移**：蓝奏云不定期更换域名，打不开时需更新域名配置（远程 JSON 或手动）。
- **直链约 2 小时有效且绑定 Referer**：解析结果缓存 1 小时；过期后需重新解析。
- **免费用户单文件 100MB 上限**：超限自动分卷（95MB/卷）；不承诺任何"登录后放宽"（会员额度为社区传闻，未经验证）。
- **风控**：同 UA/IP 对同一分享页 7 天访问约 5 次，超限临时拉黑；批量操作内置 1-3s 随机延时。
- **文件重命名**：会员功能，非会员可能失败；**文件描述**设置后不能清空；非会员可能无法关闭提取码。
- **中文搜索**：Room FTS 对中文分词无效，使用 LIKE 兜底（全盘索引同步后可用）。

## 技术栈

Kotlin · Jetpack Compose (Material 3) · MVVM + Clean Architecture · Retrofit + OkHttp + Jsoup · Room + DataStore + EncryptedSharedPreferences · Hilt · WorkManager · DownloadManager · ZXing · Zip4j

## 免责声明

本项目仅用于学习 Android 开发与 Web 协议分析。使用本项目产生的任何后果（包括账号风控）由使用者自行承担。请尊重网盘平台的服务条款。
