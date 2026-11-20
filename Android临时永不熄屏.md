# 用 ADB 临时延长 Android 手机的熄屏时间

适用于需要让手机保持亮屏的安装、真机测试或自动化操作。先保存当前设置，再把自动熄屏超时设为 `2147483647` 毫秒（约 24.9 天）。任务结束后写回原值并确认。

在 REDMI K90 Pro Max（HyperOS）上通过无线 ADB 实测成功。测试前的值为 `300000` 毫秒（5 分钟），结束后读回同一数值。

## 1. 确认目标手机

```bash
adb devices -l
```

从列表中找到真机，并确认它的状态是 `device`。把下文的 `替换为目标手机序列号` 改为列表第一列。无线 ADB 也可使用该列表显示的 mDNS 设备名。列表为空时，在手机上打开无线调试并确认主页面的连接地址和端口。

## 2. 保存原值并临时延长

以下命令使用 Bash 语法。把状态文件的路径记在任务记录里，换终端或重连后也能用它恢复。

```bash
device='替换为目标手机序列号'
state_dir=$(mktemp -d "${TMPDIR:-/tmp}/android-screen-timeout.XXXXXX")

if [ -n "$state_dir" ] && [ "$(adb -s "$device" get-state)" = device ] &&
   original=$(adb -s "$device" shell settings get system screen_off_timeout) &&
   printf '%s\n' "$original" | grep -Eq '^(null|[0-9]+)$'; then
  printf '%s\n' "$device" > "$state_dir/device" &&
  printf '%s\n' "$original" > "$state_dir/original" &&
  printf '原值 %s，恢复文件 %s/original\n' "$original" "$state_dir" &&
  adb -s "$device" shell settings put system screen_off_timeout 2147483647 &&
  adb -s "$device" shell settings get system screen_off_timeout
else
  printf '设备未连接或设置读取失败，请先检查 ADB 连接。\n' >&2
fi
```

最后一行的读回结果应为 `2147483647`。也可以检查系统电源服务采用的超时值。

```bash
adb -s "$device" shell dumpsys power | grep 'Screen off timeout'
```

如果读回值或实际行为有差异，检查手机的设备管理策略、省电限制或第三方黑屏覆盖。已经锁屏时，需要先手动解锁。

## 3. 结束后恢复并验证

使用第二步保存的状态目录。同一终端可直接使用 `state_dir`。换终端时，把 `state_dir` 设为状态文件所在的目录。原值为 `null` 表示该设置项原来没有显式值，用 `settings delete` 恢复默认行为。

```bash
device=$(cat "$state_dir/device")
original=$(cat "$state_dir/original")

if [ -n "$device" ] && [ "$original" = null ]; then
  adb -s "$device" shell settings delete system screen_off_timeout
elif [ -n "$device" ] && printf '%s\n' "$original" | grep -Eq '^[0-9]+$'; then
  adb -s "$device" shell settings put system screen_off_timeout "$original"
fi

if [ -n "$device" ] && printf '%s\n' "$original" | grep -Eq '^(null|[0-9]+)$' &&
   actual=$(adb -s "$device" shell settings get system screen_off_timeout) &&
   [ "$actual" = "$original" ]; then
  printf '已确认恢复原值 %s\n' "$actual"
else
  printf '恢复待确认，保留原值文件 %s/original\n' "$state_dir" >&2
fi
```

自动化任务应把恢复步骤放入退出清理流程，在安装失败、测试中断和正常完成时都执行。如果恢复时连接中断，保留原值文件并在设备重新连上后继续恢复，直到读回一致。
