using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Automation.Peers;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;

namespace WeChatSteganography;

public partial class MainWindow : Window
{
    private const int ImmersiveDarkMode = 20;
    private const int CaptionColor = 35;
    private const int CaptionTextColor = 36;
    private const int DefaultCaptionColor = -1;

    private readonly ResourceDictionary contrastPalette = new();
    private readonly DispatcherTimer copyFeedback = new() { Interval = TimeSpan.FromSeconds(2) };
    private string encodedRaw = string.Empty;

    public MainWindow()
    {
        InitializeComponent();
        PlainInput.TextChanged += (_, _) => UpdateStatus(EncodeMessage, string.Empty);
        MixedInput.TextChanged += OnMixedChanged;
        copyFeedback.Tick += (_, _) =>
        {
            copyFeedback.Stop();
            UpdateStatus(EncodeMessage, string.Empty);
        };
    }

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        SystemParameters.StaticPropertyChanged += OnSystemParametersChanged;
        ApplyWindowTheme();
    }

    protected override void OnClosed(EventArgs e)
    {
        SystemParameters.StaticPropertyChanged -= OnSystemParametersChanged;
        copyFeedback.Stop();
        base.OnClosed(e);
    }

    private void OnSystemParametersChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(SystemParameters.HighContrast))
        {
            ApplyWindowTheme();
        }
    }

    private void ApplyWindowTheme()
    {
        bool highContrast = SystemParameters.HighContrast;
        if (highContrast && !Resources.MergedDictionaries.Contains(contrastPalette))
        {
            contrastPalette["Page"] = SystemColors.WindowBrush;
            contrastPalette["Panel"] = SystemColors.WindowBrush;
            contrastPalette["Input"] = SystemColors.WindowBrush;
            contrastPalette["Ink"] = SystemColors.WindowTextBrush;
            contrastPalette["Muted"] = SystemColors.WindowTextBrush;
            contrastPalette["Line"] = SystemColors.WindowTextBrush;
            contrastPalette["Accent"] = SystemColors.HotTrackBrush;
            contrastPalette["AccentInk"] = SystemColors.WindowBrush;
            contrastPalette["AccentWash"] = SystemColors.WindowBrush;
            contrastPalette["Output"] = SystemColors.WindowTextBrush;
            contrastPalette["Token"] = SystemColors.WindowTextBrush;
            contrastPalette["Selection"] = SystemColors.HighlightBrush;
            contrastPalette["SelectionText"] = SystemColors.HighlightTextBrush;
            contrastPalette["Error"] = SystemColors.WindowTextBrush;
            Resources.MergedDictionaries.Add(contrastPalette);
        }
        else if (!highContrast)
        {
            Resources.MergedDictionaries.Remove(contrastPalette);
        }

        if (OperatingSystem.IsWindowsVersionAtLeast(10, 0, 22000))
        {
            IntPtr window = new WindowInteropHelper(this).Handle;
            DwmSetWindowAttribute(window, ImmersiveDarkMode, highContrast ? 0 : 1, sizeof(int));
            DwmSetWindowAttribute(window, CaptionColor, highContrast ? DefaultCaptionColor : CaptionBrush("Page"), sizeof(int));
            DwmSetWindowAttribute(window, CaptionTextColor, highContrast ? DefaultCaptionColor : CaptionBrush("Ink"), sizeof(int));
        }
    }

    private int CaptionBrush(string key)
    {
        Color color = ((SolidColorBrush)FindResource(key)).Color;
        return color.R | color.G << 8 | color.B << 16;
    }

    [DllImport("dwmapi.dll", ExactSpelling = true)]
    private static extern int DwmSetWindowAttribute(IntPtr window, int attribute, in int value, int valueSize);

    private void Encode(object sender, RoutedEventArgs e)
    {
        copyFeedback.Stop();
        (string Text, int Injected) result;
        try
        {
            result = Steganography.EncodeMessage(PlainInput.RawText);
        }
        catch (ArgumentException ex)
        {
            UpdateStatus(EncodeMessage, ex.Message, error: true);
            PlainInput.Focus();
            return;
        }

        SetEncodedResult(result.Text, result.Injected);
    }

    private void ClearEncode(object sender, RoutedEventArgs e)
    {
        copyFeedback.Stop();
        PlainInput.ClearInput();
        SetEncodedResult(string.Empty, 0);
        PlainInput.Focus();
    }

    private void SetEncodedResult(string raw, int injected)
    {
        encodedRaw = raw;
        EncodedOutput.Text = Steganography.RenderInvisibles(raw);
        UpdateStatus(EncodeCount, $"已注入不可见字符：{injected}");
        UpdateStatus(EncodeMessage, string.Empty);
    }

    private void CopyEncoded(object sender, RoutedEventArgs e)
    {
        copyFeedback.Stop();
        if (encodedRaw.Length == 0)
        {
            UpdateStatus(EncodeMessage, "请先进行编码。", error: true);
            return;
        }

        try
        {
            Clipboard.SetText(encodedRaw, TextDataFormat.UnicodeText);
        }
        catch (ExternalException)
        {
            UpdateStatus(EncodeMessage, "无法写入剪贴板，请重试。", error: true);
            return;
        }

        UpdateStatus(EncodeMessage, "已复制编码结果。");
        copyFeedback.Start();
    }

    private void OnMixedChanged(object sender, TextChangedEventArgs e)
    {
        string raw = Steganography.RestoreInvisibles(MixedInput.Text);
        var (total, carriers) = Steganography.CountInvisibles(raw);
        UpdateStatus(DecodeCount, $"检测到不可见字符：{total}（有效字符：{carriers}）");
        UpdateStatus(DecodeMessage, string.Empty);
        DecodedOutput.Clear();
    }

    private void Decode(object sender, RoutedEventArgs e)
    {
        if (MixedInput.Text.Length == 0)
        {
            UpdateStatus(DecodeMessage, "请先粘贴待检测文本。", error: true);
            MixedInput.Focus();
            return;
        }

        DecodedOutput.Text = Steganography.Decode(Steganography.RestoreInvisibles(MixedInput.Text));
        UpdateStatus(DecodeMessage, DecodedOutput.Text.Length == 0 ? "未解码出明文。" : string.Empty);
    }

    private void ClearDecode(object sender, RoutedEventArgs e)
    {
        MixedInput.ClearInput();
        UpdateStatus(DecodeMessage, string.Empty);
        MixedInput.Focus();
    }

    private void UpdateStatus(TextBlock status, string text, bool error = false)
    {
        if (status.Text == text)
        {
            return;
        }

        if (error)
        {
            status.SetResourceReference(TextBlock.ForegroundProperty, "Error");
        }
        else
        {
            status.ClearValue(TextBlock.ForegroundProperty);
        }
        status.Text = text;
        if (AutomationPeer.ListenerExists(AutomationEvents.LiveRegionChanged))
        {
            UIElementAutomationPeer.CreatePeerForElement(status)?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
        }
    }
}
