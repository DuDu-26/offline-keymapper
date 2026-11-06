# 原字节码对照测试

这是本项目自己编写的计算与状态测试工具。它在一次性 Android 模拟器上执行本地提供的原 DEX，并与当前源码比较；不启动原 APK、不加载原授权库、不修改原字节码，也不处理原账号授权。

参考 DEX 不随仓库分发。当前测试针对 SHA-256 `457095c52c1055aa7df539284a34975718a667ad7c812b3cc1e4938c60435fcf` 的研究对象，其他文件会被拒绝。

需要 JDK17、Android SDK35 / Build Tools35.0.0，以及自行创建并启动的 Android arm64 API33 测试 AVD。可以通过 `JAVA_HOME` 和 `ANDROID_SDK_ROOT` 指定工具位置；脚本会检查设备确实是指定名称的模拟器，避免误操作手机。

```sh
python3 scripts/joystick-oracle/run_oracle.py \
  --serial emulator-5560 \
  --avd-name MapperResearch20261014 \
  --reference-dex /path/to/local-reference.dex
```

结果默认写入被 Git 排除的 `research/joystick-oracle/`，保留候选源码快照、SHA-256和机器可读结果；本工具不会向 GitHub 上传文件。

对照包含25组随机轨迹、8普通方向、20组时间分配、16方向组合及3条控制序列。两个强度各覆盖五类路径。为可复现，测试实例固定随机种子，初段时长/点数使用37ms/22点；原构造范围和其他等待值另行检查。它对比注入前的计算结果，不能证明真实系统调度、硬件、游戏行为或账号安全。
