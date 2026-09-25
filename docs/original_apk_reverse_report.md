# 「蓝云」APK 逆向分析报告

- 样本：`1.3.4.10.apk`（MD5 `c2a844d7e7e5d089ae7f6cc817359200`，4.5MB）
- 应用名：**蓝云**（第三方「蓝奏云」网盘客户端）
- 包名：`com.tooyoung.lanzou`；版本 1.3.4.10 (versionCode 214)
- 框架：**AndroLua**（`com.stardew.androlua`，Lua 5.3 + LuaJava）。壳是开源框架，业务逻辑 100% 用 Lua 写成
- 主 Activity：`com.stardew.androlua.Welcome`；Application：`com.stardew.androlua.LuaApplication`

---

## 一、加密与混淆机制（已全部破解）

该 APK 对 Lua 脚本做了**两层**保护，且都已被完整还原。

### 第 1 层：容器加密（脚本文件本身）

`assets/**/*.lua`、`lua/*.lua` 均为密文。真实流程：

```
明文源码
  └─(1) luac 编译 → Lua 5.3 字节码，签名首字节 0x1B 被改为 0x1C
  └─(2) zlib 压缩
  └─(3) 累积异或（rolling XOR）：c[i] = p[i] ^ p[i-1]，p[-1]=0
  └─(4) Base64 编码，并在串首插入一个 '='（解码器会把它强制当成 base64 值 7，从而令还原后首字节恰为 0x1C）
```
解密（Python 核心）：
```python
raw   = base64.b64decode('H' + s[1:])       # 首字符替换为 'H'（值=7）
out   = rolling_xor_decode(raw)             # plain[i]=cipher[i]^plain[i-1]
out[0]= 0x78                                 # 修复 zlib 头 (78 9C)
code  = zlib.decompress(out)                # → Lua5.3 字节码(签名 0x1C)
code[0]=0x1B                                 # 还原标准签名
```
> 关键定位：`libluajava.so` 中修改过的 `luaL_loadbuffer` 分支（首字节 `=` → base64，解码后首字节 `0x1C` → 累积异或 + 补 zlib 头 + inflate）。

### 第 2 层：字符串混淆（字节码内的所有字符串常量）

字节码本身合法，但**所有字符串常量**（标识符名、字符串字面量、中文文案）都被加密。由改过的 Lua 虚拟机的 `undump` 在加载时解密——**逐字节滚动 XOR，密钥按字符串长度和位置递推**，用魔数 `0x80808081` 做 /255 运算：

```python
def dec_str(blob):                       # 长度保持，可原地替换
    n=len(blob)
    if n==0: return blob
    buf=bytearray(blob)
    buf[0]=blob[0]^(n%255)                          # 首字节
    if n==1: return bytes(buf)
    x=((n*0x80808081)>>39)&0xffffffff               # 由长度推出的步进种子
    u=(x-((x<<8)&0xffffffff))&0xffffffff
    state=((n+u)&0xffffffff)^blob[0]
    step =(n+state)&0xffffffff
    state=((2*n+state)&0xffffffff)
    for i in range(1,n):
        buf[i]=blob[i]^(state%255)                  # 关键：精确 %255
        state=(state+step)&0xffffffff
    return bytes(buf)
```
> 关键（也是唯一容易踩的坑）：取模必须用**完整 32 位 `state % 255`**。编译器是用魔数 `0x80808081` 实现这个 `%255` 的；若手工照抄魔数近似（或像另一版那样用 16 位截断 `(state&0xffff)%255`），短串没事、**长度 ≥168 后会逐步跑偏**，64KB 长串会大面积乱码。
解密后即得到全部真实字符串（验证样例：`require`/`import`/`activity`/`TextView`/`getLuaDir`/`设置`/`字体`/`更新`…）。

### 反混淆产物

统一流水线 `deobf.py`（容器解密 + 解析字节码 + 字符串解密）→ unluac 反编译 → 转义还原为 UTF-8，得到 **55 个可读 Lua 源码**（90,066 行）。

### 完整性自检结果（`verify.py`）
- **结构完整**：55/55 文件，字节码从 header 逐字段走到末尾，末偏移**精确等于文件长度**（无错位/漏读）。
- **字符串全部还原**：共 41,364 个字符串常量，**41,355 个为合法 UTF-8**；其余 9 个是同一条 **CJK 字节区间 Lua 模式** `[\228-\233][\128-\191][\128-\191]`（用于匹配中文，本就是裸字节，**非乱码**）。
- **其它资源**：263 个非 `.lua` 资源（含 `info.json`）**均未加密**，原样可读。
- 源码中仅 8 行含替换符，全部为该模式串；无其它乱码。

---

## 二、应用功能概览

一款第三方**蓝奏云（lanzou）客户端**，功能完整度接近官方：

- **账号**：账号密码登录 / Cookie 登录 / 网页登录抓 Cookie / 多账号管理 / 改密 / 换手机 / 昵称 / 外链设置 / 个人分享链
- **文件**：文件夹与文件列表、分页、拼音/时间排序、全盘加载、搜索、上传（单个/批量/系统选择器）、重命名、简介、移动、删除、设置提取码、新建文件夹、回收站与还原
- **下载**：直链解析（内置 WebView 解析 + 可配置第三方 API）、DownloadManager 下载管理、进度/速度、打开/安装、第三方下载器
- **分享/收藏**：批量生成分享链接+提取码、收藏夹订阅自动更新、二维码扫码
- **系统**：沉浸式状态栏、深色模式、图标包（zip 导入）、通知、后台下载、自更新

### 域名 / 接口清单
- 蓝奏云接口基址 `设置.domain_name`（默认 `pc.woozooo.com`，可切 `up.woozooo.com`）：`doupload.php`(task=2/5/7/8/10/15/19/43/47)、`mlogin.php`(task=3)、`myfile.php`、`mydisk.php`、`filemoreajax.php`、`fileup.php`
- 图床 `image.woozooo.com`；客服 `support.qq.com/products/344089`
- 更新：`appcenter.ms/api/v0.1/apps/Stardew/Lancloud/...`、`excited233.github.io/lanzous/appupdate.json`
- 捐赠：`qr.alipay.com/fkx10763k2cv1qxdybazaa2`；社区入口 `too-young.lanzoui.com/b084swv7g`

### 权限（`init.lua` 声明）
`INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`MANAGE_EXTERNAL_STORAGE`、`READ/WRITE_EXTERNAL_STORAGE`、`QUERY_ALL_PACKAGES`、`REQUEST_INSTALL_PACKAGES`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

### 硬编码密钥 / 标识
- 配置/备份/日志/调试码加密口令：**`LanyunByStardew6`**（AES/ECB/PKCS5）
- 蓝奏链路加密：**`344089JbfS4903==`**（AES/CBC/PKCS7，固定 IV）
- 百度移动统计 appkey `35b18fea22`；微软 AppCenter id `ef3fef1c-4fe2-4964-ae2a-c253fefec7a8`
- 反篡改：签名 hashCode 硬校验 `-496687148` + 内置公钥（含 ASCII `Too_young`）

---

## 三、逐文件分析（全部 55 个）

### 1. 框架/基础层
| 文件 | 作用 |
|---|---|
| `init.lua` | 全局常量（appname/appver/packagename/theme/debugmode）与权限申请列表 |
| `main.lua` | 入口路由：`require("import")`→import json/ty_core→未登录跳 login，否则 import home；异常跳 error_page |
| `error_page.lua` | 全局错误页；显示版本、Build 信息、错误内容与加载耗时 |
| `func.lua` | 弹窗工具箱 + **应用签名反篡改校验**（不一致弹"可能被植入病毒"红警）+ 备份/还原（含 user/pass/cookie 等）+ 捐赠 + `os.execute("pm clear ...")` 重置 |
| `ty_core.lua` | 核心库：类导入、**加密(encrypt/decrypt、encrypt128/decrypt128、MD5、文件加密)**、设置持久化(硬编码密钥)、权限/安装/分享/通知/剪贴板/统计/主题/单位换算 |
| `json.lua` / `lua_json.lua` | 两套纯 Lua JSON 库 |
| `lua_import.lua` | AndroLua 的 `import`（Java 类绑定、`loadDex`、`package.loadlib`） |
| `lua_loadlayout.lua` | `.aly` 布局引擎（Lua 表 → Android View 树，支持 0x1B Lua 字节码头） |
| `lua_loadmenu.lua` | Lua 表 → Android Menu |
| `lua_loadbitmap.lua` | 图片加载（http(s) 走 `LuaBitmap.getHttpBitmap`） |
| `lua_test.lua` | 调试钩子：读取 `LuaDir/.test.lua` 并 `debug.sethook` dump 崩溃现场 |
| `layout_lib.lua` | 自建 UI 构件库（标题栏/对话框按钮/调试文本等） |

### 2. 首页模块
| 文件 | 作用 / 关键点 |
|---|---|
| `home.lua` | 首页总控：抽屉侧栏、下拉刷新、RecyclerView 列表与分页、排序、上传、更新检测、签名校验、`loadstring("return "..str)` 反序列化列表数据 |
| `home_func.lua` | 业务函数库：登录/登出、用户信息、文件/文件夹网络操作、**直链获取与解析（内置 WebView + 第三方 API）**、订阅更新、导入图标包、`计算acw_sc_v2`（蓝奏 WAF 反爬令牌） |
| `home_file.lua` | 列表项交互弹窗：上传菜单、批量分享(链接+提取码)、重命名/简介/移动/删除/设密（单个+批量）、下载管理 |
| `layout/home_layout.lua` | 首页整屏布局（DrawerLayout）；含隐藏调试入口（长按上传标题）、警告图标触发签名校验 |

### 3. 文件 / 下载 / 页面模块
| 文件 | 作用 / 关键点 |
|---|---|
| `file.lua` | 本地文件选择器；`Android/data` 用 SAF 授权访问（`android_data_hack` 用不可见字符伪装路径）；全盘递归搜索；枚举已安装应用 |
| `download.lua` | DownloadManager 记录展示（约200条）、进度/状态轮询、打开(FileProvider)、安装APK、删除(含 `LuaUtil.rmDir` 删文件) |
| `favorites.lua` | 收藏夹（本地保存分享链接/文件夹、置顶、检查更新） |
| `recycle.lua` | 回收站（还原/清空） |
| `view.lua` | 查看文件夹页 |
| `webview.lua` | 内置浏览器/网页登录；**`setJavaScriptEnabled(true)`+`addJavascriptInterface`("JsInterface")+`shouldOverrideUrlLoading` 空实现**；登录后抓 `CookieManager.getCookie` 存 `设置.cookie`；`onDownloadStart` 下载并（更新场景）安装 APK；**反虚拟机**：探测 VPN 网卡(tun0/ppp0)、蓝牙配对、`BaiduStatService.getTestDeviceId` |
| `layout/*_layout.lua` | 各页面 UI（file/download/favorites/recycle/view/webview/error_page） |

### 4. 账号 / 登录 / 关于 / 调试
| 文件 | 作用 / 关键点 |
|---|---|
| `account.lua` | 账号管理弹窗；改密(task=8)/换手机(task=43)/外链(task=10)/昵称(task=15)/分享链(task=7)；会员购买外链 |
| `login.lua` | 登录：`mlogin.php task=3`（账密明文）、Cookie 校验(task=47)、网页登录回填；账号密码落盘明文 |
| `qr.lua` | 扫码（相机/相册），仅识别蓝奏云分享二维码 |
| `about.lua` | 版本/篡改提示/更新检查下载安装/设备信息/常见问题/许可/反馈/捐赠；**调试模式入口（图标4秒内连点5次）** |
| `update_log.lua` | 静态更新日志 + 捐赠名单 |
| **`debug_tool.lua`** | **隐藏控制台（后门式能力）**：`loadstring` 执行任意 Lua（含解密后输入）、`io.popen("logcat ...")` 读/清日志、`dump(_G)` 导出全部全局（可能含 cookie/user/pass）、杀进程、内存信息、剪贴板加密复制 |
| `layout/login_layout.lua` / `qr_layout.lua` / `about_layout.lua` | 对应 UI；about_layout 内含隐藏调试入口 |

### 5. 设置模块（8 业务 + 8 布局）
| 文件 | 作用 |
|---|---|
| `settings/settings.lua`、`settings_hd.lua` | 设置主页（手机/大屏）；深色模式/下载后缀变更触发重启 |
| `settings/customize_settings.lua` | 外观布局、主题色、图标包管理（zip 导入解压）、`解析链接(url)` |
| `settings/action_settings.lua` | 行为与权限：自动加载、缓存、无障碍、权限状态、电池优化、请求间隔/排序长度 |
| `settings/file_settings.lua` | 文件与上传：双行、后缀改写、搜索模式、android/data、旧版选择器 |
| `settings/download_settings.lua` | 连接与下载：域名/上传地址/链接替换、直链开关与第三方 API、下载位置、第三方下载器、下载管理 |
| `settings/message_settings.lua` | 通知与提醒：更新间隔、剪贴板、流量、删除、铃声 |
| `settings/privacy_settings.lua` | 隐私与数据：统计开关、更新开关、**身份 Cookie（锁屏验证后明文展示）**、备份/恢复、**清理缓存(`os.execute("rm -r cacheDir)")`**、清空收藏、还原设置、重置应用 |
| `layout/*_settings*_layout.lua` | 各设置页 UI；部分设置逻辑内联在 onClick 里 |

---

## 四、安全 / 隐私风险汇总

| 级别 | 风险 | 位置 |
|---|---|---|
| 🔴 高 | **隐藏调试控制台**：可执行任意 Lua、读/清 logcat、导出全部全局变量（含账号/Cookie）、杀进程；入口=关于页图标连点5次+调试码 | `debug_tool.lua`、`about*.lua` |
| 🔴 高 | **自动下载并无签名校验地安装 APK**（更新流程）；更新源为 GitHub Pages / AppCenter，配合 `REQUEST_INSTALL_PACKAGES` 可被投毒 | `webview.lua`、`about.lua`、`download.lua` |
| 🔴 高 | **WebView 配置不当**：JS 开启 + `addJavascriptInterface` + 无 URL 白名单/跳转拦截 | `webview.lua`、`home_func.lua` |
| 🟠 中 | **凭据明文**：账号/密码/Cookie 明文持久化；Cookie 可明文展示复制；请求发往**用户可改的域名**（可被诱导泄露登录态）| `login.lua`、`ty_core.lua`、`privacy_settings.lua` |
| 🟠 中 | **硬编码密钥** `LanyunByStardew6` / `344089JbfS4903==`（ECB 无 IV）→ 配置/备份/日志/调试码等于可逆混淆 | `ty_core.lua` |
| 🟠 中 | `os.execute` shell 拼接（`pm clear` / `rm -r`），删除范围含整个 cacheDir；zip 导入可能 zip-slip | `func.lua`、`privacy_settings.lua`、`customize_settings.lua` |
| 🟠 中 | `loadstring("return "..str)` 动态执行（列表/下载数据反序列化） | `home.lua`、`download.lua` |
| 🟡 低 | 广权限（全盘存储/枚举应用/相机/安装包）；全盘递归扫描；第三方遥测（百度统计+AppCenter 默认开）；设备指纹（蓝牙/VPN/飞行模式/设备ID）| 全局 |

**结论**：这是一个功能完整、并非以窃密为主要目的的第三方网盘客户端，但其"分发的脚本加密"仅为防逆向（两层都已还原），且内含**一个后门式调试控制台**与**无校验的自更新安装链路**——若该 APK 来自非官方渠道（如本次的 `lanosso.com` 第三方站），被二次打包注入后门/广告的风险实际存在，需谨慎安装。

---

## 五、交付物

- `src_readable/`：55 个可读 Lua 源码（已还原字符串为明文中文/英文）
- `deobf.py`：容器解密 + 字节码解析 + 字符串解密 一键脚本
- `luabc.py`：Lua 5.3 字节码解析器
- 打包：`蓝云_decrypted_lua.zip`
