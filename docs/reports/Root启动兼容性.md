# Root启动兼容性修复（1.1.1）

2026-11-07。问题在Android16/SukiSU真机复现：应用已获Root，但一直提示启动超时。阶段日志证实RootDaemon已是UID0，输入库和系统输入接口均成功，Root端监听也建立；应用的connect实际返回Permission denied。旧版丢弃了这个IOException，把连接被拒绝误报成泛化授权超时。

## REA对照所得

从原软件的分析封装实际调用REA恢复TouchService.main、exchangeBinder、ProviderHelper和ShellProcessProvider.call：原架构由Root主动调用App ContentProvider交换双向Binder，再交换SharedMemory/eventfd环形通道；不是普通App主动连接Root域的socket。原加密启动命令没有全部恢复，未冒称完整还原其SU命令。

此次借鉴的是Root主动联系App的初始化方向。独立实现采用较小的反向Unix socket修复，没有复制原Binder/共享内存架构：App先监听随机地址，Root主动回连。没有修改全局SELinux、触摸策略或其他应用，也未引入网络监听。

## 安全与生命周期

- App接入后先核对SO_PEERCRED.uid==0；反向Root端严格核对预期App UID，之后仍进行原随机token认证。
- 仍只接受配置/开始/暂停/状态/停止等既有结构化命令，没有任意shell控制口。
- App使用单调时钟、45秒轮询期限；监听端在成功、异常、超时和停止时关闭。握手提交仍核对会话generation。
- Root端client跨线程发布改为volatile；启动阶段和完整异常栈保留，便于区分加载、输入接口、回连和认证失败。
- 原Root监听模式保留，维持既有集成测试入口与其认证行为。

## 验证

- 同签名覆盖安装1.1.1，未清除应用数据。
- 真机UID0、JNI加载、系统输入接口、回连及认证通过，界面显示Root已激活，双向状态消息正常。
- 用户手动确认关闭服务后再次激活正常；该项为用户实际操作确认，不冒称由自动化完成。
- 引擎录制器回归在该Android16真机通过，包括多指ID、斜向整数截断、定时动作、随机WASD、F8/重载取消和快速转向失败传播；该测试不向游戏注入触控。
- Release编译/Lint、三ABI、16KB静态对齐、v2/v3签名验证通过。未修改WASD坐标算子。

本记录仅说明启动、通信和列明的回归结果，不把它扩大为实体键鼠和实际游戏的全面验收。设备序列号、私人配置及原始日志保留在被Git排除的本地研究目录，未公开上传。
