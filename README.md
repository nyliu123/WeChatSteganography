# WeSteg · 微信隐写工具

WeSteg 把可打印的 ASCII 明文转换为不可见字符，可以将它们放在一段公开文字前面，再从收到的混合文本中提取明文。Windows 和 Android 使用相同的编码格式，可以互相解码。

## 获取应用

| 平台                   | 本地发布文件                                                            | 使用方式                                                           |
| ---------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Windows x64            | ./bin/Release/2.0.3/win-x64-portable/WeSteg.exe | 双击 `WeSteg.exe`。单文件约 155 MiB，内置 .NET 8 Desktop Runtime。 |
| Android 6.0 或更新版本 | ./bin/Release/2.0.2/android/WeSteg-2.0.2.apk  | 在手机上打开 APK 并安装。                                          |

发布文件位于本地 `bin/`。如果手头只有源码，可以按下方步骤自行构建。

## 使用方法

1. 在「编码」页输入要隐藏的明文，点击「编码」，再点击「复制编码结果」。这个按钮会复制实际的不可见字符和混淆消息，界面里的灰色 `<0x…>` 是便于查看和编辑的标记。
2. 将复制的结果粘贴到聊天框。接收方把收到的整段文本粘贴进「解码」页的「待检测文本」，再点击「检测并解码」。
3. 解码页会显示不可见字符总数和「有效字符」数。有效字符按解码器识别的载体逐个计数，每个明文字符由两个载体表示。

### 添加混淆消息

明文仅支持 ASCII 32–126。首个未转义的 `\` 分隔明文和混淆消息，明文中的 `\\` 表示一个待编码的反斜杠。分隔符后可以写中文、emoji 或多行文字，应用会先输出隐藏字符，再原样附上这段混淆消息。

| 编码框输入          | 隐藏的明文 | 公开显示的文字 |
| ------------------- | ---------- | -------------- |
| `Hello`             | `Hello`    | 无             |
| `Hello\今晚见`      | `Hello`    | `今晚见`       |
| `Hi\\there\你好 👋` | `Hi\there` | `你好 👋`      |

## 实现原理

WeSteg 把 10 种不可见 Unicode 字符按固定顺序映射为数字 0–9。两个载体可以表示 `00`–`99`，其中 `00`–`94` 按顺序对应 ASCII 32–126 的 95 个可打印字符，从空格到 `~`。`95`–`99` 不对应任何明文字符。

根据项目目前的微信文本收发实测，下表中的 10 个字符在测试中均被微信保留。

| 数字 | Unicode 码点 |
| ---- | ------------ |
| 0    | `U+200C`     |
| 1    | `U+200D`     |
| 2    | `U+2060`     |
| 3    | `U+FEFF`     |
| 4    | `U+034F`     |
| 5    | `U+00AD`     |
| 6    | `U+E0001`    |
| 7    | `U+E0020`    |
| 8    | `U+E0041`    |
| 9    | `U+E0061`    |

编码时，将 ASCII 码记为 `a`，用下式求出十位 `d₁` 和个位 `d₀`，再依次查表写入对应的载体。

$$
d_1 = \left\lfloor \frac{a - 32}{10} \right\rfloor, \qquad d_0 = (a - 32) \bmod 10
$$


解码时，将提取出的载体按顺序两两配对，查表恢复数字，再计算原来的 ASCII 码。

$$
a = 10d_1 + d_0 + 32
$$


例如，`H` 的 ASCII 码是 72，减去 32 得到 `40`，因此依次写入 `U+034F` 和 `U+200C`。

如果混淆消息本身也含有载体，编码器会加入不可见边界，帮助解码器区分隐藏内容与混淆消息。边界格式见 [跨平台文本格式](Android/README.md#跨平台文本格式)。

## 从源码构建

### Windows 便携版

在 Windows 上安装 .NET 8 SDK 后，从项目根目录运行：

```powershell
dotnet publish .\WeChatSteganography.csproj -c Release -p:PublishProfile=WindowsPortable -o .\bin\portable
```

输出文件为 `bin/portable/WeSteg.exe`。[WindowsPortable 发布配置](Properties/PublishProfiles/WindowsPortable.pubxml)会将 Windows x64 运行时打包进 EXE。

### Android APK

在 Windows 上准备 JDK 17、Android SDK Platform 36 和 Build Tools 36.1.0 后，从项目根目录运行：

```powershell
pwsh -File Android/build.ps1
```

脚本会构建并签名 APK，输出到 `bin/Release/<版本>/android/`。签名密钥管理、覆盖安装所需的签名配置和跨平台编码格式见 [Android 构建说明](Android/README.md)。
