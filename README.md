# 离线键鼠映射 · Root 本地版

本项目根据酷凡游戏厅 26.1.17 的 REA 静态及隔离运行调研，独立实现本地键鼠映射。运行不需要账号、网络、激活码或 Shizuku。Android 9 及以上；需要手机已 Root 并在 SU 管理器授权。

**当前交付是核心映射验收版，不能视为原软件全部功能的完整复刻。** 云配置、图像识别、压枪、手搓、复杂宏、原分享码格式、直接向触屏驱动写入的系统级触摸通路未实现。WASD 坐标生成器已从原软件自然加载的业务 DEX 恢复，已执行原字节码与新版逐点对比；未修改原 APK 或原账户授权。手机实机、SU 管理器和游戏内兼容性仍待验收。

## 使用

1. 安装 `outputs/离线键鼠映射-v1.1.0-root.apk`。
2. 连接 USB OTG 或蓝牙键盘、鼠标。在 Magisk / KernelSU / APatch 允许本应用 Root。
3. 在应用内激活 Root 服务并允许悬浮窗。
4. 添加已安装游戏，进入游戏后点击悬浮球编辑。将摇杆、鼠标视角区域和按键点位拖到游戏实际操作区域，保存。
5. 点击悬浮球“开始映射”；默认 **F1** 切换自由鼠标 / 锁定视角，**F8** 紧急暂停并释放键鼠。
6. 退出悬浮球和映射后恢复普通输入。重启手机后重新激活。

可先使用内置“触控练习场”，验证 WASD + 左键 + 空格的同时触控，再进入实际游戏。点位使用全屏比例坐标；横竖屏或分辨率改变后须检查实际点位。

## 已实现

- 本机 SU 请求、UID/token 验证的抽象 Unix socket、Root app_process 服务。
- USB / 蓝牙 evdev 输入识别、EVIOCGRAB 独占；排除触屏与独立电源/音量节点。
- 按住、单击、切换按住、连点、定时滑动；最多 20 个配置点位，最多 10 个同时触点。
- WASD / 自定义方向摇杆：原版普通、半摇感、全摇感轨迹，初始触点偏移、五类曲线、持续扰动、半径长按/切换、自定义终点偏角；算法与原参数可在设置中展开查看。
- 鼠标 FPS 相对位移、水平/垂直灵敏度、反转 Y、闲置释放、边缘释放重定位。
- 自由指针模式、普通鼠标按钮/滚轮透传；锁定视角后执行鼠标映射。
- 滚轮可绑定点击/滑动，鼠标左右中键和侧键可绑定。
- 游戏列表、多个本地配置、复制/重命名/删除、JSON 导入/导出。
- 游戏内可拖拽编辑、实体按键捕获、方向/视角设置、预览、保存或取消。
- 小球/通知暂停、F8 紧急暂停、设备断开/事件丢失/IPC 断开时释放触点和独占。

## 文件

- `app/`：独立 Android Java / JNI 源码。
- `scripts/release.sh`：编译三架构 native、Release、Lint、对齐、签名与校验。
- `scripts/test-engine.sh`：引擎状态机测试，需已启动并连接的 Android 模拟器/设备。
- `app/src/androidTest/`：无第三方依赖的配置 API 测试，测试后恢复原配置。
- `research/REA分析报告.md`：调研结论与 17 份 JSON 证据索引。
- `docs/WASD算法说明.md`：真实公式、参数、模式与验证边界。
- `docs/验收说明.md`：完成边界、验收操作和实测记录位置。
- `.signing/`：本机私有签名材料；保留它才能用同一证书覆盖安装，未包含在源码压缩包中。

## 构建环境

JDK 17、Gradle 8.14.5、Android SDK 35 / Build Tools 35.0.0、NDK 27.2.12479018。项目当前使用本机 SDK `/opt/homebrew/share/android-commandlinetools`；其他电脑请设置 `local.properties` 和 SDK/NDK 环境变量。应用运行离线；首次准备构建环境仍需要下载这些开发工具。依赖已在本机缓存，本次构建使用 `--offline`。

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./scripts/build-native.sh
./gradlew :app:assembleDebug :app:lintDebug --offline
# Release 需要本机 .signing/offline-mapper.jks 和 .signing/password
./scripts/release.sh
```

## 技术依据

[REA 官方 Android 分析说明](https://github.com/morluto/rea/blob/main/docs/android-analysis.md)和[资源分析说明](https://github.com/morluto/rea/blob/main/docs/apktool-resource-analysis.md)用于实际静态调研。[KeySync](https://github.com/aka-munan/keysync)仅作为公开架构参考；其应用代码、图标、随机触控逻辑均未复制。Gradle wrapper 来自标准生成脚本，许可见 `docs/licenses/Apache-2.0.txt`。

原包的 `TouchInjector`、输入解析、Root 服务及 native grab 等静态线索支持该基础实现路线，但不证明行为完全一致。Android 公开触点结构可参考 [MotionEvent](https://developer.android.com/reference/android/view/MotionEvent)。不同厂商系统、SELinux 策略及真实游戏兼容性须在目标手机验证。
