# WhiteList — PowerKeeper 白名单管理器

LSPosed 模块（**libxposed API 102**），查看并控制 HyperOS / MIUI 的 PowerKeeper 后台运行白名单。

不改 APK、不动系统分区、**不需要 Shizuku / SUI**：所有特权操作都在 `com.miui.powerkeeper` 进程（`android.uid.system`）里通过 hook 执行。

## 功能

- 列出应用，显示真实 Doze 白名单状态、当前省电策略、模块强制状态
- 点击卡片循环切换：跟随系统 → 强制允许 → 强制禁止
- 顶部 `diag:` 行显示 hook 状态（`hook=ok idle=true api=102`），便于排查
- 查询 4 秒无响应会直接提示「LSPosed 未启用 / 作用域不对」

## 为什么不需要 Shizuku

Shizuku 是给**普通应用**借 shell 权限用的；本模块的代码运行在 PowerKeeper 进程里，该进程 `sharedUserId=android.uid.system`，本身就有 `CHANGE_APP_IDLE_STATE` 等权限，直接调用 `IDeviceIdleController` 即可。

## 原理

```
arrays.xml / local.config / 云端
        ↓
PowerKeeperAppConfigure（add2DeviceIdleWhiteList / scenario）
        ↓
CommonAdapter.addPowerSaveWhitelistApps(IDeviceIdleController, String[])
        ↓
系统 DeviceIdle 白名单
```

Hook 点：

| Hook | 作用 |
|---|---|
| `CommonAdapter.addPowerSaveWhitelistApps` | 按 override 表过滤名单 |
| `PowerManager.isIgnoringBatteryOptimizations` | 解掉「智能被吸成无限制」 |
| `PowerSaveConfigureManager.get/setPowerSaveAppConfigure` | 按 override 改写策略 |

UI 通过 ordered broadcast 与 PowerKeeper 进程通信，返回结果里带 `diag` 字段。

## 模块声明（API 102）

```
app/src/main/resources/META-INF/xposed/
├── module.prop        # minApiVersion=102 targetApiVersion=102
├── java_init.list     # com.yg.pkwhitelist.HookEntry
└── scope.list         # com.miui.powerkeeper
```

## 编译

推到 GitHub 后由 `.github/workflows/build.yml` 自动编译（JDK 17 + SDK 34 + Gradle 8.7），产物在 Actions 的 `PowerKeeperWhitelist-debug` artifact。

本地编译需要 JDK 17、Android SDK 34、Gradle 8.7。

## 安装

1. KernelSU / Magisk + Zygisk + LSPosed（1.9.2+，支持 API 102）
2. 安装 APK
3. LSPosed 启用模块，作用域 `com.miui.powerkeeper`
4. 重启或强制停止 PowerKeeper

打开应用后看顶部 `diag:`：

- `hook=ok idle=true api=102` → 正常
- `hook=ok idle=false` → DeviceIdle 反射失败（换 ROM 版本可能）
- `no response` → 模块未加载或作用域没勾

调试：`adb logcat -s PKWL`

## 已知限制

- 只 hook PowerKeeper，不碰 system_server
- PowerKeeper 升级后方法名可能变，hook 失败只记录日志，不影响其运行
- 广播桥无鉴权（本机自用）
- 多用户 / 分身只处理 user 0

## 免责

仅供个人设备调试与学习。
