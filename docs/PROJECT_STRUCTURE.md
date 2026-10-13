# 项目结构

```text
app/                       Compose UI、相机/媒体调度、目标查找与设置
  src/main/                AndroidManifest、Kotlin 源码与资源
  src/test/                目标匹配、设置、队列、缩放等 JVM 单元测试
ppocr-sdk/                 ONNX Runtime + OpenCV 检测/识别 SDK
  src/main/assets/models/  本地模型资产（忽略；由脚本恢复）
  src/androidTest/         原生模型烟测及性能测试
scripts/                   下载脚本、带哈希的模型清单、模型说明
gradle/                    版本目录与 Gradle Wrapper（含 JAR）
docs/                      结构与派生说明
```

入口与重点：
- `app/.../OCRApplication.kt`：CPU 引擎所有权、安全替换及偏好持久化。
- `app/.../OCRSettings.kt` / `DetectorDraft.kt`：模型默认配置与参数验证。
- `app/.../CameraTarget.kt`：四位 ASCII 目标验证及匹配逻辑。
- `app/.../ui/viewmodel/OCRViewModel.kt`：媒体任务、相机命中自动停止、计时/结果状态。
- `app/.../ui/component/CameraTargetInput.kt` / `ImagePreview.kt`：目标输入和结果绘制。
- `ppocr-sdk/.../engine/`、`preprocess/`、`postprocess/`：推理流水线。

这里的 `app/...` 指 `app/src/main/java/com/paddle/ocr/demo`，`ppocr-sdk/...` 指 `ppocr-sdk/src/main/java/com/paddle/ocr`。包名刻意保留，不进行架构迁移。

`local.properties`、所有 `build/`、`.gradle/`、`.idea/`、`.kotlin/`、`.cxx/`、模型缓存及签名凭据均不入库。Gradle/SDK/Maven 依赖来自本机工具链和配置仓库，不依赖父目录源码。发布时只提交源码、Wrapper、许可证、文档和模型清单，不提交 APK、下载模型或机器配置。
