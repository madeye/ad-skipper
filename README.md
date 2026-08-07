# Ad Skipper — 本地 VLM 开屏广告跳过

基于 Android 无障碍服务的开屏广告自动跳过应用。检测采用三层降级策略：

| 层级 | 方式 | 典型延迟 | 适用场景 |
|---|---|---|---|
| L1 | 无障碍节点文本匹配（跳过 / Skip / skip_ad…） | <10ms | 原生 UI 的大多数 App |
| L2 | ML Kit 本地中文 OCR | ~100ms | Flutter / Unity / 游戏等无 UI 树的 App |
| L3 | 本地 VLM Grounding（llama.cpp + GGUF） | ~1s | 倒计时圆环、混淆按钮、纯图片按钮 |

命中即短路，L2/L3 只有在前一层未命中时才执行；命中后通过无障碍手势模拟点击。

## 工程结构（仿 ../meow）

```
buildSrc/          # setupCommon/setupCore/setupApp 构建约定
core/              # Android library：无障碍服务、检测管线、VLM JNI、模型管理、数据层
  src/main/cpp/    # llama.cpp（vendored）+ vlm_jni.cpp + CMakeLists.txt
mobile/            # 应用模块：Compose UI（首页/设置/模型/统计）+ 模拟广告测试页
```

## 构建

1. 准备 Android SDK / NDK / CMake（`local.properties` 配置 `sdk.dir`）。
2. 拉取 llama.cpp 源码（已 gitignore，体积大）：

   ```bash
   git clone --depth 1 https://github.com/ggml-org/llama.cpp.git core/src/main/cpp/llama.cpp
   # GitHub 不通时可用镜像：
   # git clone --depth 1 https://gitee.com/mirrors/llama.cpp.git core/src/main/cpp/llama.cpp
   ```

3. 构建并安装：

   ```bash
   ./gradlew :mobile:assembleDebug
   adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
   ```

Gradle 仓库优先使用阿里云镜像，国内可直接构建。

## 使用

1. 打开 App，按首页引导开启无障碍服务（设置 → 无障碍 → 广告跳过）。
2. 默认 L1/L2 已开启，可覆盖绝大多数开屏广告。
3. 需要 L3 时在「模型」页下载模型（默认 Qwen2.5-VL 3B Q4_K_M，模型 1.9GB + mmproj 1.3GB，
   源：ModelScope，失败自动回退 hf-mirror；低配机可选 Qwen2-VL 2B；也支持手动导入 GGUF + mmproj），
   然后在「设置」页打开 L3 开关。
4. 「设置」页可配置关键词、白名单、调试悬浮窗（命中时显示层级与坐标）。

## 模型来源

- 默认：`ggml-org/Qwen2.5-VL-3B-Instruct-GGUF`（ModelScope 镜像优先，文件名已实测验证）
- 备选：`bartowski/Qwen2-VL-2B-Instruct-GGUF`（更小更快）、`OpenBMB/MiniCPM-V-2_6-gguf`（更强更重）
- 轻量：`ggml-org/SmolVLM2-256M-Video-Instruct-GGUF`（Q8_0，合计约 266MB，秒级加载，
  适合低配机；256M 小模型 grounding 能力有限，复杂按钮可能定位不准）
- llama.cpp 侧：mtmd API（`tools/mtmd`），当前 pin 在 master `a1f96d4`，
  升级 llama.cpp 后需重新验证 `vlm_jni.cpp` 的 API 兼容性。

## 网络说明

- `gradle.properties` 内置了本机代理（127.0.0.1:7890）的 `systemProp.http(s).proxyHost`，
  因为 JVM/Gradle 不读 `*_PROXY` 环境变量，直连 dl.google.com 会卡死。无代理环境请删除这几行。
- 依赖仓库官方源在前、阿里云镜像兜底；插件仓库只用官方源（阿里云 gradle-plugin 镜像元数据不全）。
- 模型下载走 OkHttp，ModelScope 国内直连，无需代理。

## 测试

```bash
./gradlew :core:testDebugUnitTest   # BboxParser / KeywordMatcher / 管线降级顺序
```

模拟器端到端：安装后打开 App → 设置页开启「自测模式」→ 首页「模拟广告测试」，
观察模拟广告页被自动点击「跳过」并关闭（统计页会新增一条 L1 记录）。
**已在 Medium_Phone_API_36.1 模拟器上验证通过**（L1 命中 → 手势点击 → Room 统计落库）。

注意：L3（VLM）链路已通过编译与 llama.cpp mtmd API 对齐验证，但端到端推理
（下载 3.3GB 模型 + 真机推理）尚未在本环境实测，首次使用请先在真机上小流量验证。

## 已知限制

- `FLAG_SECURE` 页面（银行等）截图为黑屏，此时只有不依赖截图的 L1 生效。
- L3 CPU 推理约 1–2s，已加超时（默认 4s）放弃机制；GPU/NPU 后端留作后续扩展。
- VLM 效果依赖所选模型，bbox 解析对输出格式做了 0-1 / 0-100 / 0-1000 三种归一化容错。
