# CaiNiaoParcelCodeFinder · 包裹码速找

[中文](README.md) | English

An independent derivative of the PaddleOCR Android demo for finding an exact four-digit code in camera OCR. Not an official product and not affiliated with or endorsed by Alibaba, Cainiao, or PaddlePaddle. It does not query shipping accounts or verify parcel ownership.

## Features

- Enter exactly four ASCII digits (including leading zeros) and press Apply. Draft edits do not change the active target. Empty Apply/Clear disables targeting; invalid input retains the previous target.
- Camera OCR compares each returned string using `trim()` and exact equality against the latest applied target. The first matching frame stops subsequent inference, highlighting all matches. Targets are session-only; image/video OCR does not auto-stop on a match.
- Offline FP32 bundles: PP-OCRv6_small (default), PP-OCRv6_tiny, PP-OCRv5_mobile; paired detector/recognizer and matching dictionary.
- CPU, six threads; recognition ROI batch 1/2/4/8, default 4 for new settings. Existing saved settings take precedence. QNN dependencies remain in the SDK but the app uses CPU.
- Model-specific explicit detector defaults and editable overrides, persisted with model/batch after successful loads. Reload failure retains the working engine.
- Original/half/quarter dimensions (default quarter), direct Bitmap input, BGR recognition, serial inference with draw backpressure. Video processes indexed frames; camera may drop frames while busy.
- Processing dimensions, preprocessing/detection/recognition/postprocessing timings and cumulative FPS. Postprocessing ends at the first Compose draw, not physical display scanout.

## Privacy and limitations

The app attempts to recognize the latest accessible gallery image at startup and requests image-library permission; it is **not limited to manually picked photos**. Android 14 partial photo access is requested, but auto-gallery logic still checks full image permission; use the system picker when auto-gallery is unavailable. Camera permission is optional. Source manifests do not request Internet access and no photo-upload flow is implemented. Build-time dependency/model downloads do use the network. Android backup remains enabled and may include preferences.

OCR can misread codes; a four-digit match is not proof of parcel ownership. Verify the parcel independently and avoid exposing other people's labels. Fresh standalone builds still need manual camera/UI/performance validation.

## Build

Use JDK 21, Android SDK Platform 35 and Build Tools 34.0.0. Bundled Gradle 8.9 / AGP 8.7.3 / Kotlin 2.1.0; JVM compilation target 17. Android API 26+, arm64-v8a only.

Set `JAVA_HOME` and `ANDROID_HOME`, or create an ignored `local.properties` with your SDK path. From this repository root:

```sh
python scripts/provision_models.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On Windows cmd use `gradlew.bat`. Python 3 standard library only; default model cache is `.model-cache/`. Model binaries and downloaded YAML dictionaries are intentionally ignored, but must exist before building. APK: `app/build/outputs/apk/debug/app-debug.apk`. No parent checkout is needed and the build does not install to a device.

The application ID and namespace intentionally remain `com.paddle.ocr.demo` to preserve compatibility/data. It cannot coexist with the original same-ID demo, and upgrading requires the same signing key. Do not uninstall casually to resolve signing conflicts.

See [model provenance/settings](scripts/MODELS.md), [structure](docs/PROJECT_STRUCTURE.md), [upstream attribution](docs/UPSTREAM.md) and [Apache-2.0 license](LICENSE). Release signing, redistribution rights and store compliance require review.
