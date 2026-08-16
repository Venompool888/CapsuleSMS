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
> 当前版本 `0.3.0` 优先验证 **OPPO CPH2797 / Android 16 / API 36** 和 **Pixel 10**。它需要成为系统默认短信应用才能完整读取、接收、发送和保存 SMS / MMS。

## 亮点

| | 能力 | 体验 |
|:--:|---|---|
| ✨ | **本地验证码识别** | 保守解析验证码，列表内突出显示，点按即可复制；短信正文不上传 |
| 💬 | **完整 SMS 对话流** | 会话列表、未读计数、搜索、单聊记录、新建短信与长短信发送 |
| 🖼️ | **MMS 与群组消息** | 收发彩信、图片 / 音频 / 视频附件、群组收件人与附件预览 |
| ✓ | **发送状态可追踪** | 展示正在发送、已发送、已送达与发送失败，并聚合长短信分片状态 |
| 📶 | **双卡友好** | 展示 SIM 标识，发送前选择号码，适合双卡 ColorOS 设备 |
| 🗑️ | **可恢复回收站** | 支持逐条恢复或永久删除，并可设置 7 / 30 / 90 天或永久保留 |
| 🎨 | **三套自适应图标** | 棱镜流光、流体省略号、经典消息三种风格，应用内即时切换 |
| ☁️ | **可选端到端同步** | 连接自建服务器，仅向明确选择的设备传送加密信封 |
| 🛡️ | **Local-first** | 默认不连接任何服务器，不接入广告、统计 SDK 或远程验证码服务 |

### 当前能力边界

- 已实现：默认短信角色申请、SMS / MMS 收发、系统消息库读写、群组收件人、媒体附件、会话搜索、联系人选择、验证码识别、通知与 `sms:` / `smsto:` 链接入口。
- 已实现：SMS 的正在发送、已发送、已送达与失败状态；长短信会聚合全部分片的回执后更新状态。
- 已实现：可选云同步客户端，包含密码/一次性注册码连接、二维码扫描、设备路由、端到端加密、离线待发队列、实时长轮询和定时兜底拉取。当前云同步只覆盖 SMS 文本，不同步 MMS 或附件。
- 当前限制：SMS 删除可进入回收站；MMS 及附件删除为永久操作。MMS 已完成系统 API 接入与真机界面冒烟测试，但仍需不同运营商的实际收发验证。GitHub Releases 提供 debug-signed 测试版 APK；尚未提供可复现的正式签名发行版或面向其他机型的兼容性保证。

## 产品原则

- **短信优先：** 围绕阅读、搜索、回复和发送设计，不用无关功能挤占收件箱。
- **本地优先：** 验证码解析、会话搜索和回收站状态全部留在设备上。
- **能力诚实：** README 只描述仓库中已经实现并可验证的能力，不用未来设想代替实现。
- **设备明确：** 当前只承诺 OPPO CPH2797 / Android 16 / API 36，不泛化兼容性结论。
- **误操作可恢复：** SMS 删除先进入回收站；无法恢复的 MMS 删除会在操作前明确提示。

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

## 云备份与同步

该功能默认不显示、不连网、不上传。在“应用图标”选择页连续点击当前图标 3 次，可进入“高级设置 → 云备份与同步”。

- 设备令牌由 Android Keystore 加密后保存。
- 每台设备在 Android Keystore 内生成 P-256 密钥；短信使用 AES-256-GCM 加密，内容密钥通过 ECDH 派生密钥分别包装给各目标设备。
- 服务器仍可观察账号、设备、同步关系、时间和密文大小，但不持有设备私钥或短信明文。
- 连接后会启动低优先级 `remoteMessaging` 前台服务，通过认证长轮询等待新密文，正常网络下可实现秒级同步。
- Android `JobScheduler` 仍以系统允许的最小 15 分钟周期作为兜底；打开应用、保存路由或手动点击“立即同步”也会触发拉取。

## 隐私与权限

验证码识别、会话搜索和回收站状态始终在设备本地完成。`INTERNET` 权限仅供用户主动配置的自建同步服务器使用。

| 权限 | 用途 |
|---|---|
| `READ_SMS` / `RECEIVE_SMS` / `SEND_SMS` | 展示、接收与发送普通短信 |
| `RECEIVE_MMS` / `RECEIVE_WAP_PUSH` | 接收 MMS 通知并通过系统 `SmsManager` 下载彩信内容与附件 |
| `READ_PHONE_STATE` / `READ_PHONE_NUMBERS` | 识别 SIM 与发送号码 |
| `READ_CONTACTS` | 从通讯录选择收件人并显示联系人名称 |
| `POST_NOTIFICATIONS` | 显示新短信通知 |
| `INTERNET` / `ACCESS_NETWORK_STATE` | 可选自建云同步与网络约束 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_REMOTE_MESSAGING` | 维持用户已配置的秒级云同步连接 |
| `RECEIVE_BOOT_COMPLETED` | 在设备重启后恢复实时同步与定时兜底 |

Android 对默认短信应用有严格限制：只有当前默认短信应用可以完整写入系统短信库。请只在测试设备或理解切换影响的前提下使用早期版本。

## 项目结构

```text
app/src/main/java/io/github/venompool888/capsulesms/
├── MainActivity.java              # 收件箱、会话、撰写、回收站与图标设置
├── LauncherIconManager.java       # 动态 launcher 图标切换
└── sms/
    ├── OtpParser.java             # 本地、保守的验证码解析器
    ├── SmsRepository.java         # 系统短信库查询与写入
    ├── SmsSender.java             # 单条 / 长短信、双卡发送与状态回执
    ├── SmsStatusReceiver.java     # 已发送 / 已送达分片回执聚合
    ├── SmsDeliverReceiver.java    # 默认短信应用接收入口
    ├── MmsSender.java             # 系统 MMS 发送与附件封装
    ├── MmsDeliverReceiver.java    # MMS 通知与系统下载入口
    ├── MmsDownloadReceiver.java   # 下载结果持久化与失败重试
    └── TrashStore.java            # 可恢复回收站
└── sync/
    ├── CloudSyncActivity.java     # 连接、设备与路由设置
    ├── CloudSyncEngine.java       # 上传、拉取、去重与写入
    ├── CloudRealtimeService.java  # 前台长轮询与断线重连
    └── CloudCrypto.java           # Keystore ECDH + AES-GCM 信封
```

项目刻意保持小而透明。SMS / MMS 传输使用 Android 系统 API；MMS PDU 的解析和封装使用 `android-smsmms` 中基于 AOSP 的实现，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 路线图

- [x] 默认短信角色与普通短信收发
- [x] 本地验证码识别与一键复制
- [x] 双卡发送、搜索、未读状态与回收站
- [x] 自适应 / 单色图标与应用内切换
- [x] 可选自建服务器与端到端短信同步
- [x] MMS 收发、群组收件人与附件预览
- [x] SMS 发送 / 送达 / 失败状态
- [ ] 跨运营商 MMS 实机兼容性矩阵
- [ ] 更完整的设备兼容性矩阵
- [x] GitHub Releases 测试版 APK
- [ ] 可复现的正式签名发行版
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
