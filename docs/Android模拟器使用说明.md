# Android 模拟器环境

开发验证使用 Android 16（API 36）ARM64 基础虚拟设备，名称为 `RSS_Android_16`，使用 Pixel 6 硬件配置，不依赖 Play 商店。

## 启动

可以用 Android Studio 的 Device Manager 创建并启动同规格设备，也可以使用 Android SDK 命令行工具：

```sh
emulator -avd RSS_Android_16
```

已有窗口运行时直接使用，避免重复启动。关闭模拟器窗口即可退出。

## 安装 APK

在终端执行：

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 验证结果

- macOS Hypervisor.Framework 加速检查通过。
- Android 启动完成，版本 16，架构 arm64-v8a。
- ADB 连接、返回桌面操作、截图成功。
- 虚拟设备请求 example.com 获得 HTTP 200；RSS App 已在此模拟器成功读取 [IT之家 HTTPS 订阅源](https://www.ithome.com/rss/)，实际用例见《RSS阅读器开发验证.md》。

## 环境位置与依赖

最简环境需要 JDK 17、Android SDK Platform API 37、Build Tools 36.0.0、Platform Tools、Emulator，以及 Android 16（API 36）ARM64 系统镜像。SDK 与虚拟设备合计约 4 GB，使用及快照保存后会增长。源码目录自带 Gradle 9.6.0 Wrapper。
