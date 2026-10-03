# 原厂 demo 的照片保存位置

本结论来自对用户提供的原厂 APK 的静态检查，未在手机上执行。它适用于包名 `com.guide.general`、版本 `V1.1.0.20250711`（minSdk21、targetSdk34）。其他版本或重新打包的 APK 可能不同。

## 路径

Android 10 及更新版本，应用使用 `Context.getExternalFilesDir(null)` 作为基础目录，再附加 `AGM` 子目录。典型主用户设备上为：

- 照片：`/storage/emulated/0/Android/data/com.guide.general/files/AGM/SourceImage/IMG_yyyy.MM.dd_HHmmss.jpg`
- 视频：`/storage/emulated/0/Android/data/com.guide.general/files/AGM/SourceVideo/VID_yyyy.MM.dd_HHmmss.mp4`
- 原始记录：同一 `AGM` 下的 `Y16/<epochMillis>.y16`、`Y8/<epochMillis>.y8` 和关联文本

Android 9 及更早版本，基础目录换成系统报告的主外部存储根目录，通常是 `/storage/emulated/0`，所以照片通常位于 `AGM/SourceImage`，视频位于 `AGM/SourceVideo`。

`/storage/emulated/0` 是常见路径，不保证每台设备、用户配置或存储卷都相同。目录应以运行设备的系统返回值为准。

## 为什么相册中可能找不到

APK 在 JPEG 保存成功后调用媒体扫描，但 Android 10+ 的目的地仍属于应用专属 `Android/data` 目录。媒体扫描不等于把文件移动到共享相册；现代 Android 的访问限制也可能使普通文件管理器无法直接打开该目录。

先不要卸载原厂 app：Android 会在卸载时清理应用专属存储。APK 的内置相册和单图查看中确实存在系统分享入口（ACTION_SEND / SEND_MULTIPLE，经 FileProvider 提供文件）。优先在原厂应用内找到照片后使用分享导出；无法直接进入目录不代表文件一定不存在。这里未验证用户手机上的具体界面和文件管理器行为。

## 静态证据

- `SDCardUtils.Companion.getInnerSDCardPath()`：以 API29 为界，选择应用专属目录或外部存储根目录
- `AppFilePathManager.Companion.getAppSafeDirectory()`：附加 `AGM` 和数据类别目录
- `RealTimeVideoPresenterImpl.startTakePic()`、`startVideo()`：使用应用安全目录分支
- `startRecY16()`：构造 Y16/Y8 原始记录路径

不在本仓库重新分发原厂 APK、反编译内容、SDK 或文档。

## ThermalFusion 的约定

用户主动保存的图片进入共享媒体集合 `Pictures/ThermalFusion`。Android10+ 以 MediaStore 的 `RELATIVE_PATH` 和 `IS_PENDING` 实现写入完成后发布；写入失败撤销未完成条目。完成后给用户可打开/分享的内容 URI，而不是只显示一个“保存成功”。

原始热帧和温度矩阵不伪装成普通图片，也不从伪彩图倒算。取得真实 SDK 数据后，使用明确的导出操作和系统文件选择器保存诊断包。

## Android 官方依据

- [App-specific files](https://developer.android.com/training/data-storage/app-specific)：应用专属目录的访问和卸载清理行为
- [Shared media](https://developer.android.com/training/data-storage/shared/media)：共享媒体集合、应用自产图片和 MediaStore
- [Documents and other files](https://developer.android.com/training/data-storage/shared/documents-files)：系统文件选择器和用户选择的文档目的地
