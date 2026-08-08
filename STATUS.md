# Ad Skipper — 开发状态记录

> 更新时间：2026-08-08。仓库：https://github.com/madeye/ad-skipper （MIT, Copyright Max Lv）

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
  - GPU 加速（改用 ggml Vulkan，弃用 TFLite）：TFLite GPU delegate 依赖
    OpenGL ES 3.1，覆盖面差；改为把 YOLO11n 直接跑在仓库已有的 ggml Vulkan
    后端上（与 L3b VLM 同一后端），只需 Vulkan 1.0。实现：
    - `vlm-bench/ggml-yolo/convert.py` 把 YOLO11n ONNX 转成 `yolo.gguf`（权重，
      ggml layout）+ `yolo.prog`（319 节点 op-program，具体属性）+ ORT 逐节点
      参考；`run_host.cpp`（链接 host ggml，CPU）逐节点对齐 ORT，output0
      relmax=4.7e-4 PASS（含 C2PSA attention 与 DFL 解码）。
    - `core/src/main/cpp/yolo_jni.cpp`：ggml-backend 载入 gguf+prog，
      GPU(Vulkan)→CPU 自动回退，构图一次、每帧 set 输入/compute/取 output0；
      `YoloNative.kt`/`YoloSkipDetector.kt` 首启把 gguf/prog 解压到 filesDir，
      解码沿用原 [1,5,8400] 逻辑（布局一致）。CMake 新增 `yolo_jni` 目标（链接
      ggml）。已移除 TFLite 依赖与 `skip_detector.tflite`、force_gpu 调试开关。
    - 关键转换点：ggml ne = ONNX dims 反序（conv 权重 [OC,IC,KH,KW] 直接当
      [KW,KH,IC,OC]）；ggml_permute 轴映射需 `axis_i = r-1-argwhere(perm==r-1-i)`；
      depthwise conv 走 `ggml_conv_2d_dw`（内部强制 F16 im2col → 核 cast F16）；
      两处 softmax 均为 ne[0]。
    - 构建通过，APK 116MB（含 libggml-vulkan.so 53MB + libyolo_jni.so + 10MB
      gguf），已装到真机（小米 14 Ultra / Adreno 750 / Vulkan 1.3）。真机
      GPU 端到端确认待用户手动开启无障碍服务（HyperOS 禁止 adb 写
      secure settings，无法脚本开启）。
  - ggml-Vulkan 真机不可用 → 改用 ncnn（feature/yolo-ncnn 分支，2026-08-07）：
    ggml Vulkan 后端面向桌面 GPU，Adreno 真机跑不通；换成 ncnn（Vulkan
    compute + CPU/NEON 回退，内置 Adreno/Mali 驱动 workaround，simplevk 自带
    loader 不依赖 NDK libvulkan）。模型改用 Ultralytics 官方 NCNN 导出
    （`yolo export format=ncnn half=True`，fp16 5.1MB，in0→out0），val 集
    mAP50 0.992 / P 0.98 / R 0.972，与原权重一致 — 整个 ggml-yolo 手写
    转换管线不再需要。`yolo_jni.cpp` 重写为 ncnn Net/Extractor（先试
    Vulkan，load 失败回退 CPU）；assets 换成 yolo.ncnn.param/bin；ncnn
    20260526 android-vulkan 预编译静态库 vendored 到 `core/src/main/cpp/ncnn/`
    （gitignored，README 有下载步骤）；链接 `-static-openmp`。
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
13. **豆瓣开屏广告跳不过 —— 根因是 HyperOS GreezeManager 进程冻结**（2026-08-08 真机
    排查，小米 14 Ultra / OS3.0.305）：冷启动豆瓣后无障碍服务进程在最后一个无障碍事件
    后约 1s 被 cgroup 冻结（实测 `/sys/fs/cgroup/apps/uid_*/pid_*/cgroup.freeze=1` 贯穿
    整个开屏窗口）。视频开屏广告不发无障碍事件，于是开屏轮询协程在广告出现时恰好拿不到
    CPU —— 日志特征为「splash poller start + 1 个 tick 后彻底安静」。有 permissioncontroller
    等事件刷屏的启动反而正常（每次 binder 投递会短暂解冻），所以之前脉脉热启动等验证都
    碰巧通过。修复（多重）：
    - **解冻脉冲（thaw pulse）**：开屏窗口期内让一个 1px 透明 accessibility overlay view
      每 300ms 自发 `TYPE_WINDOW_CONTENT_CHANGED` 事件 → 事件投递解冻进程（约 1s 宽限）
      → 解冻期间投递下一个脉冲，自持循环；窗口结束即停。实测整个窗口 freeze=0、轮询
      每 750ms 正常执行。
    - **唤醒锁无效**：`PARTIAL_WAKE_LOCK` 在冻结时被强制 DISABLED（dumpsys power 可见
      ACQ 后约 1s 被 REL），GreezeManager 不尊重 wakelock。代码保留（其他 OEM 冻结器
      可能尊重），但 HyperOS 上靠脉冲。
    - **活跃窗口守卫**：检测/点击前校验 `rootInActiveWindow.packageName == pkg`，取代
      旧的「其他包 WINDOW_STATE_CHANGED 即取消轮询器」逻辑（该取消正是压死轮询的另一
      路径：permissioncontroller 瞬时窗口事件取消豆瓣轮询器后，广告不再发事件，轮询永不
      恢复）。同时杜绝了「权限弹窗事件触发全屏截图 OCR 并按广告坐标点击」的乱点。
    - **takeScreenshot 加 2s 超时**：之前回调不来会永久挂起检测协程并卡死 processing 锁。
    - **NodeMatcher 全屏节点守卫**：豆瓣的 `id/skip` 是无文字、不可点击、bounds 全屏
      （且对服务不可见，仅 uiautomator 可见）的容器；id 命中会点屏幕中心 = 点进广告落地页。
      现丢弃面积超过根节点 1/3 的命中。
    - **YOLO 预热**：ncnn 冷初始化（Vulkan + 建 pipeline + 载模型）约 3s，发生在窗内会
      吃掉大半个窗口；改为 connect 后延迟 8s 预热。**注意**：connect 后 1-3s 内立即 init
      会在 `ncnn::Net::load_model` 空指针 SIGSEGV（墓碑实测，uptime 3s），且服务崩溃后
      系统不再重绑（Crashed services），HyperOS 又禁止 adb 写 secure settings，只能手动
      重开无障碍开关 —— 必须延迟预热。
    - 验证：连续多轮豆瓣冷启动，L2_OCR 命中真实跳过按钮 (951,173~175) 并在倒计时结束前
      进入首页（截图确认）；脉冲停后 poller 正常退出。
    - 调试辅助：debugOverlay 开启时把管线实际看到的每帧存到 `filesDir/shots/`（保留 12 张，
      `adb exec-out run-as com.tangzixiang.adskipper cat files/shots/<ts>.png` 导出）。
14. **重装/崩溃后的绑定恢复**：HyperOS 禁止 adb 写 secure settings（`settings put secure`
    直接 SecurityException），`adb install -r` 或服务崩溃后绑定丢失时的 adb 恢复办法：
    跑一次 `adb shell uiautomator dump`（UiAutomation 接入会触发无障碍重扫重绑）。崩溃
    过多次的服务会进 Crashed services 列表不再自动重绑，重装可清除该状态。
15. **冻结比 13 更致命：无障碍事件投递根本不解冻已冻结进程**（2026-08-08 下午真机复测，
    fix/hyperos-persistent-freeze 分支）：13 的「脉冲仅在开屏窗口期运行」修复有个窗口外
    死锁 —— 窗口结束脉冲停止后 ~1s 进程被冻结，此后豆瓣冷启动的事件**根本不会投递进来**
    （logcat 零输出，`cgroup.freeze=1` 贯穿整个开屏），poller 压根不启动，之前修复全部
    失效。13 里「binder 投递会短暂解冻」的推断是错的：只有事件到达时进程恰好未冻结才有效。
    实测 `RUN_ANY_IN_BACKGROUND allow`、deviceidle whitelist 均无效（run-as 解冻后 ~2s
    重新冻结）；GreezeManager 明确会解冻的只有 alarm（`THAW ... reason : alarm`）和
    Activity Start。修复（多重）：
    - **脉冲改为常驻**：只要屏幕亮着就持续运行（onServiceConnected 启动），不再限于开屏
      窗口。关键性质：postDelayed 消息在冻结中不丢失，任何解冻后脉冲链自动续跑并从此保持
      不冻结。SCREEN_OFF 停脉冲（灭屏无广告，允许冻结省电）；overlay view 常驻不反复增删。
    - **看门狗 alarm**（60s，`setExact` + OnAlarmListener，`ELAPSED_REALTIME` 非唤醒型）：
      冻结状态的保底解冻通道。非唤醒型零电量成本 —— 亮屏时脉冲本来就防冻结，睡眠中过期的
      alarm 会在设备唤醒瞬间投递 = 正好在需要解冻的时刻解冻并重启脉冲。manifest 加
      `USE_EXACT_ALARM`（侧载分发可用；上架 Play 需换 SCHEDULE_EXACT_ALARM 申请流程）。
    - 验证：装新包后空闲 30s `cgroup.freeze=0`（旧包 ~2s 即 1）；连续 2 轮豆瓣冷启动
      （adb `monkey` 拉起）均 L2_OCR 在 (950,174) 命中「跳过 5」胶囊并于倒计时结束前进入
      首页（debugOverlay 显示 L2_OCR 412ms，filesDir/shots 帧转储确认素材）。

## 待验证 / 风险

- **豆瓣部分广告素材的检测召回**：修复冻结问题后，视频类开屏（右上角「跳过 N」灰字）
  可被 L2_OCR 稳定命中；但实测一种静态图素材（右上角深色半透明胶囊「跳过 5」，压在
  橙色 banner 上）连续 5 个 tick OCR/YOLO 均未命中（YOLO conf<0.55）。另有一轮出现
  「点击 (951,173) 后广告仍在、之后多个 tick 又不再命中」的现象，怀疑 HyperOS 上
  `takeScreenshot` 在转场期返回旧帧（screencap 与服务截图看到的内容不一致），需用
  filesDir/shots 的帧转储确认是截图陈旧还是检测器盲区。若是后者，按 README 所述收集
  真实截图微调 YOLO 是下一步。
- **脉冲的电量影响**（15 改常驻后重新评估）：亮屏期间每 300ms 一个微型 binder 事件，
  灭屏即停。待观察一整天实际耗电排名；若可感知，可考虑把亮屏时的脉冲间隔放宽到
  500-800ms（需重新实测冻结宽限期），或仅在「近期有非白名单 App 启动」时提频。
- **醒后 60s 盲区**：设备唤醒瞬间若看门狗 alarm 尚未到期（灭屏 <60s）且进程已冻结、
  SCREEN_ON 广播未投递，最坏在唤醒后 60s 内启动的 App 会漏检。实测未复现（唤醒本身
  常伴随可解冻的系统活动），暂不处理。

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
