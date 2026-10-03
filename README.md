# ThermalFusion

Experimental fixed-plane registration of a USB thermal camera and an Android rear camera, with a Python/OpenCV reference and an Android integration path.

这是一个进行中的公开项目。目标是固定支架、固定工作距离下的 PCB 热源辅助定位。当前已验证离线算法、Android 编译与软件逻辑测试；真实双模态标定靶、手机 USB 和测温都需要单独验证。融合不增加热像头的实际分辨率，也不生成或修改温度值。

## 当前状态

| 部分 | 状态 |
| --- | --- |
| Python/OpenCV 自动标记检测、RANSAC 配准和质量检查 | 已实现，85 项离线测试通过 |
| 纯红外、可见光透明叠加、可见光边缘 | 已实现离线参考 |
| 几何配置绑定、不同步/过期拒绝、原始值保护 | 已实现离线参考 |
| Android 标准相册保存和应用骨架 | 默认版与私有 SDK 版均已编译；49 项核心测试、lint 通过 |
| 原厂 demo 存储分析 | 已查明目标版本路径，见下方文档 |
| 私有 SDK 红外预览 | USB2 原创适配、ARM64/API21+ 测试 APK 已构建；尚未手机实测 |
| Camera2 后摄选择与双路独立预览 | 当前开发分支已实现源码；时钟/几何诊断、生命周期门控，尚未手机实测 |
| 手机硬件采集、真实标定、定位精度和测温 | 尚未验证 |

## 运行离线核心

需要 Python 3.12+。

```sh
python -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
.venv/bin/python -m pytest -q
.venv/bin/python -m thermal_fusion.demo --output outputs
```

Windows 使用 `.venv\Scripts\python.exe`。示例图全部是合成数据，生成图会标注 SYNTHETIC ONLY。合成独立噪声帧的低误差不代表真实硬件已达到同样精度。

## 标准保存位置

Android 实现采用以下约定：

- 用户主动保存的图片：共享相册 `Pictures/ThermalFusion`，Android 10+ 通过 MediaStore 发布，并返回可打开/分享的内容 URI
- 原始帧、温度和诊断数据：只在有真实 SDK 数据时提供明确导出，由用户通过系统文件选择器选择目标；不把私有目录当成用户能直接找到的保存位置
- 保存失败必须报告，写到一半的内容要清理，不能提前提示保存成功

原厂 demo 的已查明路径和找不到照片的原因见[存储分析](docs/STOCK_DEMO_STORAGE.md)。

## 关键限制

- 普通打印黑白标记不一定在热红外可见，真实靶结构必须先测试
- 单应性只对一个工作平面成立；高出 PCB 的元件存在视差，大基线可能明显错位
- 第一版不做复杂 3D 重建、自动测距或自动对焦
- 配置变化会拒绝旧标定，但软件不能自动发现支架被碰过而元数据仍未更新
- 原始热图/测温数据独立保存，叠加效果不能作为测温或小封装定位精度的证明

## 文档

- [离线参考说明](docs/REFERENCE_README.md)
- [Android 项目与构建](android/README.md)
- [私有 SDK 适配状态和边界](docs/SDK_INTEGRATION_STATUS.md)
- [私有红外版本构建与手机操作](android/GUIDE_IR_BUILD.md)
- [Camera2 / 双路独立预览源码阶段](android/DUAL_PREVIEW.md)
- [完整融合接入与验收计划](docs/ANDROID_INTEGRATION.md)
- [真实采集协议](docs/CAPTURE_PROTOCOL.md)
- [原厂 demo 保存位置](docs/STOCK_DEMO_STORAGE.md)
- [验证范围](VALIDATION.txt)
- [第三方依赖与厂商文件边界](THIRD_PARTY.md)

## 厂商 SDK 和隐私

不要向本公开仓库上传厂商 SDK/APK、授权文件、序列号、凭据、私人热图或手机照片。厂商文件仅放在本地被忽略的 `private_inputs/` 或 `vendor_sdk/`，依据厂商许可使用。私有构建说明列出了所需本地文件，仓库本身不重新分发。

公开源码尚未指定开源许可证；第三方组件仍受各自许可证约束。
