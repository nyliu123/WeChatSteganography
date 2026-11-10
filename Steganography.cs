using System.Globalization;
using System.Text;
using System.Text.RegularExpressions;

namespace WeChatSteganography;

internal static partial class Steganography
{
    private const int FirstAscii = 32;
    private const int LastAscii = 126;
    // Printable ASCII pairs cannot contain five consecutive 9s, regardless of alignment.
    private const string CoverBoundaryDigits = "999991999991";
    private const string LegacyCoverBoundaryDigits = "999990999990";

    private static readonly string[] Carriers =
    [
        "\u200C", "\u200D", "\u2060", "\uFEFF", "\u034F", "\u00AD",
        "\U000E0001", "\U000E0020", "\U000E0041", "\U000E0061"
    ];

    private static readonly Dictionary<Rune, int> CarrierDigits = Carriers
        .Select((carrier, digit) => (Rune.GetRuneAt(carrier, 0), digit))
        .ToDictionary(pair => pair.Item1, pair => pair.digit);

    internal static (string Text, int Injected) EncodeMessage(string input)
    {
        var hidden = new StringBuilder();
        string cover = string.Empty;
        for (int index = 0; index < input.Length; index++)
        {
            char ch = input[index];
            if (ch == '\\')
            {
                if (index + 1 >= input.Length || input[index + 1] != '\\')
                {
                    cover = input[(index + 1)..];
                    break;
                }

                index++;
            }

            hidden.Append(ch);
        }

        string payload = Encode(hidden.ToString());
        int injected = hidden.Length * 2;
        if (CountInvisibles(RestoreInvisibles(cover)).Carriers > 0)
        {
            string boundary = string.Concat(CoverBoundaryDigits.Select(digit => Carriers[digit - '0']));
            return (string.Concat(payload, boundary, cover), injected + CoverBoundaryDigits.Length);
        }

        return (string.Concat(payload, cover), injected);
    }

    internal static string Encode(string plain)
    {
        var result = new StringBuilder();
        foreach (Rune rune in plain.EnumerateRunes())
        {
            if (rune.Value is < FirstAscii or > LastAscii)
            {
                throw new ArgumentException($"发现 U+{rune.Value:X4}。请输入 ASCII 32–126 范围内的字符。");
            }

            int code = rune.Value - FirstAscii;
            result.Append(Carriers[code / Carriers.Length]);
            result.Append(Carriers[code % Carriers.Length]);
        }

        return result.ToString();
    }

    internal static string Decode(string raw)
    {
        var digits = new StringBuilder();
        foreach (Rune rune in raw.EnumerateRunes())
        {
            if (CarrierDigits.TryGetValue(rune, out int digit))
            {
                digits.Append((char)('0' + digit));
            }
        }

        string carrierDigits = digits.ToString();
        int boundary = carrierDigits.IndexOf(CoverBoundaryDigits, StringComparison.Ordinal);
        int start = 0;
        int end = digits.Length;
        if (boundary >= 0 && IsAsciiPayload(carrierDigits.AsSpan(0, boundary)))
        {
            end = boundary;
        }
        else
        {
            int legacyBoundary = carrierDigits.LastIndexOf(LegacyCoverBoundaryDigits, StringComparison.Ordinal);
            if (legacyBoundary >= 0)
            {
                start = legacyBoundary + LegacyCoverBoundaryDigits.Length;
            }
        }

        var result = new StringBuilder((end - start) / 2);
        for (int index = start; index + 1 < end; index += 2)
        {
            int ascii = DecodePair(carrierDigits[index], carrierDigits[index + 1]);
            if (ascii <= LastAscii)
            {
                result.Append((char)ascii);
            }
        }

        return result.ToString();
    }

    private static bool IsAsciiPayload(ReadOnlySpan<char> digits)
    {
        if (digits.Length % 2 != 0)
        {
            return false;
        }

        for (int index = 0; index < digits.Length; index += 2)
        {
            if (DecodePair(digits[index], digits[index + 1]) > LastAscii)
            {
                return false;
            }
        }

        return true;
    }

    private static int DecodePair(char first, char second) =>
        FirstAscii + (first - '0') * Carriers.Length + second - '0';

    internal static string RenderInvisibles(string text, Action<int, int, Rune>? onMarker = null)
    {
        StringBuilder? result = null;
        int literalStart = 0;
        for (int index = 0; index < text.Length;)
        {
            bool valid = Rune.TryGetRuneAt(text, index, out Rune rune);
            int width = valid ? rune.Utf16SequenceLength : 1;
            if (valid && IsInvisible(rune))
            {
                result ??= new StringBuilder(text.Length);
                result.Append(text, literalStart, index - literalStart);
                int start = result.Length;
                result.Append(CultureInfo.InvariantCulture, $"<0x{rune.Value:X}>");
                onMarker?.Invoke(start, result.Length, rune);
                literalStart = index + width;
            }

            index += width;
        }

        return result?.Append(text, literalStart, text.Length - literalStart).ToString() ?? text;
    }

    internal static string RestoreInvisibles(string text) => TokenPattern().Replace(text,
        match => TryReadToken(match, out Rune rune) ? rune.ToString() : match.Value);

    internal static (int Total, int Carriers) CountInvisibles(string raw)
    {
        int total = 0;
        int carriers = 0;
        foreach (Rune rune in raw.EnumerateRunes())
        {
            if (IsInvisible(rune))
            {
                total++;
            }

            if (CarrierDigits.ContainsKey(rune))
            {
                carriers++;
            }
        }

        return (total, carriers);
    }

    internal static IEnumerable<(int Start, int End)> DisplayTokens(string text)
    {
        foreach (Match match in TokenPattern().Matches(text))
        {
            yield return (match.Index, match.Index + match.Length);
        }
    }

    private static bool TryReadToken(Match match, out Rune rune)
    {
        int codePoint = int.Parse(match.Groups[1].ValueSpan, NumberStyles.HexNumber, CultureInfo.InvariantCulture);
        return Rune.TryCreate(codePoint, out rune) && IsInvisible(rune);
    }

    private static bool IsInvisible(Rune rune)
    {
        if (rune.Value is ' ' or '\r' or '\n' or '\t')
        {
            return false;
        }

        return Rune.GetUnicodeCategory(rune) is UnicodeCategory.Format or UnicodeCategory.Control
            or UnicodeCategory.NonSpacingMark or UnicodeCategory.EnclosingMark
            or UnicodeCategory.LineSeparator or UnicodeCategory.ParagraphSeparator or UnicodeCategory.SpaceSeparator
            || rune.Value is 0x115F or 0x1160 or 0x17B4 or 0x17B5 or 0x2800 or 0x3164 or 0xFFA0;
    }

    [GeneratedRegex("<0x([0-9A-Fa-f]{1,6})>")]
    private static partial Regex TokenPattern();
}
