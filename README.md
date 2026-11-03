# Limbus Localizer — 《边狱公司》Android 免 Root 沙箱汉化启动器

[![Android 12~17 Supported](https://img.shields.io/badge/Android-12%20~%2017-brightgreen.svg)](#系统兼容性)
[![Architecture](https://img.shields.io/badge/Architecture-arm64--v8a-blue.svg)](#构建说明)
[![Target SDK](https://img.shields.io/badge/Target%20SDK-35-orange.svg)](#构建说明)
[![License](https://img.shields.io/badge/License-GPL--3.0-lightgrey.svg)](LICENSE)

专为 **《边狱公司》（Limbus Company）** Android 端构建的单应用免 Root 虚拟化沙箱汉化启动器。

基于深度定制的 **VirtualApp 容器内核** 与 **IL2CPP 原生运行时动态注入引擎**，在不修改官方 APK 原生签名与本体二进制文件的前提下，原生承载 Google Play 正版客户端，提供**容器内热更新隔离**、**内置 microG Google 服务**与**高品质中文字体排版渲染**。

---

## 🌟 核心特性

### 1. 动态 IL2CPP 运行时汉化引擎（免改包运行架构）
- **动态符号反射与 Native Hook**：基于 `libv++_64.so` 在 Unity 进程底层挂钩 TextMeshPro（TMP）与 UGUI 文本流，在内存层动态匹配日文/韩文字符串并替换为对应中文。
- **免改包字体注入与动态字形预热**：主线程动态反射载入中文矢量字体（更纱黑体 / Sarasa Gothic），自动创建并挂载中文 `TMP_FontAsset`；对未激活（Inactive）预制体及动态图集提供字形预热，有效避免缺字方块与字符乱码。
- **专业级排版与视觉定制**：
  - **战斗技能横幅**：适配 Regular 字体资产 + 0.14f 膨胀度（`FaceDilate`）+ 紧凑单行自适应排版，笔画清晰开阔，保持技能名称完整呈现且兼顾战局视野；
  - **UI 界面与结算面板**：深度适配 Unity UGUI `Mask` 与 `RectMask2D` 遮罩管线，镜像迷宫结算奖励弹窗、背包、人格详情等均完整呈现原生质感与阴影（Underlay）。

### 2. 双层存储隔离架构（周四增量热更新兼容）
- **Clean Game Data（纯净本体层）**：沙箱内保持官方原始文件结构，十几 GB 游戏本体资源与官方增量热更新直接写入该层，确保官方热更新与资源校验顺畅通行。
- **Merged Cache（汉化叠加层）**：通过内核级 `openat` / `fstatat` 智能重定向，写操作安全穿透至纯净层，校验通过后的只读操作无缝切换至中文合并缓存。

### 3. 免 Root 容器内 Google 账号支持
- 深度预置 **microG (`com.google.android.gms`)** 与 **FakeStore (`com.android.vending`)** 核心支持。
- `VPackageManager` 官方证书 `SHA-1` 透明透传代理，配合进程级 `WebView.setDataDirectorySuffix` 存储隔离与 `recaptcha://` 认证跳板，提供便捷的容器内 Google 账号登录体验。

### 4. 易用的管理与诊断启动台
- **Jetpack Compose 现代化启动台**：一键检测游戏安装状态、汉化包版本更新与沙箱运行环境。
- **脱敏日志导出**：内置一键导出诊断日志功能，自动剔除个人账号与敏感路径信息，方便排查兼容性问题。

---

## 🛠️ 工程模块架构

```text
Limbus-Company-Localization-Sandbox/
├── app/                  # 宿主启动器应用（Jetpack Compose，启动台/环境检测/汉化更新/脱敏诊断）
└── third_party/
    └── virtualapp-upstream/
        └── lib/          # VirtualApp 核心虚拟化容器内核
            └── src/main/jni/
                └── Foundation/
                    ├── LimbusTranslationRuntime.cpp  # IL2CPP 汉化引擎核心与 TMP/UI Hook
                    ├── LimbusTextLayoutPolicy.h     # 中文字体、排版规则与 FaceDilate 视觉策略
                    ├── IORelocator64.cpp            # 虚拟文件 IO 智能重定向
                    └── MapsRedirector.cpp           # 内存 Maps 特征隐藏与虚拟环境适配
```

---

## 📱 系统兼容性

- **支持架构**：仅支持 64 位 `arm64-v8a`（现代主流 Android 设备）。
- **支持 Android 版本**：Android 12 (API 31) ～ **Android 17 (API 36+)**。
- **真机实测涵盖**：
  - vivo / iQOO（OriginOS 5 / Android 17）
  - 一加 / OPPO（ColorOS 15 / Android 16）
  - 小米 / 红米（Xiaomi HyperOS / Android 14+）

---

## 🔨 构建说明

### 环境要求
1. **操作系统**：Windows 10/11、Linux 或 macOS
2. **JDK**：OpenJDK 17（推荐 Temurin 或 Eclipse Adoptium JDK 17）
3. **Android SDK**：API 35（Android 15+）
4. **Android NDK**：`27.0.12077973`
5. **CMake**：`3.22.1`

### 命令行构建

在工程根目录下执行：

**Windows PowerShell 示例：**
```powershell
# 配置环境变量（请根据本机实际安装路径调整）
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.x"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 执行编译与打包
cmd.exe /c "gradlew.bat assembleDebug --no-daemon 2>&1"
```

**Linux / macOS 示例：**
```bash
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
export PATH="$JAVA_HOME/bin:$PATH"

./gradlew assembleDebug --no-daemon
```

构建成功后产物位于：
`app/build/outputs/apk/debug/app-debug.apk`

---

## ⚠️ 免责声明 (Disclaimer)

1. **项目定位**：本项目为开源的技术探索与本地化学习研究成果，由社区开发者独立维护，**非** Project Moon 官方产品，亦未获得 Project Moon 的授权、赞助或背书。
2. **游戏版权**：《边狱公司》（Limbus Company）及其相关的一切美术、音频、游戏文本、商标及知识产权均归属于 **Project Moon** 官方所有。
3. **翻译文本**：本项目所配套的中文汉化资源来自于社区翻译团队或开源本地化项目，文本版权归原翻译作者所有。
4. **风险须知**：本项目通过免修改 APK 的内存及虚拟沙箱技术实现本地化，旨在最大限度保留正版游戏环境。但鉴于网络游戏运营策略及客户端安全机制的不确定性，开发者不对任何使用本工具所导致的账号异常、数据受损或其他损失承担保证或责任，请使用者充分知悉并自行承担相关风险。
