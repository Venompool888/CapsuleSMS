<div align="center">
  # 胶囊短信 · Capsule SMS

  **一款为 OPPO / ColorOS 打磨的轻盈短信应用。**<br>
  把验证码、双卡收发与日常对话，装进一颗安静、流动的胶囊里。

  [![Android](https://img.shields.io/badge/Android-16%20%7C%20API%2036-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com/about/versions/16)
  [![Build](https://img.shields.io/github/actions/workflow/status/Venompool888/CapsuleSMS/android.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Build)](https://github.com/Venompool888/CapsuleSMS/actions/workflows/android.yml)
  [![License](https://img.shields.io/github/license/Venompool888/CapsuleSMS?style=for-the-badge&color=7c5cff)](LICENSE)
  [![Privacy](https://img.shields.io/badge/Privacy-Local%20First-00B8A9?style=for-the-badge&logo=shield&logoColor=white)](#隐私与权限)

  [功能亮点](#亮点) · [开始构建](#开始构建) · [产品原则](#产品原则) · [路线图](#路线图)
</div>

---

## 为什么是胶囊短信？

短信不该只是一个堆满通知的旧收件箱。胶囊短信把最常用的动作重新排布：快速找到对话、在列表里识别验证码、双卡选择发送号码、误删后随时找回。界面遵循 ColorOS 的圆润节奏，但保持原生 Android 的轻巧与直接。

> [!IMPORTANT]
> 当前版本 `0.1.0` 是专为 **OPPO CPH2797 / Android 16 / API 36** 构建的早期版本。它需要成为系统默认短信应用才能完整读取、接收和保存短信；MMS 完整收发尚未实现。

## 亮点

| | 能力 | 体验 |
|:--:|---|---|
| ✨ | **本地验证码识别** | 保守解析验证码，列表内突出显示，点按即可复制；短信正文不上传 |
| 💬 | **完整 SMS 对话流** | 会话列表、未读计数、搜索、单聊记录、新建短信与长短信发送 |
| 📶 | **双卡友好** | 展示 SIM 标识，发送前选择号码，适合双卡 ColorOS 设备 |
| 🗑️ | **可恢复回收站** | 支持逐条恢复或永久删除，并可设置 7 / 30 / 90 天或永久保留 |
| 🎨 | **三套自适应图标** | 棱镜流光、流体省略号、经典消息三种风格，应用内即时切换 |
| 🛡️ | **Local-first** | 不申请网络权限，不接入广告、统计 SDK 或远程验证码服务 |

### 当前能力边界

- 已实现：默认短信角色申请、SMS 接收与发送、系统短信库读写、会话搜索、联系人选择、验证码识别、回收站、通知与 `sms:` / `smsto:` 链接入口。
- 部分实现：已声明默认短信应用所需的 MMS 组件与入口，但收到 MMS 时只会提示暂不支持。
- 尚未提供：发行版 APK、完整 MMS 收发、云同步、跨设备同步，以及面向其他机型的兼容性保证。

## 产品原则

- **短信优先：** 围绕阅读、搜索、回复和发送设计，不用无关功能挤占收件箱。
- **本地优先：** 验证码解析、会话搜索和回收站状态全部留在设备上。
- **能力诚实：** README 只描述仓库中已经实现并可验证的能力，不用未来设想代替实现。
- **设备明确：** 当前只承诺 OPPO CPH2797 / Android 16 / API 36，不泛化兼容性结论。
- **误操作可恢复：** 删除先进入回收站，真正清除前保留恢复路径。

## 开始构建

### 环境要求

- macOS / Linux / Windows
- Android Studio 与 Android SDK 36
- JDK 17 或更新版本
- 一台 Android 16（API 36）设备；当前验证目标为 OPPO CPH2797

```bash
git clone https://github.com/Venompool888/CapsuleSMS.git
cd CapsuleSMS
./gradlew assembleDebug
```

APK 将生成在：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次启动后，请按界面引导授予必要权限并将胶囊短信设为默认短信应用。若只想浏览源码或运行单元测试，无需连接手机：

```bash
./gradlew testDebugUnitTest
```

## 隐私与权限

胶囊短信不声明 `INTERNET` 权限。验证码识别、会话搜索和回收站状态都在设备本地完成。

| 权限 | 用途 |
|---|---|
| `READ_SMS` / `RECEIVE_SMS` / `SEND_SMS` | 展示、接收与发送普通短信 |
| `RECEIVE_MMS` / `RECEIVE_WAP_PUSH` | 满足默认短信应用组件要求；完整 MMS 尚未实现 |
| `READ_PHONE_STATE` / `READ_PHONE_NUMBERS` | 识别 SIM 与发送号码 |
| `READ_CONTACTS` | 从通讯录选择收件人并显示联系人名称 |
| `POST_NOTIFICATIONS` | 显示新短信通知 |

Android 对默认短信应用有严格限制：只有当前默认短信应用可以完整写入系统短信库。请只在测试设备或理解切换影响的前提下使用早期版本。

## 项目结构

```text
app/src/main/java/io/github/venompool888/capsulesms/
├── MainActivity.java              # 收件箱、会话、撰写、回收站与图标设置
├── LauncherIconManager.java       # 动态 launcher 图标切换
└── sms/
    ├── OtpParser.java             # 本地、保守的验证码解析器
    ├── SmsRepository.java         # 系统短信库查询与写入
    ├── SmsSender.java             # 单条 / 长短信与双卡发送
    ├── SmsDeliverReceiver.java    # 默认短信应用接收入口
    └── TrashStore.java            # 可恢复回收站
```

项目刻意保持小而透明：主应用使用 Android 原生 API，当前没有运行时第三方依赖。

## 路线图

- [x] 默认短信角色与普通短信收发
- [x] 本地验证码识别与一键复制
- [x] 双卡发送、搜索、未读状态与回收站
- [x] 自适应 / 单色图标与应用内切换
- [ ] 完整 MMS 收发与附件预览
- [ ] 更完整的设备兼容性矩阵
- [ ] 可复现的签名发行版与 GitHub Releases
- [ ] 在隐私边界内探索分类与摘要能力

## 参与项目

欢迎提交 Issue、设计建议或兼容性报告。报告设备问题时，请附上机型、Android / ColorOS 版本和可复现步骤，但**不要上传真实短信、手机号、验证码或联系人信息**。

本项目采用 [MIT License](LICENSE) 开源。`OPPO`、`ColorOS` 与 Android 相关标识归各自权利人所有；本项目与 OPPO 官方无隶属或背书关系。

---

<div align="center">
  <strong>让每一条重要短信，都能在需要时浮上来。</strong>
  <br><br>
  Made with care for ColorOS · 2026
</div>
