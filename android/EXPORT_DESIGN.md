# 未来真实采集导出：SAF 设计，不是已实现功能

当前没有真实 raw 或温度数据，不提供伪造 raw/温度的“导出”按钮。保存测试 PNG 与导出测温数据必须是独立流程。

## 数据准备与授权

仅在厂商 SDK 的数据格式、单位、有效性、使用/分发限制、缓冲区所有权经过核实后，才能生成真实快照。预览 RGB/伪彩图不得反算温度。缺失的 raw、温度、时间戳或 NUC 状态必须明确为不可用，不用 0、猜测值或合成值填充。

导出前显示本次将包含哪些真实项目，由用户明确选择。默认不包含设备序列号、定位或其他额外身份信息。采集缓冲区先复制或正确持有，防止 SDK 复用内存。为每项记录实际尺寸、类型、字节序、stride、单位/比例、无效像素规则、设备/主机时间及其时钟来源。温度转换只能走已验证的官方 SDK 路径。

## 系统“另存为”

1. 用户点击“导出真实采集”，选择 ZIP（`application/zip`）或仅元数据 JSON（`application/json`）
2. 原生 `Intent.ACTION_CREATE_DOCUMENT` + `Intent.CATEGORY_OPENABLE` + MIME + `Intent.EXTRA_TITLE` 打开系统文件选择器，让用户选择目标；不申请全盘访问，不假定下载目录，也不默认申请持久访问
3. `RESULT_CANCELED` 或缺少 URI：不创建导出内容；返回未导出状态
4. 仅向本次返回的 URI 写入。后台使用 try-with-resources；输出流为 null、空间不足、源数据失效或取消时失败关闭。仅在成功关闭输出后显示成功及实际 URI
5. SAF 不保证像 MediaStore 一样有原子 pending/publication。若失败，按提供者能力尝试 `DocumentsContract.deleteDocument` 清除本次新建文件；如果不支持或失败，明确提示残留 URI，让用户处理。不得因失败退回偷偷写其他目录

`ACTION_CREATE_DOCUMENT` 不覆盖已存在的文档，系统可能调整文件名；结果必须使用实际 URI。导出数据大时可先写 app-private 临时 ZIP，核验内容后再请求用户目的地，随后流式复制并删除临时文件，仍须说明目标提供者复制中断可能留下不完整文档。

## ZIP 内容草案

- `metadata.json`：schema version、来源、采集状态、字段可用性与文件清单/校验和
- `thermal/raw.bin`：仅实际获得的原始帧，解释由明确的格式元数据决定
- `thermal/temperature.f32`：仅 SDK 提供且验证为摄氏度的真实矩阵
- `thermal/invalid-mask.bin`：仅确有无效像素语义时提供
- `images/thermal-preview.png`、`images/visible.png`、`images/fused-display.png`：仅用户选择且存在的各图，区分原始预览与显示融合
- `calibration.json`：仅存在的已验证配置和几何指纹，不把未验证参数称为标定结果

导出 UI 显示 raw/temperature 是否可用和原因；文件清单与真实内容严格一致。测试夹具必须另用 `SYNTHETIC_ONLY` 名称和元数据，不能作为实测报告。

参考：[Android 官方 SAF 创建文档指南](https://developer.android.com/training/data-storage/shared/documents-files)。
