# 私有 Guide SDK 红外预览构建

这是可选、原创的 SDK 适配代码。默认 Gradle 构建不读取 SDK、无 SDK 运行时依赖。公开 CI 只构建默认版，**不得上传此私有版本的 APK 到公开制品或 release**。SDK 分发权仍须由设备/SDK 提供方确认。

## 私有输入与构建

本地提供用户自己的 `lib_sdk_1.0.1-release.aar`，不要复制进 Git。可放在被忽略的 `android/vendor-libs/`，或直接用外部绝对路径。适配器固定了已检查版本的 SHA-256，避免混用同名但不同的 sample AAR：

`684789c86e992a10a6e2cf9373275038de261c5ebf8e53264b80566155463035`

```sh
cd android
./gradlew --no-daemon :storage-core:check :app:assembleDebug :app:lintDebug \
  -PguideAar=/your/private/path/lib_sdk_1.0.1-release.aar
```

JDK 17、官方 Android platform 35 / Build Tools 35.0.0；SDK 条款由操作者接受。当前私有 APK 包名 `org.thermalfusion.app.guide`，ARM64，minSdk21 / targetSdk35，此开发分支构建版本 `0.3.0-guide-dual-preview-source`（先前已交付的 `0.2.0-guide-local` APK 保持不变）。输出 `app/build/outputs/apk/debug/app-debug.apk`。默认测试版包名 `org.thermalfusion.app`，两者可以并存。默认/私有构建会使用同一输出路径，留存时请明确重命名，并核对 APK package/version 后再交付。

Kotlin 标准库和 MaterialComponents 只用于私有构建的 SDK 依赖兼容；应用 UI 本身仍是原生 Java/Android 控件。SDK 所附资源引用了 MaterialComponents 主题。无厂商签名私钥或原厂 APK 拆出依赖。

## 手机操作

1. 打开应用后点击“进入真实红外 SDK 预览”，测试图页和真机页严格分开
2. 确认诊断中的 Android 版本、ABI、内存页大小。型号不能替代实际页大小检查
3. 仅连接目标 USB2 热像模组，点击“授权 USB 并开始预览”，同意系统的此设备访问请求
4. 配置尺寸来自设备回调；收到有效 UYVY 后才显示图像。保存按钮在有效且新鲜的帧到达后才启用
5. 保存 PNG 到 `Pictures/ThermalFusion`，显示实际 URI/路径；可打开或通过系统选择器分享
6. 停止、拔出、页面进入后台会立即使旧帧失效，再异步串行停止/关闭 SDK。返回后手动开始

## 安全边界与未完成项目

- 目前仅核对 USB2 `04B4:F7F7` 路径。SDK 的 open API 自动选设备，因此本版要求只连接一个 USB 设备，在授权后、打开前后分别使用单次拓扑快照核对所选设备身份与权限；连接拓扑的 attach/detach 变化会中止会话。SDK 不公开实际打开的 UsbDevice 句柄身份，因此这些是有边界的防错检查，不能声称已独立验证其内部选择
- 该 AAR 的 USB3 依赖包不完整，本版不走 USB3；不从原厂 APK 提取依赖来补齐
- 所有 SDK open/start/stop/close 使用同一个串行执行器，跨页面重建也不并发操作 SDK 单例
- 回调返回前复制实际模式对应的 UYVY/Y16/参数数组；不保留可被 SDK 回收覆盖的引用。没有 UYVY 的回调模式拒绝显示，不生成替代“实时图”
- 图像转换采用已单测的 BT.601 limited-range UYVY→ARGB，仅为显示转换；真实颜色/量程仍需对照设备验证
- 帧时间与序号是应用主机接收值，**不是曝光时间戳，也不证明与手机相机同步**
- 超过 500ms 的旧帧清屏且不能保存；10 秒无有效帧后停止。NUC/快门有效性尚未验证，画面不能用于测温结论
- **不调用 measure 或其他温度转换**。当前设备/镜头编号映射未验证，不能把示例固定编号套用到 25mm 镜头。TEMP/Y16 数组即使存在也不解释为摄氏度
- `Os.sysconf(_SC_PAGESIZE)` 在加载任何 SDK 类前检查实际系统内存页。当前供应库只验证了 4KiB ELF 对齐：非 4096 字节模式一律阻止加载，需要厂商更新适配库。APK zipalign 与 native ELF 对齐是两件事，不能用 zipalign 宣称修复 16KiB 兼容性
- SDK 为 ARM64/ARMv7，本私有版本仅打包 ARM64。没有 x86 原生库，不能把 x86 模拟器成功启动当作 SDK 验证
- 最低 Android 5 / API21 是工程兼容目标；API21–22 安装时存储权限，23–28 运行时写入权限，29+ 自建媒体无需存储权限。旧系统和每款 USB 主机能力仍须实测
- 此分支另有 [Camera2 / 双路独立预览源码](DUAL_PREVIEW.md)，尚未手机实测；没有自动标定、图像融合、原始数据导出、已验证测温、视频保存或厂商兼容性认证

APK 编译成功只证明构建产物生成。还需在目标 Xiaomi 15 的实际 HyperOS/Android/页大小环境安装、授权并连接实机，核对尺寸、帧更新、插拔、后台/旋转、保存结果和热靶显示；未做这些验证时不能声称真机已通过。
