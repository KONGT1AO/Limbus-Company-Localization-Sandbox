# Limbus Localizer — 《边狱公司》Android 免 Root 沙箱汉化启动器

[![Release](https://img.shields.io/github/v/release/KONGT1AO/Limbus-Company-Localization-Sandbox?color=crimson)](https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases)
[![Android 12~17 Supported](https://img.shields.io/badge/Android-12%20~%2017-brightgreen.svg)](#下载与快速上手)
[![License](https://img.shields.io/badge/License-GPL--3.0-lightgrey.svg)](LICENSE)

专为 **《边狱公司》（Limbus Company）** Android 端打造的免 Root 沙箱汉化启动器。  
无需修改正版游戏安装包签名，也无需卸载重下十余 GB 数据，即可在独立容器中体验完整中文汉化与 Google 登录服务。

---

## 📦 下载与快速上手

- **适用设备**：Android 12 ～ Android 17，主流 64 位（`arm64-v8a`）手机，建议预留 **15～20 GB** 以上存储空间
- **下载地址**：前往 [**Releases 页面**](https://github.com/KONGT1AO/Limbus-Company-Localization-Sandbox/releases) 下载最新 `LimbusLocalizer-v*-arm64-v8a.apk`

### 初次使用步骤
1. 先在本机安装官方正版《边狱公司》，再安装本启动器 APK。
2. 打开启动器，在 **「01 启程」** 页同步本机正版游戏（**请勿卸载原机正版 APP**，后续大版本更新后点“重新同步”即可）。
3. 切换到 **「02 汉化」** 页，选择 **在线下载并更新汉化**，或本地导入汉化资源（支持 `.zip` / `.7z` 压缩包或文件夹）。
4. 回到 **「01 启程」**，点击 **「进入《边狱公司》」** 即可开始游玩。

> [!IMPORTANT]
> **更新启动器请直接覆盖安装，千万不要先卸载！**  
> 本应用所有版本均保持统一包名与签名，覆盖安装后，沙箱内已下载的十几 GB 游戏资源与账号登录状态都会完整保留。

---

## 🌟 主要特点

- **免改包更安全**：不改动官方 APK 二进制与签名，降低完整性校验异常与账号风险。
- **周四热更新无忧**：采用纯净本体层与汉化叠加层隔离设计，每周例行更新直接写入游戏层，更新后汉化依然生效，无需每次手动重装补丁。
- **高还原度中文排版**：
  - 内置更纱黑体矢量字库，字迹清晰、不缺字；
  - 完整适配第 6 章 / 第 10 章等动态渐变与打字机台词效果；
  - 修复战斗超长技能横幅截断、人格卡标题折行重叠以及 UI 材质发白失色等问题，加载界面随机战斗小贴士也已全部汉化。
- **支持 Google 账号登录**：内置 microG 与基础验证环境，可在容器内直接登录绑定正版账号。
- **切后台保活与多包管理**：切回桌面回消息更稳健，同时支持多汉化包管理、版本锁定与编辑删除。

---

## ❓ 常见问题

- **Q：更新启动器需要重新下载十几 GB 游戏资源吗？**  
  不需要。只要**不卸载旧版**、直接下载新 APK 覆盖安装，所有数据都会原样继承；官方大版本更新后在「01 启程」重新同步一次原版 APK 即可，不会清除容器内资源。
- **Q：遇到文字异常、闪退或黑屏怎么反馈？**  
  可以在启动器 **「03 维护」** 页面点击导出诊断包（工具会自动剔除账号等个人隐私信息），连同截图一并提交 Issue。

---

## ⚠️ 免责声明 (Disclaimer)

1. 本项目为社区开源技术探索项目，**非** Project Moon 官方产品，与官方无任何隶属或授权关系。
2. 《边狱公司》（Limbus Company）游戏本体及其美术、音乐、文本等相关权利均归 **Project Moon** 所有；配套使用的中文汉化文本版权归对应翻译团队所有。
3. 请使用者知悉第三方沙箱工具的潜在不确定性并自行承担使用风险。
