---
version: alpha
colors:
  page: "#0B1020"
  panel: "#131C30"
  input: "#0E1729"
  ink: "#EDF5FF"
  muted: "#A8B9CF"
  primary: "#72F0D2"
  accentInk: "#092523"
  accentWash: "#183938"
  output: "#B6FFE6"
  token: "#B5B5B5"
  selection: "#304660"
  error: "#FFA3B4"
typography:
  heading:
    fontFamily: "Microsoft YaHei UI, Segoe UI"
    fontSize: "27px"
    fontWeight: 600
  body:
    fontFamily: "Microsoft YaHei UI, Segoe UI"
    fontSize: "14px"
  editor:
    fontFamily: "Consolas, Microsoft YaHei UI"
    fontSize: "15px"
rounded:
  control: "5px"
spacing:
  gutter: "22px"
  panel: "15px"
  actionGap: "8px"
components:
  app-shell:
    backgroundColor: "{colors.page}"
    textColor: "{colors.ink}"
  workspace:
    backgroundColor: "{colors.panel}"
    textColor: "{colors.ink}"
  editor:
    backgroundColor: "{colors.input}"
    textColor: "{colors.ink}"
  output:
    backgroundColor: "{colors.page}"
    textColor: "{colors.output}"
  character-marker:
    textColor: "{colors.token}"
  editor-selection:
    backgroundColor: "{colors.selection}"
    textColor: "{colors.ink}"
  active-tab:
    backgroundColor: "{colors.accentWash}"
    textColor: "{colors.primary}"
  status:
    textColor: "{colors.muted}"
  error-status:
    textColor: "{colors.error}"
  primary-button:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.accentInk}"
    rounded: "{rounded.control}"
---

## Overview

WeSteg 是给在微信中处理零宽文本的用户使用的 Windows 和 Android 工具。界面以 Unicode 信号分析终端为参考，顶部的 `<0x200C>` 徽标展示一种真实的零宽载体。两个平台共用编码格式和简体中文操作名称。

## Colors

蓝黑底分出窗口、工作区和输入框。薄荷青标记主操作、选中的模式、计数和顶部载体徽标，浅粉强调错误文字。输入和结果中的字符标记用中性灰，深蓝选区保留标记的辨识度。`TerminalTheme.xaml` 管理桌面颜色和控件样式，其中 `primary` 对应 `Accent`，`Line` 用 `#526A8B` 描边。Android 使用 `MainActivity.java` 的同名颜色。Windows 高对比度模式改用系统颜色。

## Typography

微软雅黑 UI 用于简体中文界面。Consolas 用于模式短码、输入、输出和计数，使十六进制位容易对照，中文由微软雅黑 UI 补齐。标签和反馈保持固定行位。长内容换行，垂直滚动条沿用 WPF 的滚动交互。

## Layout

两页共用纵向排列，输入框等高，操作按钮放在输入与输出之间，计数和提示放在底部。编码页按编码、清空、复制结果排序。窗口默认宽 800，高 590，最小宽 600，高 450，尺寸单位为 WPF 设备无关像素。Android 允许页面在小屏或键盘打开时滚动。操作按钮随系统字号缩放，文字换行时向下撑高按钮，编码页给较长的复制按钮分配更多宽度。

## Shapes

按钮和标签使用 5 像素圆角，工作区用细边框区隔。顶部静态波形呼应零宽载体，其余控件以稳定的颜色和边框传达状态。

图标以深青圆角底托住薄荷色对话气泡，气泡中央的小方块代表隐藏字符。EXE 和窗口共用 `Assets/AppIcon.ico`，Android 启动器使用 `Assets/AppIcon.png`。

## Components

### Canonical UI Map

| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Form | InvisibleTextBox 与共享 TokenEditor 样式 | TerminalTheme.xaml、hide.py | 明文输入、待检测文本、只读输出 | 窗口交互与往返测试 |
| Scrollbar | WPF ScrollBar 与共享 EditorScrollbar 样式 | TerminalTheme.xaml | 自动显示垂直滚动条 | 长文本与最小窗口检查 |
| Status | 共享 Status 样式与 UpdateStatus | TerminalTheme.xaml、MainWindow.xaml.cs | 计数、操作反馈、错误 | 反馈状态与自动化事件 |
