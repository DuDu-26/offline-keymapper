# 离线键鼠映射 · Root 本地版

在已 Root 的 Android 手机上，把 USB / 蓝牙键盘鼠标映射成屏幕触控，用来玩手游。完全本地运行，不需要账号、网络或激活码。要求 Android 9 及以上，并在 Magisk / KernelSU / APatch 中授权本应用 Root。

当前状态：核心映射可用，练习场测试正常；具体游戏中的兼容性和长时间稳定性仍在验证。

## 使用

1. 从 [Releases](https://github.com/DuDu-26/offline-keymapper/releases) 或 GitHub Actions 构建产物下载 APK 并安装。
2. 连接 USB OTG 或蓝牙键盘、鼠标，在 SU 管理器中允许本应用 Root。
3. 在应用内激活 Root 服务，并允许悬浮窗。
4. 添加已安装的游戏，进入游戏后点击悬浮球编辑：把摇杆、鼠标视角区域和按键点位拖到游戏的实际操作区域，然后保存。
5. 点击悬浮球“开始映射”。默认 **F1** 切换自由鼠标 / 锁定视角，**F8** 紧急暂停并释放键鼠。
6. 关闭悬浮球和映射后，键鼠恢复普通输入。手机重启后需要重新激活。

建议先在内置“触控练习场”里验证 WASD + 左键 + 空格的多指触控，再进入游戏。点位按全屏比例保存；横竖屏或分辨率改变后需要检查点位。

## 功能

- Root 服务：本机 SU 启动 app_process 守护进程，通过抽象 Unix socket 反向回连，校验 UID 和随机 token。无网络权限。
- 输入：JNI 读取 USB / 蓝牙 evdev 设备并独占（EVIOCGRAB），排除触屏和电源 / 音量节点；专用高优先级采集线程。
- 按键动作：按住、单击、切换按住、连点、定时滑动；最多 20 个点位，最多 10 个同时触点。
- WASD 摇杆：拟真 / 平滑 / 直接三种手感，弹簧跟随轨迹，可调到位时间、按住漂移、终点偏角、推出比例和起点偏移，支持半径切换键。详见 [摇杆算法](docs/摇杆算法.md)。
- 鼠标：FPS 相对视角（水平 / 垂直灵敏度滑块、开镜灵敏度倍率、视角发送频率、反转 Y、闲置释放、边缘重定位），悬浮球菜单里可以随时调灵敏度，自由指针模式下左键转触控点击和拖动，滚轮、中键、侧键可绑定。
- 配置：按游戏保存多个本地配置，支持复制、重命名、删除、JSON 导入导出；游戏内拖拽编辑、实体按键捕获。
- 安全：悬浮球 / 通知暂停、F8 紧急暂停；设备断开、事件丢失或连接中断时自动释放触点和独占。

## 目录

- `app/`：Android Java / JNI 源码。
- `docs/技术实现.md`：架构和安全边界。
- `docs/摇杆算法.md`：WASD 轨迹模型和参数。
- `scripts/test-trajectory.sh`：摇杆轨迹测试，普通 JVM 即可运行。
- `scripts/test-engine.sh`：引擎状态机测试，需要已连接的 Android 模拟器或设备。
- `scripts/input-tests/`：native 输入采集测试。
- `app/src/androidTest/`：配置 API 测试，测试结束后恢复原配置。

## 构建

JDK 17、Android SDK 35 / Build Tools 35.0.0。修改 native 代码时还需要 NDK 27.2.12479018（预编译的 `libmapper_input.so` 已在 `app/src/main/jniLibs/`）。

```sh
./scripts/test-trajectory.sh
./gradlew :app:assembleDebug :app:lintDebug
# 修改 C++ 后重新编译三架构 native 库
./scripts/build-native.sh
# Release 需要本机 .signing/offline-mapper.jks 和 .signing/password
./scripts/release.sh
```

每次推送都会通过 GitHub Actions 构建 debug APK，并运行轨迹测试和 Lint，产物可在 Actions 页面下载。

## 说明

本项目为独立实现，不包含任何商业软件的代码、资源或商标。第三方参考见 [第三方说明](docs/第三方说明.md)。不同厂商系统、SELinux 策略和游戏的兼容性需要在目标手机上验证。
