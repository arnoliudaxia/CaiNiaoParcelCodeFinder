# 模型来源、恢复与运行配置

模型来自 PaddlePaddle 官方发布包；`models-manifest.json` 保存原始 URL、归档 SHA256、每个资产的 SHA256 与大小。三套均为 FP32 ONNX，使用各自识别 YAML 内的字典，不能混用。

| 模型 | assets 内相对目录 | CTC 类别数（含 blank） | ONNX + YAML 字节数 |
|---|---|---:|---:|
| PP-OCRv6_small（默认） | `models/` | 18710 | 31,191,354 |
| PP-OCRv6_tiny | `models/v6_tiny/` | 6906 | 6,299,683 |
| PP-OCRv5_mobile | `models/v5_mobile/` | 18385 | 21,510,548 |

在项目根目录执行（Python 3，无第三方包）：

```sh
python scripts/provision_models.py
# 自定义相对缓存；包含全部且校验通过的归档时无需再次下载
python scripts/provision_models.py --cache .model-cache
```

默认缓存基于脚本定位项目根目录；显式相对 `--cache` 基于当前工作目录。脚本校验整个归档与指定资产，只读取 manifest 指定的 tar 成员，不展开任意路径，并逐文件原子替换。中途失败需完成恢复后再构建。模型输出位于 `ppocr-sdk/src/main/assets/`，ONNX/YAML（包括大字典）及缓存不入 Git，JSON 清单和脚本入 Git。不重复打包 small。模型分发授权仍需按官方来源单独核实，根目录许可证不自动替代第三方模型许可证。

## 当前应用默认值与覆盖

| 模型 | thresh | box_thresh | unclip_ratio | max_candidates | 检测 resize |
|---|---:|---:|---:|---:|---|
| v6_small | 0.2 | 0.45 | 1.4 | 3000 | YAML 为 null，使用 SDK 默认 |
| v6_tiny | 0.2 | 0.4 | 1.4 | 3000 | 同上 |
| v5_mobile | 0.3 | 0.6 | 1.5 | 1000 | 显式 resize_long=960 |

Mobile 按上游 `ppocr/data/imaug/operators.py` 的 `resize_image_type2`：先把长边缩放为 960（允许放大），取整数后各维向上对齐 128；1920×1080 或 480×270 均得到 1024×640。此处只是来源引用，不依赖该 Python 文件。V6 使用 SDK 的 min=64/maxSideLimit=4000、32 对齐；静态后端保持固定输入 letterbox。

识别使用 BGR、高度48、`(x/255-.5)/.5` 归一化，动态批次按最长 ROI 补零（宽度上限3200），不强制 YAML 的宽320。检测保留 BGR、`1/255`、mean `[.485,.456,.406]`、std `[.229,.224,.225]`。未明确的选项用 SDK 默认，包括 dilation=false、scoreMode=fast、boxType=quad、识别阈值0。**不是完整官方推理 pipeline 等价声明。**

App 使用 CPU6，新设置 batch4，可选1/2/4/8。面板支持四项检测参数草稿，应用才生效；阈值 `[0,1]`、unclip `(0,10]`、候选数 `1..10000`。恢复默认只改草稿。切换模型恢复该模型默认检测值，切换 batch 保留覆盖，成功加载后保存；失败保留旧引擎。模型替换时短暂同时持有新旧引擎，注意峰值内存。正在推理/加载时不能重载。换模型不重新触发自动相册识别。

## 验证

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

以下为可选真机烟测，会向所连测试设备安装测试 APK；请显式选择测试设备并避免误操作日常设备：

```sh
./gradlew :ppocr-sdk:connectedDebugAndroidTest
```

SDK 包含模型切换、动态 ROI batch、检测 resize 与 benchmark 测试。`run_benchmark.sh` 是可选的 adb 辅助脚本，会清除设备 logcat 并运行测试；不属于普通构建过程。单元测试/SDK 烟测不能代替 UI 权限、持久化、命中自动停止、准确率和持续录像/相机测试，较大的 batch 不保证更快。
