# Dev常亮 (DevAwake)

安卓开发者的实用小工具：手机连接电脑（USB 有线或无线调试）时自动进入「开发模式」——开启充电时保持亮屏、关闭自动亮度并把亮度调至最低；断开连接或只接充电头/充电宝时自动恢复连接前的设置。

适配 HyperOS / MIUI 的广播限制与「连电脑被识别为 AC 充电」等厂商行为。

## 功能

- **两种连接场景，独立开关**：有线 USB 连电脑 / 无线调试（adb over Wi-Fi）连入，各场景四项动作（启用、充电时保持亮屏、关闭自动亮度、亮度调至最低）独立开关，默认全部开启
- **改动即时生效**：调整当前生效场景的开关，立即恢复对应项并按新配置重新应用
- **精确恢复**：保存连接前的设置原值，断开后原样写回；只接充电头/充电宝不会误触发
- **有线/无线自动交接**：同时连接时有线优先；拔线后若无线调试仍连着电脑，自动切换到无线场景
- **双写入通道**：系统权限直写（`WRITE_SECURE_SETTINGS` + 「修改系统设置」appop），缺权限时经 Shizuku 以 shell 身份写入；两张设置表可分别选择通道、自动混用

## 连接判定原理

- **有线连电脑**：有供电 且 USB 已被主机枚举（`USB_STATE` 的 `connected=true`）。
  不能只看电池上报的 USB/AC 类型——小米会把连电脑识别成 AC 充电。
- **无线调试连入**：每 2.5 秒经 Shizuku 检查 `netstat`，存在「本端端口是通配地址上的监听端口、
  远端非回环」的 ESTABLISHED 连接即判定有电脑连入调试端口（本机出站连接使用临时端口，不误判）。
  连接确认 2 次、断开确认 3 次去抖；无线调试开关关闭时立即判定断开。
- 保持亮屏写 `stay_on_while_plugged_in = 15`（AC|USB|无线|底座，与开发者选项行为一致）。

## 为什么需要常驻前台服务

HyperOS 会把电源广播按「后台执行不允许」策略跳过清单接收器（连 GMS 也一样），
所以插拔事件由前台服务里动态注册的接收器处理，无线检测由轮询线程完成，
进程靠一条静音低优先级通知保活。注意：HyperOS 的 SmartPower 仍会在应用退后台约 40 秒后
杀掉未开「自启动」的应用进程，**必须手动开启自启动并锁定应用**（见下）。

## 安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 一次性授权（不依赖 Shizuku；覆盖安装后可能需重跑）
adb shell pm grant com.halo.devawake android.permission.WRITE_SECURE_SETTINGS
adb shell appops set com.halo.devawake WRITE_SETTINGS allow
adb shell pm grant com.halo.devawake android.permission.POST_NOTIFICATIONS
```

安装后打开一次应用以启动前台服务。小米 / HyperOS 必须在「应用信息」里打开
「自启动」、把省电策略设为无限制，并在最近任务里锁定本应用，否则检测会失效。

Shizuku 兜底：应用内点「申请 Shizuku 授权」。

## 构建

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17 与 Android SDK（compileSdk 35）。无线调试检测依赖
[Shizuku](https://github.com/RikkaApps/Shizuku) API。

## 调试

```bash
adb logcat -s 'DevAwake:*'
adb shell dumpsys battery unplug   # 模拟拔出电池（线仍连着时 USB_STATE 不变，不触发恢复）
adb shell dumpsys battery reset    # 回到真实状态
# 无线断开实测：adb kill-server 后约 7.5s 应用应恢复；重连后重新应用
```

## 许可

[MIT](LICENSE)
