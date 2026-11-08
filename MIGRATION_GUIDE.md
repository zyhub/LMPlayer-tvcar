# LMPlayer TV 车机与电视端工程迁移与更新指南

> 本目录为 **LMPlayer TV（柠檬音乐车机/电视端）** 纯净独立工程，已剔除所有编译缓存（`.gradle`、`build/`）、旧版 APK 安装包与临时日志，代码体积由原来的 470MB+ 缩减至 **~5.1MB**，包含车机大屏交互、横屏适配、TV 遥控器按键导航、所有源码以及车机端专用正式签名凭据。

---

## 一、 快速导入与编译指南

### 1. 导入 Android Studio
- 打开 Android Studio，点击 **Open**，直接选中本 `LMplayer TV` 文件夹即可导入。
- Android Studio 会自动根据当前电脑的 Android SDK 路径更新 `local.properties` 中的 `sdk.dir`。

### 2. 命令行一键编译
在当前根目录下执行：
- **Windows**:
  ```bat
  gradlew.bat assembleRelease
  ```
- **macOS / Linux**:
  ```bash
  chmod +x gradlew
  ./gradlew assembleRelease
  ```
- 编译生成的带正式签名的安装包位于：`app/build/outputs/apk/release/app-release.apk`。

---

## 二、 签名与密钥配置说明（防止换电脑失效）

车机端采用独立专用签名，已配置**双重凭据守护机制**，防止换电脑时因 Android Studio 重置 `local.properties` 导致签名丢失：

| 配置项 | 文件位置 | 配置内容 / 凭据说明 |
| :--- | :--- | :--- |
| **密钥库文件 (Keystore)** | `app/lmplayer-tv-release.jks` | 车机端专用正式签名密钥库 |
| **主签名配置文件** | `signing.properties` | 根目录独立凭据文件（防止换电脑被重写） |
| **备用签名配置文件** | `local.properties` | 兼容旧版构建脚本的环境读取配置 |
| **Store Password** | 配置文件内读取 | `lmplayer2026` |
| **Key Alias** | 配置文件内读取 | `lmplayer_tv` |
| **Key Password** | 配置文件内读取 | `lmplayer2026` |

> [!IMPORTANT]
> 在通常情况下，换电脑导入 Android Studio 时，系统会自动重写 `local.properties` 并覆盖掉其中的自定义配置。
> 为彻底杜绝此问题，本工程已在 `app/build.gradle.kts` 中添加了对 `signing.properties` 的自动加载支持。只要 `signing.properties` 和 `app/lmplayer-tv-release.jks` 存在，无论换到哪台电脑，构建系统都能自动注入正确签名，**确保更新安装时不会因证书不一致导致覆盖安装失败**！

---

## 三、 工程核心目录结构

```
LMplayer TV/
├── app/                           # 车机端核心应用模块
│   ├── build.gradle.kts           # 模块级构建脚本（已配置正式签名与混淆规则）
│   ├── lmplayer-tv-release.jks    # 车机端官方正式签名证书
│   ├── proguard-rules.pro         # R8 / ProGuard 混淆保护规则
│   └── src/
│       └── main/                  # 核心源码 (车机焦点控制、遥控器导航、大屏UI与资源)
├── gradle/wrapper/                # Gradle Wrapper (8.7)
├── docs/                          # 车机设计规范与界面说明文档
├── .gitignore                     # Git 忽略文件（已配置忽略临时 build 产物）
├── build.gradle.kts               # 顶层项目构建脚本
├── settings.gradle.kts            # 项目模块定义 (include(":app"))
├── gradle.properties              # JVM 与 AndroidX 配置
├── gradlew / gradlew.bat          # 跨平台 Gradle 包装器执行脚本
├── signing.properties             # 独立签名配置文件（防重置守护）
├── local.properties               # 本机 SDK 路径与兼容签名配置
├── README.md                      # 车机端说明文档
└── tvcar审计报告.md                 # 车机与 TV 适配深度代码审计报告
```
