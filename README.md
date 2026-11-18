# ZCode App —— 手机端轻客户端（安卓 APK）

ZCode 电脑端会显示一个配对二维码（内容形如
`https://zcode.z.ai/remote/v4?sid=...&mid=...&name=PC-JACK`），
本 App **自己扫码**，扫到后直接在 App 内打开远程控制页面——
不再经手机浏览器，误关浏览器/划掉标签都不影响连接。

## 成品

- `ZCodeApp.apk` —— 约 250 KB（含 ZXing 扫码库），自签名 APK
- 包名 `com.jack.zcodeapp`，最低 Android 8.0（API 26），target API 36
- 已在本地模拟器（Medium_Phone_API_36.1）实测：主界面、扫码页、登录流程、返回导航均无崩溃

## 功能

| 特性 | 说明 |
|---|---|
| App 内扫码 | 打开相机实时识别二维码（ZXing），带手电筒开关，识别成功震动提示 |
| 首屏引导 | 未连接时首屏提供 扫码 / 粘贴链接 / 先去登录 三个入口，进入会话页后页面完全干净 |
| 通知栏扫码/退出 | 常驻通知带「扫码」「退出」两个按钮，随时可重新配对或彻底退出 |
| 自动记住会话 | 扫过的配对网址保存在本地，下次打开 App 直接连上次的会话 |
| 粘贴链接连接 | 配合电脑端「复制链接」：链接发到手机 → 复制 → App 首屏粘贴即连 |
| 系统打开方式 | 相机/微信扫二维码后可选「ZCode App」打开（仅 /remote/* 配对链接会被记住） |
| 独立窗口 | 桌面图标「ZCode App」，全屏 WebView 打开远程控制页 |
| 前台保活 | 通知栏常驻"连接保持中"，后台/锁屏时安卓不轻易杀掉连接 |
| 返回键 | 网页内后退；到首页再按返回=退到后台（不退出、不断连） |
| 附件上传 | 支持页面里的 ＋ 按钮调起系统文件选择器 |
| 断线恢复 | 加载失败显示"重新连接 / 重新扫码"两个按钮 |
| 屏幕常亮 | App 在前台时屏幕不熄灭，方便盯任务 |

## 使用流程

1. 电脑端 ZCode 显示二维码；
2. **首次使用先登录**：App 是独立浏览器沙盒，不共享手机浏览器的 z.ai 登录态。
   首屏点「先去登录」→ 选 "Continue with BigModel" → 手机号+验证码登录；
   Cookie 会持久化，之后一般不再需要登录；
3. 点「扫码」扫电脑上**当前显示**的二维码（配对码会轮换，旧码会提示
   "Mobile Connection Invalid"，重新扫即可）→ 震动 → 进入任务会话页；
4. 若登录后落在官网其他页面：底部有常驻回跳栏「不在远程控制页？回到远程控制」，
   一键返回；App 也会在登录完成后自动跳回（每次落点只回跳一次，不会死循环）；
5. 悬浮「扫码」按钮：重新扫码 / 重新加载上次连接；
6. 也支持系统打开方式：系统相机/微信扫二维码后选择「ZCode App」打开
   （只有 zcode.z.ai/remote/* 配对链接会被记为会话）。

> 已在本地模拟器（Medium_Phone_API_36.1）全流程验证：首启引导、登录页渲染、
> 配对链接加载、自动回跳（单次、无死循环）、回跳栏显隐逻辑，全程 0 崩溃。

## 手机上安装

1. 手机和电脑连同一个 Wi-Fi；
2. 手机浏览器打开：`http://192.168.1.6:8899/ZCodeApp.apk`（电脑上已开着下载服务），
   或者用微信/QQ 把 `ZCodeApp.apk` 传到手机；
3. 点开下载好的 APK → 允许"安装未知来源应用" → 安装（已装旧版直接覆盖升级）；
4. 首次启动授予相机权限，扫码即可。

> 下载服务启动命令：`python -m http.server 8899 --bind 0.0.0.0 --directory <本目录>`

## 重新构建

无需 Gradle，全部走本机 Android SDK（`libs/zxing-core.jar` 缺失时自动从 Maven 下载）：

```bash
bash build.sh
```

**签名密钥不入库**：签名密码放在本地 `signing.local`（已被 .gitignore 排除），格式：

```
KS_PASS=你的store密码
KEY_PASS=你的key密码
```

`zremote.keystore` 同样被排除在仓库外——**弄丢它就无法覆盖升级已安装的 App**，
请务必另行备份。

流程：aapt2 编译资源 → aapt2 link → javac（Android Studio 自带 JBR，-source 1.8，
-classpath zxing-core.jar）→ d8 打 dex（把 zxing-core.jar 一并 dex）→ jar 打包 →
zipalign → apksigner 签名。

## 文件结构

- `AndroidManifest.xml` —— 清单（前台服务 specialUse、相机权限、扫码页）
- `src/com/jack/zremote/MainActivity.java` —— WebView 壳（会话记忆、上传、重连、返回策略）
- `src/com/jack/zremote/ScanActivity.java` —— Camera2 + ZXing 扫码器
- `src/com/jack/zremote/KeepAliveService.java` —— 前台保活 + 唤醒锁
- `libs/zxing-core.jar` —— ZXing 二维码解码库 3.5.3
- `res/` —— 自适应图标（深底白 Z 矢量）、主题色
- `build.sh` —— 一键构建脚本

