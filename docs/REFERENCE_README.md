# 固定工作平面的热像与可见光自动标定原型

这是可运行的 Python/OpenCV 离线参考核心，用来先验证“固定支架、固定距离、一个 PCB 工作平面”的配准和融合流程。它不含厂商 SDK，不是 Android 工程或 APK，尚未连接实际热像头，也没有证明真实标定靶在热红外中的可识别性。

## 已实现

- 自动检测候选双模态 ArUco 靶，按标记 ID 和角点顺序建立对应关系；热图允许正/反对比度
- RANSAC 单应性：可见光坐标映射到原始热像网格
- 独立采集帧检查，训练异常点过滤，验证点不允许再被当作异常点丢弃
- 至少 12 个点、足够覆盖面积、内点率、验证 P95/最大误差、几何和时效检查
- 纯红外、可见光透明叠加、可见光边缘三种离线显示输出
- 在有效标定区域内叠加，不在无对应内容处延展填充
- 原始 uint16 热帧和独立温度矩阵只做只读副本，融合不改数值、不创造温度或热像分辨率
- 绑定相机 ID、分辨率、裁剪、旋转、镜像、焦点、变焦、畸变配置、支架版本、镜头、垫圈和工作距离
- 不同步、过期、无效热帧、NUC/遮挡状态拒绝；合成标定禁止用于 live 模式
- 手工对应点接口是 `Correspondences`，未实现手机手点 UI

## 快速运行

需要 Python 3.12 或更高版本。建议使用独立虚拟环境。

```sh
python -m venv .venv
.venv/bin/python -m pip install -e '.[test]'
.venv/bin/python -m pytest -q
.venv/bin/python -m thermal_fusion.demo --output outputs
```

Windows 将 `.venv/bin/python` 改为 `.venv\Scripts\python.exe`。所需依赖由 `pyproject.toml` 固定版本；ZIP 不包含解释器、虚拟环境或厂商库。

`outputs/verification_metrics.json` 是这次实际运行的数值结果；`outputs/test-results.txt` 是测试日志。预览图均醒目标记 SYNTHETIC ONLY。生成的标定 JSON 只供检查/接入参考，当前没有未经验证的 JSON 导入器。

## 当前实测范围

这里的“测试通过”仅指离线软件测试。使用合成图分别施加平移、旋转、透视、模糊、噪声、反色；另用坐标测试注入异常点、聚集/共线/重复点、错误配置、不同步和多平面偏移。

第二张合成图使用独立噪声，其底层几何相同。它不是独立真实硬件证据。即使合成 P95 误差低于一个像素，也不能推断实际镜头、真实热靶、焦点、USB 时序、镜头畸变和温度精度已经通过。

## 必须知道的边界

1. 普通纸质打印标记未必有热红外对比度。需要在真实热帧里能同时清楚看到的双模态靶。候选方案先做可检测性实验，再确定结构和材料
2. 标定只覆盖一个平面。高元件和板面高度不同会产生视差。把叠加做得漂亮不能证明热源恰好落在某个小封装上
3. 未做全套相机内参/畸变标定。当前要求目标区域畸变小，或上游已用正确模型去畸变并绑定配置；误差超标就拒绝。后续须用真实图验证是否需要加上去畸变阶段
4. 不会从图像自动测距，也不会自动知道支架被碰过。配置变化能被拒绝；如果实际几何变了却仍报告旧配置，软件不能保证发现。应固定机械结构，每次使用前复核参考点
5. 默认阈值是实验起点，不是经过该硬件验证的规格。实际允许误差必须按热像素对应尺寸、最小封装和接受风险决定
6. 几何融合并不改善热像头实际 IFOV/光学分辨率、焦点、热扩散、发射率、反射温度或厂商测温算法
7. 目前没有 Android UI、USB 驱动、权限流程、GPU 实时优化、APK 安装测试或相机切换实测

详情见 `docs/ANDROID_INTEGRATION.md` 和 `docs/CAPTURE_PROTOCOL.md`。

## 文件说明

- `thermal_fusion/core.py`：核心数据契约、质量门槛、配准和融合
- `thermal_fusion/detection.py`：候选双模态标记检测
- `thermal_fusion/synthetic.py`：明确标识的合成测试图
- `thermal_fusion/demo.py`：复现验证和显示预览
- `tests/test_reference.py`：自动化测试
- `android_contract/FusionPorts.kt`：仅接口设计草案，未编译，不能直接构建 APK
- `outputs/`：本次离线验证产物

## 官方参考

- OpenCV 单应性、RANSAC、相机和畸变模型：https://docs.opencv.org/4.13.0/d9/d0c/group__calib3d.html
- OpenCV 几何图像变换：https://docs.opencv.org/4.13.0/da/d54/group__imgproc__transform.html
- OpenCV ArUco 标记：https://docs.opencv.org/4.x/d5/dae/tutorial_aruco_detection.html

只采用传统几何和图像算法。依赖由各项目许可约束；本包没有包含或再发布厂商 SDK/官方 APK。
