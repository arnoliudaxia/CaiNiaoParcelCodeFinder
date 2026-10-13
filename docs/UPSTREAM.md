# 来源与派生说明

本项目是 [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) 的 Android 示例派生作品，源目录为 `deploy/ppocr-android`。独立化时复制的是本地工作树，而非仅导出某个提交，因此包含当时尚未提交的相机目标查找功能。

- 本地源仓库基线：`011fe0f92e4578a1d86ee1a3fde7ec31a7d9a90e`（简写 `011fe0f9`）。这是派生工作树的本地 Git 基线，**不声称该提交一定存在于官方上游**。
- 已有本地改动包括 CPU6、直接 Bitmap/BGR、图片/视频/相机串行 OCR、分辨率和计时、三套离线模型、batch4 默认与选择、模型检测默认参数及可编辑覆盖。
- 基线之上的未提交功能包括相机四位 ASCII 数字目标输入、应用/清除、trim 后精确匹配、首次命中自动停止与画面突出显示及测试。
- 独立化仅调整项目/显示名称、文档、忽略规则和便携构建入口；保留模块结构、源文件版权头、SDK/QNN 代码与原应用包名。

根目录 `LICENSE` 从源 PaddleOCR 仓库根目录原样复制，采用 Apache License 2.0。已有源文件版权声明不移除、不改署名。本仓库的名字不是对原作者代码的原创性声明；第三方库、模型和图片素材的权利不因派生而转移，分发时需复核各自许可。

**本项目为非官方工具，与阿里巴巴、菜鸟及 PaddlePaddle 无隶属、合作或背书关系。** CaiNiaoParcelCodeFinder 名称仅用于标识本项目，不代表官方菜鸟产品。

应用 ID/namespace 仍为 `com.paddle.ocr.demo`，目的是保留已有安装和偏好兼容性，而非冒充官方应用。原图标及部分内部 PaddleOCR 类名/主题名保留，以减少无关重构；公开发布前可再审核品牌和素材使用。

测试资源 `ppocr-sdk/src/androidTest/res/raw/android_ocr_benchmark_reference.png` 保留自源工作树，内容为街景标识（包含公共地址文字），实际编码为 JPEG；它不是业务采集数据。发布前请确认该图片的来源和再分发许可，必要时替换为自制测试素材。
