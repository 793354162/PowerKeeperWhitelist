# PowerKeeper 白名单管理器

一个 LSPosed 模块：**查看并控制 HyperOS / MIUI 的 PowerKeeper 后台运行白名单**。

不改 APK、不动系统分区，全部在 `com.miui.powerkeeper` 进程（`android.uid.system`）内用 hook 完成。

## 功能

- 列出所有应用，显示：
  - 是否在系统 Doze 白名单（`dumpsys deviceidle whitelist` 同源）
  - 当前省电策略（无限制 / 智能 / 受限 / 禁止后台）
  - 模块的强制状态（跟随系统 / 强制允许 / 强制禁止）
- 点击条目循环切换强制状态，立即生效并持久化
- 搜索、显示系统应用、「全部跟随」一键重置

## 原理

PowerKeeper 的规则链路（详见分析）：

```
arrays.xml / assets/local.config / 云端
        ↓
PowerKeeperAppConfigure（add2DeviceIdleWhiteList / scenario）
        ↓
CommonAdapter.addPowerSaveWhitelistApps(IDeviceIdleController, String[])
        ↓
系统 DeviceIdle 白名单
```

模块 hook 三处：

| Hook | 作用 |
|---|---|
| `CommonAdapter.addPowerSaveWhitelistApps` | 按 override 表过滤名单（强制禁止的剔除，强制允许的补齐） |
| `PowerManager.isIgnoringBatteryOptimizations` | 对强制禁止的包返回 false，解掉「智能被吸成无限制」 |
| `PowerSaveConfigureManager.get/setPowerSaveAppConfigure` | 读取/写入时按 override 表改写策略 |

UI 通过 ordered broadcast 与 PowerKeeper 进程通信（`Bridge`），因为 UI 进程没有系统权限。

## 编译

### GitHub Actions（推荐）

推到 GitHub 后，`.github/workflows/build.yml` 会自动：

1. 装 JDK 17 / Android SDK 34 / Gradle 8.7
2. 下载 Xposed API 82（`app/libs/api-82.jar`）
3. `gradle :app:assembleDebug`
4. 上传 `PowerKeeperWhitelist-debug` artifact

在 Actions 页面下载 artifact 即可。

### 本地

需要：JDK 17、Android SDK（platform 34 + build-tools 34）、Gradle 8.7。

```bash
mkdir -p app/libs
curl -L -o app/libs/api-82.jar \
  https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar
gradle :app:assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 安装

1. 设备需已 root：**KernelSU 或 Magisk**
2. 开启 **Zygisk**（Magisk 在设置里开；KernelSU 装 ZygiskNext）
3. 安装 **LSPosed**（Android 15/16 建议用 JingMatrix 分支）
4. 安装本模块 APK
5. 在 LSPosed 里启用模块，**作用域勾选 `com.miui.powerkeeper`**
6. 重启（或强制停止 PowerKeeper）

## 使用

打开「PowerKeeper 白名单」：

- 列表显示每个应用的真实 Doze 状态与策略
- 点击条目：`跟随系统 → 强制允许 → 强制禁止 → 跟随系统`
- 「刷新」重新查询
- 「全部跟随」清空所有强制项

## 已知限制

- 只 hook PowerKeeper，不碰 system_server，PowerKeeper 崩溃不影响系统
- PowerKeeper 升级后类名/方法名可能变化，hook 失败会记录日志但不影响其运行（`XposedBridge.log` 里搜 `PKWL`）
- 广播桥没有鉴权，任何应用都能发 SET 广播；仅在本机自用场景可接受
- 多用户/分身（XSpace）暂只处理 user 0
- 强制允许一个不在任何名单里的应用，需要 PowerKeeper 重新计算后才会出现在系统白名单里

## 目录

```
app/src/main/java/com/yg/pkwhitelist/
├── HookEntry.java      # 入口：hook + 广播桥
├── Bridge.java         # 通信协议常量
├── OverrideStore.java  # 强制策略持久化
├── DeviceIdleApi.java  # 反射 IDeviceIdleController
├── PowerKeeperApi.java # 反射 PowerKeeper 配置 API
├── MainActivity.java   # UI
├── AppEntry.java
└── AppListAdapter.java
```

## 免责

仅供个人设备调试与学习。修改系统电源策略可能影响续航与后台消息到达，请自行承担风险。
