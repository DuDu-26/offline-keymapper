# 酷凡游戏厅 26.1.17：REA 静态调研

调研日期：2026-10-13。结论：公开可读资源与未加密低层 DEX 足以确定 Root 键鼠映射的基础功能结构；无法据此声称完整还原原软件。建议独立实现离线配置、悬浮编辑、键鼠事件读取及多指触摸注入，保留真实设备验收边界。

## 原包身份及工具

| 项目 | 现场值 |
|---|---|
| 原文件 | `酷凡游戏厅26.1.17.apk.1` |
| 实际字节数 | 23,420,004 |
| SHA-256 | `aebaa741983299577da0e72f5cea90484a750e528048164c6b39c4c714e9d3bc` |
| 包名 | `com.qvznrx.kdptla` |
| 版本 | `26.1.17` / versionCode `52` |
| Android | minSdk `28`，targetSdk `35`，compileSdk `36` |
| 资源显示名 | 默认、zh 字符串中的 `app_name` 均为 `hsl`；不以文件名推断显示名 |
| REA CLI | npm 发布版 `rea-agents@6.4.0`，项目内 `tools/rea/runtime/node_modules/.bin/rea` |
| REA 参考源码 | 本地 Git 镜像，commit `916ac97bf9f7b976982e438ceb856a9692576cb1` |
| JADX 引擎 | 官方文档要求的 `jadx-headless-mcp 0.7.1` |
| 引擎 SHA-256 | `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`，与 REA 固定校验值相同 |
| Java / Apktool | 完整 JDK17；Apktool `3.0.2` |

已阅读并联网核对 [REA Android 静态分析说明](https://github.com/morluto/rea/blob/main/docs/android-analysis.md)、[Apktool 资源分析说明](https://github.com/morluto/rea/blob/main/docs/apktool-resource-analysis.md)，CLI 输出参数依据本机 `--help`。本次直接使用 CLI；未执行 REA setup，未更改 Codex 全局配置。

原文件后缀 `.apk.1` 被 REA JADX 入口识别为 ZIP，第一次请求按预期拒绝。创建 `research/target.apk` 原样副本后成功，两文件 SHA-256 相同。这个失败记录也保留在 `rea-android-package.json`。

## 实际执行结果与证据

| 工作流 | 证据文件 | 结果 |
|---|---|---|
| `inspect-apktool-client` | `rea-apktool-client.json` | launcher 来自 PATH，版本 3.0.2 |
| `inspect-artifact` 原后缀 | `rea-artifact.json` | ZIP 文件结构、各成员摘要 |
| `inspect-artifact` APK 副本 | `rea-artifact-apk-copy.json` | APK 文件结构与原包哈希一致 |
| `project-android-application-graph` | `rea-android-graph.json` | 清单、资源、DEX、原生库路径及哈希 |
| `decode-android-resources` 默认 | `rea-resources-default.json` | 完整覆盖，867 条字符串，1,052 个解码文件 |
| 同命令 `--locale zh` | `rea-resources-zh.json` | 完整覆盖，763 条中文字符串 |
| `inspect-android-package` | `rea-android-package-copy.json` | 清单完整；55 个可见类、848 个资源项 |
| `search-android-classes` 空查询 | `rea-classes.json` | 全部可见类完整枚举 |
| `inspect-android-class` | `rea-touch-injector-class.json`、`rea-input-device-class.json` | 注入类、设备类成员清单 |
| `inspect-android-method` | `rea-touch-inject-method.json` | 注入方法 Java 表示可用，无 smali 回退，输出完整 |
| `trace-android-references` | `rea-touch-inject-refs.json` | 注入方法有两个静态入站引用 |
| 本地只读 ZIP/头部/字符串补充 | `native-and-packed-inventory.json` | 低层 JNI、打包/加壳线索；这项是本地补充，非 REA 原生反汇编结果 |

上述文件均位于本报告目录。`rea-summary.json` 提供精简摘要、来源及证据文件哈希，便于后续检查。

## 已观察到的清单结构

应用入口声明为 `moe.heroman.eventtest.MainActivity`，Application 为 `v.m.p`。包含特殊用途前台悬浮服务 `FloatingService`、ADB 配对服务、ShellProcessProvider 和 ShizukuProvider。权限声明包括悬浮窗、前台服务、通知、查询应用、存储、忽略电池优化及网络。原包有网络权限，独立离线实现不需要照搬该权限或远程激活机制。

Shizuku/ADB/Root 激活均有对应资源或清单线索；只使用 Root 是复刻需求的范围收敛。用户界面操作资源支持“激活助手 → Root模式 → 激活Root权限”，并提醒需要在 SU 管理器授权。

## 功能清单和独立实现边界

以下“资源可见”表示原 APK 的字符串确实存在，不等于已运行验证该功能。原包界面、后台服务时序、数据格式和性能未做动态确认。

| 功能 | 原包证据 | 独立离线基础版目标/差距 |
|---|---|---|
| Root 激活 | `activation_root_title`、`root_step_activate`、SU 授权说明 | 检测 root，启动本机 Root 输入服务，显示真实状态 |
| 悬浮球和映射编辑 | `menu_mapping_config`、`menu_mapping_overlay`、`shoucuo_mapping_paused` | 显示/隐藏编辑层、暂停恢复；可拖动坐标 |
| 点击、长按、连点 | 按下/抬起/按抬触发及点击间隔资源 | 键码绑定、按下保持和松开释放，多点同时按住 |
| WASD/方向虚拟摇杆 | `settings_joystick_mode_wsad`、`settings_joystick_radius` | 方向组合、斜向移动、中心和半径配置 |
| FPS 鼠标视角 | `settings_fps_view`、水平/垂直灵敏度、边缘释放延迟 | 相对位移转触摸拖动、锁定切换、到边重置；需真鼠标验证 |
| 鼠标按键与侧键 | 左、中、右及侧键对应字符串 | 绑定常见鼠标键；额外侧键由硬件事件决定 |
| 滚轮滑动 | `settings_mouse_scroll_desc`、反转/边缘回中/释放间隔 | 滚轮转矩形区域垂直滑动 |
| 滑动动作 | 时长、滑动前/后点击配置 | 起终点和时长可配置；时序必须验收 |
| 宏 | 按下/释放/循环/终止键资源 | 高级可选功能，不能因为资源存在就声称基础版等价 |
| 游戏配置 | 保存失败/成功、分享码导入/分享及游戏设置资源 | 本地持久化、多配置文件导入导出是独立设计；原包使用分享码，格式与服务行为未确认 |
| 系统级触摸 | 描述直接写屏幕驱动并要求 Root | 是独立于框架注入的另一通路；设备差异大，不能把 InputManager 路线等同该功能 |
| PUBG 识别、压枪、智能武器/倍镜/蹲伏 | 完整中文资源及 OpenCV 库 | 超出基础离线键鼠映射；没有恢复识别模型及业务算法 |
| 手搓工作模式 | `working_mode_shoucuo` 等 | 属于原软件另一工作模式，基础键鼠版本不等价 |
| 远程套餐、鉴权、统计 | 套餐字符串、`libauth.so`、Umeng/监控库 | 本次不复制；离线自主实现无原包鉴权兼容性承诺 |

## 可读 DEX 和原生线索

原主 DEX 只显示 55 类，但不仅是壳类：还保留 `InputDevManager`、`InputDevice`、`KeyboardEventParser`、`MouseEventParser`、`RawEventReceiver`、`NativeEventMux`、`TouchInjector`、`TouchStateManager`、`PointersState`、`IpcRing` 等低层输入组件。清单中的 MainActivity 不在这次可见类枚举中，因此没有得到完整界面和游戏配置业务代码。

REA 的 TouchInjector 成员清单显示反射 Method 字段及 `injectInputEvent(InputEvent,int)`。方法观察确认它经反射调用保存的输入管理对象并返回布尔值。引用追踪显示 `TouchStateManager.performTouch` 和 `cancelFrameworkTouches` 静态引用该注入方法；这些是静态关联，不是运行调用记录。

`libinput.so` 为 ELF，可见 JNI 名称对应键盘/鼠标/触摸设备识别、grab 事件、绝对轴最大值读取，以及系统触摸 nativeOpenDevice/nativeInject/nativeReset。`libipcring.so` 可见环形 IPC 的创建、读写帧及关闭方法；`libadb.so` 可见 ADB 配对相关 JNI；库名或导出名不证明具体实现、性能和权限兼容性。

特别检查实际文件头：`lib/arm64-v8a/libshellproc.so` 的字节是 `dex\n039`，不是 ELF；大小 80,356 字节。可见 ASCII 包含 `/dev/input/`、InputManager、InputManagerGlobal 和 injectInputEvent。额外创建的 `shellproc-static-probe.apk` 只是把这个未加密原样 DEX 接到 REA 的 standalone APK 静态入口；其来源与哈希见 `shellproc-probe-provenance.json`，类枚举见 `rea-shellproc-classes.json`。它不是可安装成品，清单不代表该 DEX 有完整业务组件。

因此独立实现“Root 后台进程 → 读取 evdev 键鼠 → 映射动作 → 构造多指触摸 → InputManager 注入”的路线有静态依据。该结论只说明结构相符，不代表复用了原软件算法或已证明所有系统版本可运行。

## 加壳与验证限制

`abcd/libabcd.enc`、`abcd/libabcd2.enc`、`assets/libabcdProtect.*` 以及 `v.m.*` 入口构成明显保护线索。未解密这些文件、未导出运行内存、未绕过激活、未解壳；没有得到隐藏业务 DEX。占位资源路径不应用于功能推断。

REA 的 APK 分析不会自动执行 APK，也不验证签名；本次 `signature_verification` 为 `not_performed`。资源工作流自身的 Evidence `subject` 为空并提示通用 artifact identity limitation，但它的 `normalized_result.target` 明确保留原包路径、字节数和 SHA-256；报告使用这个明确绑定字段。应用图的 bridge candidates 只是按路径推测关系，不能充当实际 JNI 调用证据。

本次没有启动原软件验证界面，没有全量反汇编 ELF，也没有测量延迟、帧率和长期稳定性。独立 APK 的验收应另行记录 root 授权、USB/蓝牙键鼠、点击与释放、多指摇杆+鼠标同时操作、旋转/分辨率、游戏窗口实际触摸和设备 SELinux 差异。模拟器验收不能替代目标手机及真实键鼠验收。

## 复现方式

在项目根目录设置环境后可重跑，所有输出留在 research：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export REA_JADX_MCP_JAR="$PWD/tools/rea/engines/jadx-headless-mcp-0.7.1-all.jar"
REA_CLI="$PWD/tools/rea/runtime/node_modules/.bin/rea"
"$REA_CLI" inspect-android-package "$PWD/research/target.apk" --format json
"$REA_CLI" search-android-classes "$PWD/research/target.apk" '' --format json
"$REA_CLI" decode-android-resources "$PWD/酷凡游戏厅26.1.17.apk.1" --locale zh --format json
"$REA_CLI" inspect-android-class "$PWD/research/target.apk" moe.heroman.eventtest.shellprocess.touchcontroller.TouchInjector --format json
"$REA_CLI" inspect-android-method "$PWD/research/target.apk" moe.heroman.eventtest.shellprocess.touchcontroller.TouchInjector injectInputEvent --format json
"$REA_CLI" trace-android-references "$PWD/research/target.apk" moe.heroman.eventtest.shellprocess.touchcontroller.TouchInjector --method-name injectInputEvent --format json
```

调研已经达到当前独立基础版实现所需的信息边界，不继续扩展到解壳或隐藏算法恢复。
