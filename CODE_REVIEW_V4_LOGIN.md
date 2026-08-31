# CloudBox 登录失败根因分析（V4 · 实测专项）

- **触发**：用户首次真实登录，报"登录失败（未获取到身份凭证）"，确认账号密码正确
- **分析方式**：拉取 LanZouCloud-API 现行源码比对 + **对蓝奏云线上端点逐个实测**（本轮全部结论有一手证据）
- **结论**：**App 的账号密码登录从第一版起就打在一个已经不存在的接口上。** `login.php`（task=3）在 pc.woozooo.com 和 up.woozooo.com 均已下线（实测均 404）。蓝奏云已将登录迁移到统一账号中心 `accounts.woozooo.com`，参数体系、挑战机制、凭证下发方式全部变了。这不是回归 bug，是该功能**从未真正可用过**——前四轮纯代码审查无法发现（接口存活性只能实测），本轮实测锤死。

---

## 一、证据链（全部一手实测，2026-08-31 晚）

| # | 实测 | 结果 |
|---|---|---|
| E1 | POST `pc.woozooo.com/login.php` task=3（App 现行请求，域名拦截器把 `.php` 路由到 diskMain） | 返回 404 页（HTML 含 `pan.lanzou.com/?404`），**无 Set-Cookie** → `isLoggedIn()`=false → 报"未获取到身份凭证"，与用户报错完全吻合 |
| E2 | POST `up.woozooo.com/login.php`（配置项 loginEntry 指向的主机） | **HTTP 404**，login.php 在 up 域也不存在 |
| E3 | GET `pc.woozooo.com/account.php`（LanZouCloud-API 现行登录页入口，桌面/手机 UA 均试） | 返回 830 字节 JS 跳转壳：`document.location="https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com"` —— **旧流程也已死** |
| E4 | GET `accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com` | 返回 **acw_sc__v2 JS 挑战页**（`var arg1='3347925A...'`） |
| E5 | 用原版算法算出挑战值，带 `Cookie: acw_sc__v2=6a9595...` 重 GET | **挑战通过**，拿到真登录页（3251 字节，含表单与 uselogin JS） |
| E6 | POST `accounts.woozooo.com/accounts.php` `task=uselogin&username=...&password=...&ref=pc.woozooo.com`（假凭证，验证格式） | 返回 JSON `{"zt":0,"msgs":"用户名不正确"}` —— **新端点存活且参数格式确认** |

**现行官方登录流程**（从 E5 登录页 JS 逐行读出）：
1. GET 登录页（过 acw 挑战，需带 `ref=pc.woozooo.com` 参数）
2. AJAX POST `/accounts.php`，表单：`task=uselogin`、`username`、`password`、`ref=pc.woozooo.com`
3. 响应 JSON：`zt=1` 时 `msgs`=**中转鉴权 URL**，前端 `window.location.href = msgs` 跳转；`zt=0` 时 `msgs`=错误文案
4. 访问中转 URL →（推断）跨域重定向回 pc.woozooo.com 并 Set-Cookie 下发 `phpdisk_info`+`ylogin`

> 与 App 实现的差异：端点（login.php→accounts.php）、参数名（uid→username、pwd→password、task 3→uselogin）、缺 ref、缺 acw 挑战、缺中转跳转——**五个维度全错**，纯靠"部分账号仍可用"的侥幸设计。

---

## 二、修复清单（L1-L6，按实现顺序）

### L1（核心）按新流程重写 `AuthRepositoryImpl.login()`
```
1. GET https://accounts.woozooo.com/accounts.php?action=login&ref=pc.woozooo.com（桌面 UA）
2. 响应含 var arg1= → AcwScV2.compute() 算值 → cookieJar.putCookie("accounts.woozooo.com",
   "acw_sc__v2", <纯值>) → 重 GET 拿真登录页
   ⚠️ App 的 AcwScV2.compute() 返回带 "acw_sc__v2=" 前缀的串，取值需 substringAfter('=')；
   ⚠️ 域名注意：挑战 cookie 绑 accounts.woozooo.com（不是 www.lanzou.com），V2 加的
   putCookie(domain,...) 按完整 host 建 host-only cookie 即可
3. POST https://accounts.woozooo.com/accounts.php
   form: task=uselogin, username=<账号>, password=<密码>, ref=pc.woozooo.com
   带 acw cookie + 头 X-Requested-With: XMLHttpRequest（实测带此头，稳妥保留）
4. 解析 JSON：
   zt=0 → 直接把 msgs（"用户名不正确"/"密码错误"等）作为失败原因展示——比现在的
   "未获取到身份凭证"友好得多
   zt=1 → 取 msgs（中转鉴权 URL）
5. GET 中转 URL（OkHttp 自动跟随重定向，不要截断）→ 响应链上 pc.woozooo.com 的
   Set-Cookie(phppdisk_info+ylogin) 由 CookiePersistenceJar 自动捕获 → isLoggedIn()=true
   ⚠️ 此步是我唯一无法实测的环节（需真实凭证）。若实测发现 cookie 不在中转响应链上，
   对照浏览器 DevTools 的 Network 面板补齐（可能需要带 ref 参数或 Referer 头）
6. 成功后 GET pc.woozooo.com/account.php 复核：页面不再含"网盘用户登录"即登录态有效
```
`ensureSession()` 的静默重登复用同一条链路，无需单独改。

### L2 域名拦截器加路由规则
`LanzouDomainInterceptor` 的 when 分支加：`path.contains("accounts.php") -> config.loginEntry`，并把 `LanzouDomainConfig.DEFAULT.loginEntry` 从 `https://up.woozooo.com/` 改为 `https://accounts.woozooo.com/`（E3 已证 up 域的 account.php 也不再是登录入口；up.woozooo.com 仅剩官网主页）。
远程配置/用户覆盖已有此字段的会跟随新默认值，无需迁移。

### L3 中转 URL 的直连处理
中转 URL 可能是绝对地址（任意 woozooo 子域）。走统一 OkHttp client 时注意：域名拦截器只重写占位 host（lz.dynamic.invalid），绝对 URL 不受影响 ✓（V1 设计如此，确认无冲突）。

### L4 登录错误文案直通
新接口 msgs 直接给中文错误（"用户名不正确"），现有 `extractLoginError()` 关键词匹配表可保留作兜底，但优先展示 msgs 原文。

### L5 白名单确认（无需改动，仅记录）
`TRUSTED_SHARE_HOSTS` 含 `woozooo.com` 后缀，`accounts.woozooo.com` 以 `.woozooo.com` 结尾已被覆盖 ✓；RemoteDomainSource.isTrustedDomain 同理 ✓。

### L6 文档更新
- AI_MAINTENANCE.md §5 task 表：登录条目改为 accounts.woozooo.com 体系，注明"login.php 已于线上验证死亡（2026-08-31）"
- App 的 login() KDoc 中"login.php 为旧入口（部分账号仍可用）"注释删除——已被实测证伪
- **流程教训写入**：涉及第三方接口的功能，修复后必须真机走通一次再销账；纯代码审查（含我历轮复审）无法验证接口存活性

---

## 三、给用户的临时出路（不等修复）

**Cookie 导入路径完好可用**（本轮已复核代码）：
1. 电脑浏览器登录 lanzou.com（或 pc.woozooo.com）
2. F12 → Application/Storage → Cookies → 复制 `phpdisk_info` 的值（长串）
3. App 登录页切到"Cookie 导入"：账号名框填你的账号名（作槽位名），粘贴 `phpdisk_info=刚复制的值`
4. 导入后即可正常使用全部功能；phpdisk_info 有效期约 20 天，过期后重新导入或等修复

---

## 四、验收标准

- 真机：正确账号密码登录 → 直接进入文件列表，杀进程重启不丢登录态
- 真机：错误密码 → 提示"密码错误"（不再是"未获取到身份凭证"）
- 首次登录触发 acw 挑战时自动通过（AcwScV2 已在 V2 轮与原版逐行对齐，本轮 E5 实测原版算法可过挑战）

---

*分析：Zero（外部复审 AI）· 2026-08-31 · 全部端点行为一手实测。修复完成后此报告 L1-L6 与 V3 遗留 N1-N4/P3 一并销账。*
