# WeSteg Android

在 Windows 上使用 JDK 17、Android SDK Platform 36 和 Build Tools 36.1.0，从项目根目录运行：

```powershell
pwsh -File Android/build.ps1
```

脚本读取桌面工程的版本，构建、对齐、签名并验证 APK，输出到 `bin/Release/<版本>/android`。Android 最低版本为 API 23。首次构建会在忽略目录 `.signing` 创建应用签名密钥，密码由当前 Windows 用户的 DPAPI 保护。后续更新应沿用该密钥。已有签名密钥可通过 `-Keystore` 和 `-KeyAlias` 指定，并通过 `WESTEG_ANDROID_KEY_PASSWORD` 提供密码。

## 跨平台文本格式

编码框里首个未转义的 `\` 分隔明文与混淆消息，明文中的 `\\` 表示一个待编码的 `\`。编码结果先放隐藏载体，再原样附上混淆消息。明文继续使用原来的 ASCII 两字符编码。

混淆消息含有载体字符，或含有可还原为载体的 `<0x...>` 字样时，编码器会在隐藏载体与混淆消息之间插入 `999991999991` 对应的不可见边界。两端解码器读取这个边界之前的隐藏载体，并兼容 2.0.0 的后置载体边界 `999990999990`。普通旧消息仍按原来的载体配对规则读取。
