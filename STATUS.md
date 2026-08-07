# Ad Skipper — 开发状态记录

> 更新时间：2026-08-07。仓库：https://github.com/madeye/ad-skipper （MIT, Copyright Max Lv）

## 当前任务状态（TODO）

- [x] VLM 模型选型基准测试（2026-08-07，harness 在 `/Volumes/DATA/workspace/vlm-bench/`，
  详见其 REPORT.md）：20 张合成开屏广告 + GT 框，8 个候选 GGUF 模型 × 4 种输入
  分辨率 × 多种坐标约定。结论：
  - **旧配置（448px + yxyx 归一化 prompt/parser）所有模型 0/20 命中**，三个叠加
    原因：448px 太小（跳过按钮仅 ~30×15px）；模型实际回答 xyxy（InternVL/MiniCPM
    为 0-1000 归一化，Qwen2.5-VL 为输入图绝对像素）；BboxParser 的数字正则会把
    JSON key `bbox_2d` 里的 2 当成第一个坐标。
  - **最优折中：InternVL3-2B Q4_K_M + Q8_0 mmproj（1.46GB）@896px：16/20 命中、
    0 次误点 CTA**。Qwen2.5-VL-3B @672px 也是 16/20 但体积 2.8GB 且有 1 次误点
    CTA；MiniCPM-V 2.6（5.7GB，8/20）与 Qwen2-VL-2B（2.3GB，≤5/20）被碾压；
    ≤1B 的模型（SmolVLM2 256M/500M、LFM2-VL、InternVL3-1B）全部 0-3/20，无
    grounding 能力。Qwen 的 Q8_0 mmproj 与 f16 精度一致（省 0.5GB）。
- [x] 按基准结论重构 L3（feature/internvl3-2b-default 分支）：prompt 改为
  JSON bbox_2d xyxy、BboxParser 重写（方括号组提取 + 按模型 CoordSpace 解析）、
  MAX_DIM 按模型（896/672）、vlmTimeoutMs 默认 4s→8s、目录仅保留
  InternVL3-2B / Qwen2.5-VL / 自定义。APK 不再内置任何 VLM（2B 太大，
  256M 无用）：L3 需先在模型页下载 InternVL3-2B，未下载时自动跳过
- [x] 内置 SmolVLM2 256M (Q8_0, ~266MB) 到 APK assets 并设为默认模型（已
  移除：基准测试证明其无 grounding 能力，0/20）
- [x] 训练轻量 YOLO 跳过按钮检测器并内置 APK（L3a 层），VLM 降级为可选下载
  兜底（L3b）：YOLO11n 在 2400 张合成开屏广告上训练，基准 19/20 命中、0 次
  误点 CTA、host 21ms；导出 fp32 TFLite 10.6MB 内置 assets（APK 共 123MB）。
  训练/导出管线在 `/Volumes/DATA/workspace/vlm-bench/`（gen_yolo_data.py /
  eval_yolo.py / runs/detect/yolo_runs/）。
  - 首次真机（模拟器）验证暴露纯合成训练的误报问题：launcher/设置等真实界面
    16/16 出现 ≥0.35 置信度的假阳性 → 三重修复：(1) 截取真实系统界面做负样本
    微调（12 屏 ×25 增广 = 300 张，4 屏留作验证）；(2) 置信度阈值 0.35→0.55；
    (3) AdSkipperService 增加「开屏窗口期」门控 — 图像类 L3 仅在应用会话开始
    8s 内运行（20s 无事件视为新会话），自测模式豁免；默认白名单扩充常见
    launcher。
- [x] Vulkan GPU 后端编译 + L3 端到端验证
  - 模拟器 logcat 确认 Vulkan 后端生效：
    `llama_prepare_model_devices: using device Vulkan0 (Goldfish GFXStream (Apple M4)) - 25005 MiB free`，
    `load_tensors: offloaded 31/31 layers to GPU`
  - 模拟广告验证：关闭 L1/L2，仅 L3_VLM 开启 → 命中
    `AdSkipperService: skip hit ... via L3_VLM at (540, 1200), timings=[(L3_VLM, 615)]`（GPU 上 615ms）
  - SmolVLM2-256M grounding 不够精确（返回了全屏 bbox → 点击屏幕中心），
    属于 L3 兜底层的已知可接受局限
- [x] README 更新（GPU 后端说明）+ git 提交推送（用户已授权过 GitHub 操作）
- 备注：`mobile` applicationId 已从旧值改名为 `com.tangzixiang.adskipper`

## 已验证可用

- `./gradlew :core:testDebugUnitTest` 全过（BboxParser / KeywordMatcher / 管线降级顺序）
- 模拟器（Medium_Phone_API_36.1）e2e：无障碍服务开启 → 自测模式 → L1 节点匹配
  命中模拟广告「跳过」按钮 → 手势点击 → Room 统计落库（logcat `skip hit ... via L1_NODE`）
- llama.cpp mtmd 链路在 Android 跑通：模型加载成功（"model loaded OK"，约 1.3s，GPU）
- 内置模型构建期下载（`:core:downloadBundledModel`）+ noCompress 打包 + 首启解压到
  filesDir 全流程验证通过
- Vulkan GPU 后端端到端验证通过：31/31 层 offload 到 `Vulkan0 (Goldfish GFXStream
  (Apple M4))`，L3_VLM 在关闭 L1/L2 时独立命中模拟广告（详见上方 TODO 小节）

## 关键问题与修复记录

1. **Gradle 依赖下载卡死**：JVM 不读 `*_PROXY` 环境变量，直连 dl.google.com 挂起。
   → `gradle.properties` 配 `systemProp.http(s).proxyHost=127.0.0.1:7890`，
   nonProxyHosts 含 modelscope.cn / maven.aliyun.com。
2. **阿里云镜像坑**：gradle-plugin 镜像 KSP 元数据为空、google 镜像对新 KSP 502。
   → 插件仓库只用官方源；依赖仓库官方在前、阿里云兜底。
3. **模型源核实**：`Qwen/Qwen2-VL-2B-Instruct-GGUF` 在 HF/ModelScope 均不存在（401/record not found）。
   → 实测可用：ggml-org/Qwen2.5-VL-3B-Instruct-GGUF、bartowski/Qwen2-VL-2B-Instruct-GGUF、
   ggml-org/SmolVLM2-256M-Video-Instruct-GGUF、OpenBMB/MiniCPM-V-2_6-gguf（文件名均核对）。
4. **JVM 直连 ModelScope Connection reset**（Gradle 任务内 URL.openStream）：
   → 下载任务改为 shell 调 `curl --noproxy '*' -C -`（带 5 次重试 + 断点续传）。
5. **openFd 失败**：library 模块的 aaptOptions.noCompress 不传递到打包。
   → mobile 模块也加 `noCompress += "gguf"`。
6. **OpenMP 崩溃**：libomp `__kmp_invoke_microtask` 内 SIGSEGV（mul_mat memcpy 写越界）。
   → `GGML_OPENMP=OFF`（用 ggml 自带线程池），mtmd `warmup=false`。修复后加载/推理不再崩。
7. **无障碍服务重装后不重新 bind**：`adb install -r` 后需切换
   `enabled_accessibility_services` 值（先指向 talkback 再切回）触发重连。
8. **NDK 不带 `vulkan/vulkan.hpp`**：ggml-vulkan 需要的 C++ 头文件 NDK sysroot 里没有。
   → `Vulkan_INCLUDE_DIR` 改指向 Homebrew 装的 Khronos Vulkan-Headers；只有
   `libvulkan.so` 仍从 NDK 对应 API level 目录取用。
9. **`vulkan-shaders-gen` 嵌套 CMake 找不到 ninja**：该 host 工具由 ExternalProject
   触发的嵌套 CMake 构建，继承了 Ninja generator 却没继承含 ninja 的 PATH
   （`CMAKE_MAKE_PROGRAM` 为空，Gradle 进程 PATH 里也没有 SDK 自带的 ninja）。
   → 生成 `vk-host-toolchain.cmake`，固定 `CMAKE_MAKE_PROGRAM` 为 SDK ninja、
   编译器为 host clang。注意 `core/.cxx` 下过期的嵌套 `CMakeCache.txt` 会缓存旧
   配置掩盖这个修复，复现时需 `rm -rf` 对应 `vulkan-shaders-gen-build` 目录。
10. **gfxstream Vulkan 设备 createDevice 失败，静默回退 CPU**：gfxstream（模拟器的
    guest Vulkan 实现）只宣称支持 16-bit-storage *feature*，不宣称 legacy 扩展名
    `VK_KHR_16bit_storage`（该扩展自 Vulkan 1.1 起已收编进 core），而原版
    ggml-vulkan 无条件请求这个扩展名 → `createDevice` 抛
    `vk::SystemError: ErrorExtensionNotPresent`，且 `ggml_backend_vk_reg()` 吞掉了
    异常，导致后端悄悄回退到 CPU、日志毫无线索。
    → vendored 源码补丁 `core/src/main/cpp/patches/0001-vulkan-16bit-storage-optional.patch`
    （CMake configure 时自动应用），只在设备真正宣称该扩展时才请求它。
11. **JNI crash：`NewStringUTF` 在非法 Modified UTF-8 上 abort**：生成过程可能在多
    字节字符中途截断，且 emoji 之类的 4 字节序列 `NewStringUTF` 从不接受，直接
    abort 整个进程。
    → 改为通过 `String(byte[], "UTF-8")` 传递结果字节（非法序列会变成 U+FFFD），
    不再走 `NewStringUTF`。
12. **VLM 生成从一开始就被静默限制在约 1 个 token**：解码循环用 `llama_batch_init`
    手工构造 `llama_batch`，但从未设置 `batch.n_tokens`，导致第一个生成 token 上
    `llama_decode` 就返回 -1（这也是早期日志里 `infer done, output: �` 的成因）。
    → 改用 `llama_batch_get_one`。修复后模型能生成完整句子（验证中 43-token 回答）。

## 待验证 / 风险

- InternVL3-2B @896px 的真机延迟未实测（host 基准含加载约 3s；896px 的图像
  token 是 448px 的 4 倍）。若旗舰机也压不进 ~2s，可考虑 Qwen2.5-VL-3B @672px
  （精度同为 16/20，但要多下 1.3GB 且基准中有 1 次误点 CTA）。
- APK 含内置模型约 1.5GB，Play 商店 AAB 限制下无法直接上架，需改用
  Play Asset Delivery 或首启下载（当前按侧载分发考虑）。
- llama.cpp pin 在 master `a1f96d4`（2026-08-06 浅克隆）；升级需重验 mtmd API。
- 目前仅在模拟器（Goldfish GFXStream / Apple M4 host）验证过 Vulkan 路径，真机
  GPU（各厂商驱动）尚未实测，需注意驱动差异导致的扩展支持不一致。

## 工程速查

- 结构（仿 ../meow）：`buildSrc` + `:core`（服务/管线/JNI/模型/数据）+ `:mobile`（Compose UI）
- 构建：`./gradlew :mobile:assembleDebug`（需先 clone llama.cpp 到 `core/src/main/cpp/llama.cpp`）
- 模拟器：`/Volumes/Data/workspace/android/emulator/emulator -avd Medium_Phone_API_36.1`
- GPU 测试必须加模拟器启动参数 `-gpu host -feature Vulkan`，否则客户机只拿到
  SwiftShader（CPU 类型）Vulkan 设备，ggml-vulkan 会拒绝并静默回退 CPU。
- 服务重绑：`settings put secure enabled_accessibility_services <先talkback再切回>`
- 测试流程：App 内设置页开「自测模式」→ 首页「模拟广告测试」→ logcat 看
  `AdSkipperService` / `VlmJni` / `llama` tag
- L3 单独验证流程：设置页开「自测模式」，关闭 L1/L2 开关 → 回首页 →
  「模拟广告测试」，确认 logcat 命中 `via L3_VLM`
