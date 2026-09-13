# PSA Media Plugin (PSA 多媒体插件)

![Claude](https://img.shields.io/badge/AI%20Assisted-Claude-orange?logo=anthropic&logoColor=white)
[![Android Build](https://github.com/ShaobaiLee/PSA-MediaPlugin/actions/workflows/android.yml/badge.svg)](https://github.com/ShaobaiLee/PSA-MediaPlugin/actions/workflows/android.yml)

一个轻量级的多媒体插件，专为 Blue-i 3.0 车机系统设计。用于桥接第三方音乐 APP，使其能够通过系统的原厂桌面卡片进行显示与控制。

## ✨ 主要功能

- **无缝集成**：替换默认的媒体服务，读取并显示第三方 APP 的媒体元数据。
- **系统控制**：支持通过桌面组件直接控制播放/暂停、上一首和下一首，无需依赖第三方服务。
- **自动同步**：自动同步当前媒体会话的播放进度、时长和状态。

## 📱 应用兼容性

本插件通过 Android 的 `NotificationListenerService` 获取媒体信息。以下是主流音乐 APP 的兼容性测试状态：

| 音乐 APP | 歌曲名 | 歌手名 | 专辑封面 | 播放控制 |
| :--- | :---: | :---: | :---: | :---: |
| **QQ 音乐车机版** | ✅ | ✅ | ✅ | ✅ |
| **酷狗音乐车机版** | ✅ | ✅ | ✅ | ✅ |
| **酷我音乐车机版** | ✅ | ✅ | ✅ | ✅ |
| **网易云音乐车机版** | ✅ | ✅ | ✅ | ✅ |
| **汽水音乐车机版** | ✅ | ✅ | ✅ | ✅ |
| **Spotify** | ❓ | ❓ | ❓ | ❓ |
| **Apple Music** | ❓ | ❓ | ❓ | ❓ |

> **说明**: 兼容性取决于 APP 是否在通知栏中通过标准的 `MediaMetadata` 暴露媒体信息。
> - ✅ : 完美支持
> - ❌ : 不支持 / 存在问题
> - ❓ : 尚未测试

## 🛠️ 安装指南

1. 拉取默认分支最新代码。
2. 编译后安装到您的车机上。
3. 打开 **多媒体插件** 应用。
4. 授予 **通知访问权限**（这是获取歌名和封面的必要条件）。
5. 插件将自动在后台运行。

## ⚠️ 恢复与卸载

如果您希望恢复原厂的多媒体功能：
1. 打开 **多媒体插件** 应用。
2. 点击底部的 **"恢复多媒体"** 按钮。
3. 在弹出的系统对话框中确认卸载插件即可。

## 🤝 贡献与反馈

欢迎提交 Pull Request 或 Issue 来帮助改进这个项目！

1. Fork 本项目
2. 创建您的特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交您的修改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

## 📄 许可证

本项目采用 **[CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/)** (署名-非商业性使用-相同方式共享 4.0 国际) 协议进行许可。

您有权：
- **共享** — 在任何媒介以任何形式复制、发行本作品。
- **演绎** — 修改、转换或以本作品为基础进行创作。

但在行使上述权利时必须遵守以下条件：
- **非商业性使用** — 您不得将本作品用于商业目的。
- **相同方式共享** — 如果您再混合、转换或者基于本作品进行创作，您必须基于与原先许可协议相同的许可协议分发您贡献的作品。
