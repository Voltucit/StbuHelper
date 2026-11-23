# 川工商小助手

四川工商学院校园助手，Kotlin + 本地 WebView，Android 8.0+，Java/Kotlin 17。手机直接连接学校、学习通和趣智校园官方服务，不使用开发者中转服务器。

## 功能

- 今日课程、有效未交作业、整周课表、考试与成绩；同步后可离线查看。
- 学习通课程追踪和作业通知。作业自动同步间隔为 12 小时，失败后也按此间隔检查。课表、成绩与考试通过学校教务账号同步。
- 趣智校园洗澡、饮水、绑定机器、离线扫码、蓝牙连接、余额和账单。连接后由用户主动开始使用，结束时提交官方结算并刷新余额。

## 目录

Android 工程由 Gradle 管理。iOS 工程定义在 `ios/project.yml`，两端使用同一份 `app/src/main/assets/frontend` 页面。

```text
app/src/main/
├─ assets/
│  ├─ frontend/
│  │  ├─ pages/       home、timetable、study、life、me、account、settings
│  │  ├─ shared/      公共模型、桥接、路由、同步状态与样式
│  │  ├─ index.html   本地界面入口
│  │  └─ bootstrap.js 启动与事件绑定
│  ├─ auth-engine/    官方登录表单交互脚本
│  └─ licenses/       随 APK 携带的许可
├─ java/
│  ├─ cn/stbu/helper/
│  │  ├─ ui/          原生界面、登录、扫码
│  │  └─ backend/     academic、auth、security、storage、homework、life
│  └─ com/hualala/linyu/  保留上游包名的蓝牙协议代码
└─ res/               Android 图标、主题与系统配置
app/src/test/         用户自行执行的 Kotlin 用例
tests/                JavaScript 用例及测试辅助文件
.run/                 IDEA 的 Android 与 iOS 打包配置
```

页面显示逻辑通常在 `view.js`，操作按用途拆分，专用样式在页面自己的 `styles.css`；公共样式在 `shared/styles.css`。新增或移动脚本时更新 `frontend/index.html` 的加载顺序。移动 Kotlin 类时同步修改导入、Manifest 和 ProGuard。`backend` 指手机上的本地业务代码。

## iOS 与手机桌面组件

iOS 16 或更高版本使用 Swift 和 WKWebView。学习通和学校教务登录在 App 内独立的官方页面完成，登录 Cookie 保存在本机钥匙串。课表、作业追踪、考试与成绩、备份、外观和背景共用 Android 页面。回到前台时，距上次同步满 12 小时会自动刷新作业。已有截止时间的作业可由 iOS 在截止前 24 小时和 3 小时发送本地通知。

生活页接入钱包余额、按月账单与支付宝充值入口。洗澡和饮水继续使用官方 App，现有 Android 水表识别依赖 MAC 地址，iOS 版需要先用真机确认广播中的设备标识与二维码如何对应。

Android 和 iOS 均提供“下一节课”和“未完成作业”两个桌面组件，点按可进入本周课表或待办。Android 新添加时默认一行四格，iOS 中号组件采用横向布局。

课程组件显示当天的课程。当天最后一节课上完后，显示首页的自定义无课提示。作业组件遵循课程追踪选择，显示所有有效待办中最早截止的一项，并标注今天、明天或具体截止日期。没有符合条件的待办时，显示首页的自定义作业提示。组件读取已同步的数据，刷新时间由系统调度。iOS 主应用只把组件所需的课程、作业、作息和提示文字写入 App Group。

在 Mac 上安装 Xcode 和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)，从仓库根目录执行：

```sh
cd ios
xcodegen generate
open StbuHelper.xcodeproj
```

在 Xcode 的 Signing & Capabilities 中，为 `StbuHelper` 和 `StbuWidgets` 选择同一签名团队。真机安装时，两者须使用同一个已注册的 App Group。若默认标识无法注册，在 `ios/project.yml` 修改 `STBU_BUNDLE_ID` 与 `STBU_APP_GROUP` 后重新生成工程，再运行 `StbuHelper` scheme。组件扩展随主应用安装。XcodeGen 会从现有项目图标生成 iOS 图标。

iOS 源码和工程定义已接入。Xcode 构建、真机签名、官方登录、钱包接口和组件刷新由用户验收。

### Windows 电脑上的 iPhone 自测

1. 将源码提交到 GitHub 仓库的默认分支，提交时检查发布密钥、真实的 `keystore.properties` 和 Apple 签名文件均留在本机。
2. 打开仓库的 **Actions → iOS Sideload IPA → Run workflow**。构建完成后，在此次运行的 **Artifacts** 下载 `StbuHelper-Sideload.ipa`。
3. 在 Windows 安装 AltServer，按说明把 AltStore Classic 安装到 iPhone。将 IPA 传到 iPhone 后，在 AltStore Classic 的 **My Apps** 点 **+** 选中 IPA，用自己的 Apple ID 签名。普通 Apple ID 签名后需要在七天内刷新。[AltStore Windows 安装说明](https://faq.altstore.io/altstore-classic/how-to-install-altstore-windows)

自测包支持 iOS 16 及以上，包含主 App，省略需 App Group 的桌面组件。GitHub Actions 提供未签名的真机包，Apple ID 只在安装或刷新时使用。

## 设置

课程追踪列表同时显示课程名和授课教师；升级后的旧目录首次打开会自动只刷新课程目录，不读取全部课程作业。官方目录未提供教师时显示“教师未提供”。

首页“蓝线日程”把当天课程与当天截止的待交作业按时间排列。“接下来”最多显示三项未来截止的作业，可从“全部待办”查看完整列表。

“我的 → 设置”提供晴空蓝、雨雾蓝、暮蓝紫、海湾青、钴蓝、墨海蓝、松石绿、莓紫八种蓝线日程配色，以及绿色、紫色、橙色、玫红四种经典配色。选择背景图片后可以拖动亮度滑杆预览，松手后将 25% 到 150% 的亮度设置保存在本机。设置也包含夜间模式、首页提示、学期与作息、作业提醒和本机数据管理。

无课程默认显示“今天没有课程哦，可以好好休息”，没有有效未做作业时默认显示“好厉害，作业全部都做完了！”。两项提示都可自定义，最多 200 字，留空保存恢复默认；未登录时继续提示连接账号。主题、夜间模式和提示单独保存在本机，不随清除学习数据重置。

设置页源码在 `frontend/pages/settings/`，本机偏好存储在 `backend/settings/AppPreferences.kt`。系统栏边距由外层容器处理，输入法边距继续传给 WebView，避免修改编辑中的 WebView 内边距；后台刷新会等待弹窗关闭或设置文本保存后再重绘。

## 输入法兼容与钱包充值

“我的 → 设置 → 输入法兼容模式”可改用 Android 系统输入框，完成后填回原字段，保留原有表单校验；密码不写入兼容模式的缓存。vivo/iQOO 的 Android 16 及以上设备默认开启，也可手动关闭。普通 WebView 路径继续接收输入法边距更新，不再返回 `WindowInsets.CONSUMED`，参考 [Android WebView 官方说明](https://developer.android.com/develop/ui/views/layout/webapps/understand-window-insets)。

生活页钱包余额旁新增“充值”：直接打开支付宝中的趣智校园充值页，无需先打开趣智校园 App；需安装支付宝。支付页的学校与账号由支付宝小程序确认，可能需要首次登录或关联，请在付款前核对。本应用不传入账号密码、loginCode 或伪造小程序登录态，也不自动创建/确认支付。

返回本 App 后仅重新查询官方钱包余额；返回、取消支付或查询成功都不等于充值成功。未查到账时保留原余额，可手动刷新。入口的小程序 ID 和页面路径参考 [JUWPHub 的公开充值入口](https://github.com/Inonvation/JUWPHub/blob/cd5ac2863d92d08d2c2b873f28251ac272cd46f5/app/src/main/java/edu/jxslu/schedule/ui/qzxy/QzxyRecharge.kt)；未复制其实现或使用厂商 mPaaS 密钥/证书。

本轮仅修改源码，未构建、未测试、未登录或执行任何充值；该学校的账号对应关系及实际到账由用户验收。

## 用水账单与自定义背景

用水状态与设备面板显示官方设备配置 `withholdMoney`（单位为元）的预扣金额，未返回则明确标记。结算上传成功后弹窗显示设备名和设备类型，可直接确认。实际扣费明细可在「生活 → 消费账单」按月查看。

账单提示必须点击“确认”才能关闭，退出 App 后也会保留；确认只表示已查看，不再扣费。已记账但设备清理未确认时，仍保留原用水恢复记录，并在账单中提示。未确认账单前不新开订单。

“我的 → 设置 → 背景”支持系统图片选择器选择 JPG/PNG/WebP 背景、恢复默认；图片最多 8 MB、单边最多 8192 像素、总像素不超过 1600 万，仅复制到独立的应用私有目录，不上传、不写入学习数据备份。背景淡化显示，并随夜间模式调整可见度。

本轮同样未构建、未测试、未连接设备或执行实际消费。

## 刷新、查询与缓存清理

在主页面顶部下拉，达到提示距离后松手执行一次手动同步。同步指示器与右上角按钮会持续显示忙碌状态，直到后台同步结束；过程中不会重复启动同一轮同步。生活页下拉更新钱包余额。首页下拉会更新已连接账号的教务课表和学习通作业。课表页下拉只查询教务系统，按学校当前学期一次更新整学期课程。它保留手动添加的课，首次成功后替换旧版学习通课表。Android 前台和后台的作业同步共用 12 小时间隔。学习通要求验证码时，待办页会提供官方验证入口，完成验证后可手动重试。

消费账单的月份改变后自动查询，保留“重新查询”用于重试；快速切换时只展示当前月份的结果，旧月份迟到的响应不覆盖当前列表。

只追踪部分课程时复用最多六小时的本机加密课程目录，未缓存、过期或所选课程缺失时重新获取。有效课程签名入口只在进程内复用十分钟，最多 64 项，失效后重新获取。Android 和 iOS 的作业同步最多同时发出两个请求，并保留同域名的请求间隔。学习通返回 HTTP 429 时，暂停请求并保留本机作业。仍有课程或截止时间未确认时，待办页会显示原因。

App 回到前台时自动检查缓存，持续使用时每三十分钟再次检查。网页资源缓存达到每日清理时间或应用缓存目录超过 32 MB 时，通过 [WebView 官方资源缓存 API](https://developer.android.com/reference/android/webkit/WebView#clearCache(boolean)) 清理；超过一天的残留背景导入临时文件、未使用的旧背景副本也会自动移除。课程签名缓存按过期时间和数量上限回收。清理不删除登录 Cookie、Keystore 凭据、离线学习数据、设备绑定、未确认账单或正在使用的背景，不遍历删除 Chromium 的活动文件。

本轮仅修改源码和文档，未构建、未测试；滑动体验、同步耗时和实际存储回收量由用户验收。

## 软件名、版本与图标

统一修改 `buildSrc/src/main/java/cn/stbu/config/AppConfig.java`：

| 配置 | 用途 |
| --- | --- |
| `APP_NAME` | 桌面名称、App 内名称和 APK 文件名前缀 |
| `VERSION_NAME` | 安装包和“关于”中的显示版本 |
| `VERSION_CODE` | Android 内部版本号，发布新版本时递增 |
| `ICON_RESOURCE` | 桌面图标资源，默认 `@mipmap/ic_launcher` |

自定义图标放到 `app/src/main/res/drawable-nodpi/`，例如 `my_logo.png`；将 `ICON_RESOURCE` 改成 `@drawable/my_logo`。文件名使用小写字母、数字和下划线，配置值不包含扩展名或磁盘路径。默认图标继续使用现有自适应资源。

修改后由你重新加载 Gradle 并构建，安装后的名称、版本与图标才会生效。配置类只参与构建，不额外引入 Android 运行依赖。版本号规则见 [Android 官方说明](https://developer.android.com/studio/publish/versioning)。

## IDEA 打包与输出

用根目录的 `settings.gradle.kts` 导入 Gradle 工程，使用 JDK 17，并安装兼容的 Android / Android Design Tools 插件。不要把整个 `app/src` 手动标成普通 JVM 源码根，也不要给 Android 模块添加 `kotlin("jvm")` 插件。

| 运行配置 | 打包方式 | 输出 |
| --- | --- | --- |
| Android Debug | `:app:publishDebug` | `app/Debug/`（APK） |
| Android Release | `:app:publishRelease` | `app/Release/`（APK） |
| iOS Debug | Xcode 模拟器构建 | `ios/Debug/StbuHelper.app` |
| iOS Release | Xcode 真机归档和签名导出 | `ios/Release/StbuHelper.xcarchive` 与 `StbuHelper.ipa` |

Android 打包不需要连接手机。`app` 配置用于安装并运行应用，需要已连接的 Android 手机或模拟器。中间产物仍在 `app/build`。

Android Release 签名使用 `keystore.properties`。从 `keystore.properties.example` 复制并填写自己的密钥路径、别名和密码。签名文件、真实属性和 APK 已排除出 Git。

在装有完整 Xcode、iOS SDK 和 XcodeGen 的 Mac 上打开同一仓库，启用 IDEA 的 Shell Script 插件，即可从运行配置中选择 iOS Debug 或 iOS Release。脚本会按需生成 Xcode 工程。Windows 上可以看到这些配置，iOS 构建需在 Mac 执行。

iOS Release 默认按 App Store Connect 方式签名并导出到本地。请在该运行配置的环境变量中设置 `STBU_IOS_TEAM` 为你的 Apple 开发团队 ID，并在 Xcode 配好主应用、组件目标及共用 App Group 的签名。如需让 Xcode 更新签名配置，再设置 `STBU_IOS_ALLOW_PROVISIONING_UPDATES=1`。

若要用 iPhone 调试，可在 iOS Debug 配置里设置 `STBU_IOS_DEBUG_DEVICE=1` 及 `STBU_IOS_TEAM`，将签名后的 Debug IPA 和归档输出到 `ios/Debug/Device/`。默认的模拟器 `.app` 用于 iOS Simulator。如需自定义导出方式，可将 `STBU_IOS_EXPORT_OPTIONS` 指向 Xcode 导出的本机 plist，例如 `ExportOptions.local.plist`（相对于 `ios/`）。输出为本地文件，提交发布由你执行。

## GitHub Actions 打包 Android APK

将 `.github/workflows/android-apk.yml` 提交到 GitHub 仓库默认分支后，打开 **Actions → Android APK → Run workflow**。选择 `debug` 或 `release`，构建完成后在这次运行的 **Artifacts** 下载 `StbuHelper-debug.apk` 或 `StbuHelper-release.apk`。产物保留 7 天。

Debug 使用调试签名，可以直接构建。Release 使用现有发布密钥，需要先在仓库的 **Settings → Secrets and variables → Actions** 添加四个 Repository secrets。

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `storeFile` 指向的密钥文件，编码为 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` |
| `ANDROID_KEY_ALIAS` | `keyAlias` |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

在项目根目录用 PowerShell 将已有密钥文件转为 Base64 并复制到剪贴板。请将路径换成你的实际 `storeFile`。

```powershell
$keystorePath = (Resolve-Path -LiteralPath 'signing/stbu-helper-release.jks').ProviderPath
Set-Clipboard -Value ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath)))
```

把剪贴板内容粘贴到 `ANDROID_KEYSTORE_BASE64`。密钥文件和真实的 `keystore.properties` 继续保留在本机。要覆盖已安装的 Release 版本，需沿用原发布签名。[GitHub Actions Secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)

## 登录与本机数据

默认在 App 表单输入学校学号或学习通手机号/超星号，图片验证码和学校短信验证也在表单中处理；后台使用隔离的官方 WebView 保留官方认证逻辑。微信、QQ 或其他验证需要用户主动点击“打开官方网页”，不会自动跳转。

账号密码可选择经 Android Keystore AES-GCM 加密保存在本机；有效会话分别持久化，未失效时复用。趣智校园使用独立账号，密码仅用于登录，其令牌另行加密保存。Keystore 不可用时不降级为软件密钥。远程官方网页没有调用本机业务的 JavaScript 桥。

课程追踪位于“学习 → 待办 → 课程追踪”，默认全部，也可只选择部分课程或全部取消；同步在发送作业请求前筛选，主页和通知采用相同选择。待办仅显示截止时间明确、未过期且未提交的作业，排除待批阅等状态。

提醒可在“我的”或待办页开启，Android 13+ 需允许通知；系统省电和后台调度可能延后提醒。学习通功能只读，不代签到、刷课或提交作业。

学习数据保存在应用私有目录，禁止云备份和设备迁移。导出备份不包含账号密码、会话 Cookie、趣智令牌或带签名的作业链接。网络失败保留旧数据，不冒充同步成功；用水未确认结束时保留恢复记录，不自行估算扣费。

## 开发约定

代理只修改源码、配置和必要文档。所有构建、APK 输出、签名、测试、预览、真实登录和蓝牙设备验收均由用户亲自进行，详见 [AGENTS.md](AGENTS.md) 和 [agent.md](agent.md)。本次清理未构建、未测试，未生成新 APK；不能把历史验证记录当作当前版本的验证结果。

开源来源与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
