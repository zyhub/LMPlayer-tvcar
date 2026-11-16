<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="LMPlayer TV" />
</p>

<h1 align="center">LMPlayer TV</h1>

<p align="center">柠檬音乐服务端的电视 / 机顶盒 / 车机客户端</p>

<p align="center">
  <a href="https://github.com/zyhub/LMPlayer-tvcar/releases">下载</a> ·
  <a href="https://github.com/jia070310/lemon-muisc">柠檬服务端</a> ·
  <a href="https://github.com/zyhub/LMplayer">手机版</a> ·
  <a href="LICENSE">许可证</a>
</p>

## 简介

LMPlayer TV 是 [柠檬音乐服务端（lemon-muisc）](https://github.com/jia070310/lemon-muisc) 的横屏大屏客户端，与手机版分开发布。需要连接使用者自己部署的柠檬服务端使用，曲库、歌单、收藏、在线检索与推荐均由服务端提供，客户端本身不内置任何曲库。同时支持扫描播放本机已有的音乐文件。

## 功能

- **播放页三种主题**：黑胶唱片、专辑卡片、全屏封面 + 歌词，共用一个可自动收起的控制胶囊，歌词支持逐字效果。
- **遥控器与触控双模式**：首次启动选择「电视 / 机顶盒」或「车机 / 中控」，遥控器方向键全程可聚焦，触控模式放大点按区域。
- **车机语音点歌**：可配合车机语音助手点歌，优先播放本机已有的歌曲。
- **启动与流畅度**：启动预加载、续播快速恢复、切页保留页面，歌词渲染针对车机性能优化。
- **本地播放加速**：服务器歌曲预匹配、歌词与封面竞速加载、已缓存歌曲直接播放。
- **歌单与下载**：多选加入 / 移出歌单；按所选音质下载，已有且音质达标的歌曲自动跳过，全局下载窗口查看进度。
- **更多**：点歌台首字母快搜、播客、桌面小组件、应用内检查更新。

## 下载安装

在 [Releases](https://github.com/zyhub/LMPlayer-tvcar/releases) 下载最新的 `LMPlayerTV-<版本号>.apk`，用 U 盘拷贝或在设备上直接下载安装（首次需允许安装未知来源应用）。

- 系统要求：Android 6.0 及以上
- 支持架构：arm64-v8a、armeabi-v7a、x86_64
- 安装包使用固定签名，新版本可直接覆盖安装
- 应用内「设置 → 检查更新」读取本仓库的 Releases，发布新版本后设备端即可直接更新

## 编译

环境：JDK 17、Android SDK 34。

```bash
git clone https://github.com/zyhub/LMPlayer-tvcar.git
cd LMPlayer-tvcar
./gradlew :app:assembleRelease
```

签名密钥不包含在本仓库中。release 构建会从 `local.properties`（`lmplayer.storeFile` / `lmplayer.storePassword` / `lmplayer.keyAlias` / `lmplayer.keyPassword`）或同名环境变量（`LMPLAYER_STORE_FILE` 等）读取签名信息；未配置时仍可编译，只是产出未签名的 APK。

## 致谢

- [柠檬音乐服务端 lemon-muisc](https://github.com/jia070310/lemon-muisc)：本应用连接的服务端。
- AndroidX Media3、Jetpack Compose、Room、OkHttp、Retrofit、Coil、quickjs-kt 等开源项目，各自遵循其开源许可证。

## 免责声明

本项目仅供个人学习与技术研究，不提供、不存储、不分发任何音频、歌词或封面等版权内容，播放的内容均来自使用者自己部署的服务端或本机文件，相关版权归原权利人所有。界面中出现的第三方平台名称仅用于说明服务端数据的来源分类，不代表与相关平台存在任何合作或授权关系。请勿将本项目用于任何商业用途，并请支持正版音乐。因使用本项目产生的任何纠纷与后果，由使用者自行承担。

## 许可证

[MIT](LICENSE) © 2026 Zhou (zyhub)
