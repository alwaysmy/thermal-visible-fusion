# ThermalFusion Android：标准图片保存原型

本目录是可构建的原生 Java Android 工程。当前 UI 只显示**合成彩色测试图**，保存后的 PNG 内也有不可误认的 `SYNTHETIC TEST PATTERN` 标识。没有连接厂商 SDK、USB 热像仪或手机相机，没有生成真实原始热帧或温度，也没有实现实时融合。不要把此版当成测温软件。

## 保存到哪里

- **Android 10 / API 29 及以上**：用 `MediaStore.Images` 在主共享存储创建图片，目录提示为 `Pictures/ThermalFusion/`。先设置 `IS_PENDING=1`，成功写入、flush、关闭流后，更新为 `IS_PENDING=0`。UI 显示系统返回的实际 `content://…` URI；不伪造物理 `/sdcard` 路径
- **Android 6–9 / API 23–28**：仅在保存时请求 `WRITE_EXTERNAL_STORAGE`。在系统 `Environment.DIRECTORY_PICTURES` 下建立 `ThermalFusion`，完整写入同目录隐藏临时文件后重命名为 PNG，再调用 MediaScanner。UI 显示实际完整文件路径。拒绝权限不会写文件
- 文件名含 `ThermalFusion_TEST_ONLY_`、时间和 UUID，避免覆盖旧照片
- 插入失败、null 输出流、编码返回 false、写入/flush/close/发布异常，或提交前取消，都会清理当前未完成项。清理失败会把定位信息作为警告显示，不会假报成功
- “取消”在发布提交点之前有效；发布开始之后，最终结果会按保存成功或失败报告。重复点击保存被禁用。离开/旋转销毁页面会取消尚未发布的事务
- Android 29+ 不申请存储权限；没有 `READ_MEDIA_*`、`MANAGE_EXTERNAL_STORAGE`、`requestLegacyExternalStorage`、相机、网络或 USB 权限

系统进程被强杀、断电等无法执行 Java 清理的情况尚未实现持久化恢复：可能留下等待系统处理的 pending MediaStore 项或旧设备隐藏临时文件。当前事务测试不等同于设备断电测试。相册应用是否立即展示图片也取决于系统媒体索引。

## 构建

要求：JDK 17（或支持该 Gradle/AGP 的更高版本）、已安装且许可已由使用者接受的 Android SDK platform 35 和 Build Tools 35.0.0。首次 Gradle 构建需要网络以下载官方依赖。工程关闭 SDK 自动下载，不运行 `sdkmanager --licenses`。

```sh
cd android
export ANDROID_HOME=/your/existing/android/sdk
# 也可在不提交的 local.properties 中写 sdk.dir=...
./gradlew --no-daemon :storage-core:check :app:assembleDebug :app:lintDebug
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。这是 Gradle 的调试签名，不含任何厂商签名配置或私钥。Windows 使用 `gradlew.bat`。

固定工具版本：AGP 8.9.2、Gradle 8.11.1、compile/target SDK 35、min SDK 23。Gradle distribution SHA-256 固定于 wrapper properties，wrapper JAR 也由静态测试核对官方发布校验值。版本兼容性见 [Android AGP 8.9 官方说明](https://developer.android.com/build/releases/agp-8-9-0-release-notes)。

### 不需要 Android SDK 的本地测试

```sh
cd android
bash scripts/test-core.sh
```

需要 JDK 的 `javac`、`java` 和 Python 3。测试运行 **实际被 Android 使用的** `GalleryTransaction` / `SaveCancellation` / `StoragePolicy`，包含 23 项成功、失败、null、取消竞态、清理异常和版本边界用例。附加静态检查核对 manifest 权限和实际 Android 适配器的关键调用；这不替代 ContentResolver 或手机实测。

### GitHub Actions 要求

在仓库根目录工作流使用 `ubuntu-latest`、`actions/setup-java`（Temurin 17）和预装 Android SDK。先断言 `$ANDROID_HOME/platforms/android-35/android.jar` 与 `$ANDROID_HOME/build-tools/35.0.0/aapt2` 存在；缺失则清楚失败，不自动接受新 SDK 许可。依次执行上面测试和 Gradle 命令。建议上传 APK、`app/build/reports/lint-results-debug.html`。

## 代码边界

- `app/.../MainActivity.java`：中文操作提示、合成图、后台保存、旧系统权限和精确结果
- `app/.../storage/ImageSaver.java`：真正的 Android MediaStore / legacy 文件适配器
- `storage-core/`：无 Android 依赖的生产事务与可执行测试
- `app/.../sdk/ThermalSource.java`：SDK 中立帧契约，缓冲区防御性复制
- `UnavailableThermalSource`：明确无硬件，绝不把测试图回调成真实帧
- `UsbPermissionCoordinator`：未接到 UI 的独立授权入口；包名限定 + immutable PendingIntent、API 33+ 私有动态 receiver、重新核对选定设备与 `UsbManager.hasPermission`、结束时取消/注销。授权回调不依赖可变 Intent extras；不调用厂商权限 helper，等待实机验证后再接入
- [EXPORT_DESIGN.md](EXPORT_DESIGN.md)：未来真实 raw/温度导出的 SAF 设计，**尚未实现导出功能**

厂商 AAR/SO、文档、原厂源码、demo 签名配置均不得放到公共仓库。接口层是本项目原创代码；当前构建不读取私有 SDK。未来适配应保留无厂商依赖的默认构建，先核实分发许可和真实硬件行为。

## 验证边界

即使 CI 的 APK 编译/lint 通过，也不表示已经安装到手机或通过 MediaStore provider、USB、相机、温度、标定、融合测试。手机验收应覆盖 API 23/28 的允许/拒绝/“不再询问”，API 29/35+ 的零存储权限保存，重复点击、取消、旋转、后台/重开、磁盘满、存储失联，检查实际保存图内标识、显示定位和相册可见性。

## 官方参考

- [Android：访问共享媒体](https://developer.android.com/training/data-storage/shared/media)
- [Android：通过 Storage Access Framework 创建文档](https://developer.android.com/training/data-storage/shared/documents-files)
- [Gradle wrapper 校验](https://docs.gradle.org/8.11.1/userguide/gradle_wrapper.html#sec:verification)

Gradle wrapper 是第三方工具，见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
