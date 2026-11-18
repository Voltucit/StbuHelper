# 开源来源与修改

## LinYu

- 原项目：https://github.com/yehu-imei/linyu
- 许可：MIT，完整文本见 `app/src`。
- 本项目复用：BleController、BleFrame、DeviceQueryState、BleShowerController、BleSignBuilder、BleSettlementCleanup、MacFormat、KlcxkjSigner、KlcxkjCrypto、BleModels，以及 DeviceQueryParserTest / MacFormatTest。
- 适配：使用本项目的官方 HTTPS 客户端、Keystore 会话库和活跃订单存储；删去 UI/日志依赖；设备连接地址与服务端台账 MAC 分开；写入开阀费率前先持久化恢复记录。数据模型只保留协议字段。
- 未复用原项目界面、主题、遥测或自动更新。设备实际兼容性不因复用源代码而得到验证。

## 航院通

https://cnb.cool/Hobson2233/hangyuantong （用户提供源码 ZIP，MIT）。参考产品功能与手机直连架构。本地页面和学校提取器独立实现，没有使用原项目的遥测、服务器或郑航接口。

## AiSchedule

https://github.com/imshixin/AiSchedule/blob/master/四川工商学院-为途.js 。研究其中的历史为途课表字段与时间格式；未复制其实现代码，当前学校页面未实测。

## xuexitong-cli

https://github.com/DanMo661/xuexitong-cli （MIT）。研究公开客户端中的逐周课表接口、学期及节次字段、课程和作业只读查询流程，按本项目的 WebView 会话和 Kotlin 数据层适配。保留原许可于 `app/src`。不引入其 MCP 服务，不代提交作业或签到。

## 正方查询接口参考

https://github.com/openschoolcn/zfn_api 和 https://github.com/drfccv/ZFJW-backend 。研究公开的成绩/考试请求路径、分页字段和 JSON 格式，没有复制其后端实现，也不使用外部转发服务。实际学校接口由用户验证。

## AndroidX WebKit / Kotlin Coroutines / Gson / Jsoup

使用各项目发布的依赖，遵循其随包许可。发行时保留构建产物中的依赖许可与本文件。


## ZXing Android Embedded / ZXing Core

- 项目：https://github.com/journeyapps/zxing-android-embedded ，依赖 `com.journeyapps:zxing-android-embedded:4.3.0`；ZXing Core 由其传递依赖提供。
- Apache License 2.0；Copyright (C) 2012–2022 ZXing authors, Journey Mobile。完整许可随 APK 放在 `app/src/main/assets/licenses/ZXing-Apache-2.0.txt`，同时保留依赖构建产物内的许可和声明。
- 用于离线相机扫码，通过 IntentIntegrator / CaptureActivity 接入现有 Activity；不保存图像，不接入 Google 服务或开发者服务器。
- 趣智校园设备码格式参考 LinYu 的 `QrScanActivity.parseSnCode`：https://github.com/yehu-imei/linyu/blob/master/app/src/main/java/com/hualala/linyu/QrScanActivity.kt 。保留 LinYu MIT 许可，未复用其相机界面。

## 趣智校园充值入口资料

https://github.com/Inonvation/JUWPHub/blob/cd5ac2863d92d08d2c2b873f28251ac272cd46f5/app/src/main/java/edu/jxslu/schedule/ui/qzxy/QzxyRecharge.kt 。仅研究其中公开的支付宝小程序标识与充值页路径；未复用其实现代码或厂商证书。本项目使用 Android Intent / Uri 构造固定入口，支付在支付宝内由用户确认。

## SwiftSoup

[SwiftSoup](https://github.com/scinfu/SwiftSoup)（MIT）用于 iOS 解析官方作业和教务页面。Xcode 通过 Swift Package Manager 获取源码和许可。XcodeGen 只生成本地工程，不随应用运行。
