using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;

namespace WeChatSteganography;

public sealed class InvisibleTextBox : TextBox
{
    private const int MarkerUndoLimit = 100;

    public static readonly DependencyProperty TokenBrushProperty = DependencyProperty.Register(
        nameof(TokenBrush), typeof(Brush), typeof(InvisibleTextBox),
        new PropertyMetadata(null, (target, _) => ((InvisibleTextBox)target).UpdateTokenEffects()));

    private (int Start, int End)[] tokens = [];
    private RawMarkerHistory? rawHistory;
    private List<RawMarkerHistory.Marker>? pendingMarkers;
    private int? keyboardAnchor;
    private int keyboardCaret;
    private bool rendering;

    public InvisibleTextBox()
    {
        UndoLimit = MarkerUndoLimit;
        AllowDrop = false;
        DataObject.AddPastingHandler(this, OnPasting);
        DataObject.AddCopyingHandler(this, OnCopying);
        CommandManager.AddPreviewExecutedHandler(this, OnPreviewCommand);
        AddHandler(MouseLeftButtonUpEvent, new MouseButtonEventHandler(OnSelectionReleased), true);
    }

    public Brush? TokenBrush
    {
        get => (Brush?)GetValue(TokenBrushProperty);
        set => SetValue(TokenBrushProperty, value);
    }

    public bool PreserveRawInput { get; set; }

    internal string RawText => rawHistory?.Restore(Text, 0, Text.Length) ?? Text;

    internal void ClearInput()
    {
        SelectAll();
        SelectedText = string.Empty;
    }

    protected override void OnTextChanged(TextChangedEventArgs e)
    {
        if (rendering)
        {
            return;
        }

        if (PreserveRawInput)
        {
            rawHistory ??= new RawMarkerHistory(MarkerUndoLimit);
            rawHistory.Apply(e, pendingMarkers);
            pendingMarkers = null;
        }

        keyboardAnchor = null;
        string original = Text;
        string displayed = PrepareInvisibles(original, 0, out var converted);
        tokens = Steganography.DisplayTokens(displayed).ToArray();
        if (displayed != original)
        {
            int caret = Steganography.RenderInvisibles(original[..CaretIndex]).Length;
            if (converted != null)
            {
                rawHistory?.Rewrite(converted);
            }

            rendering = true;
            try
            {
                SetCurrentValue(TextProperty, displayed);
                Select(Snap(caret), 0);
            }
            finally
            {
                rendering = false;
            }
        }

        UpdateTokenEffects();
        base.OnTextChanged(e);
    }

    protected override void OnPreviewTextInput(TextCompositionEventArgs e)
    {
        if (IsReadOnly)
        {
            base.OnPreviewTextInput(e);
            return;
        }

        NormalizeSelection();
        string displayed = PrepareInvisibles(e.Text, SelectionStart, out var converted);
        if (displayed != e.Text)
        {
            int end = SelectionStart + displayed.Length;
            pendingMarkers = converted;
            SelectedText = displayed;
            pendingMarkers = null;
            Select(end, 0);
            e.Handled = true;
        }

        base.OnPreviewTextInput(e);
    }

    protected override void OnPreviewKeyDown(KeyEventArgs e)
    {
        base.OnPreviewKeyDown(e);
        if (e.Handled || Keyboard.Modifiers.HasFlag(ModifierKeys.Alt))
        {
            return;
        }

        bool control = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        var (command, direction) = e.Key switch
        {
            Key.Left => (control ? EditingCommands.MoveLeftByWord : EditingCommands.MoveLeftByCharacter, -1),
            Key.Right => (control ? EditingCommands.MoveRightByWord : EditingCommands.MoveRightByCharacter, 1),
            Key.Up => (EditingCommands.MoveUpByLine, -1),
            Key.Down => (EditingCommands.MoveDownByLine, 1),
            Key.Home => (control ? EditingCommands.MoveToDocumentStart : EditingCommands.MoveToLineStart, -1),
            Key.End => (control ? EditingCommands.MoveToDocumentEnd : EditingCommands.MoveToLineEnd, 1),
            Key.PageUp => (EditingCommands.MoveUpByPage, -1),
            Key.PageDown => (EditingCommands.MoveDownByPage, 1),
            _ => ((RoutedUICommand?)null, 0)
        };

        if (command != null && tokens.Length > 0)
        {
            MoveCaret(command, direction, Keyboard.Modifiers.HasFlag(ModifierKeys.Shift));
            e.Handled = true;
        }
        else if (!IsReadOnly && (e.Key == Key.Back || e.Key == Key.Delete && !Keyboard.Modifiers.HasFlag(ModifierKeys.Shift)))
        {
            Delete(e.Key == Key.Back);
            e.Handled = true;
        }
        else if (e.Key is Key.Enter or Key.Tab)
        {
            NormalizeSelection();
        }
    }

    protected override void OnPreviewMouseDown(MouseButtonEventArgs e)
    {
        keyboardAnchor = null;
        base.OnPreviewMouseDown(e);
    }

    private void OnSelectionReleased(object sender, MouseButtonEventArgs e) => NormalizeSelection();

    private void OnPreviewCommand(object sender, ExecutedRoutedEventArgs e)
    {
        pendingMarkers = null;
        if (e.Command == ApplicationCommands.Copy || e.Command == ApplicationCommands.Cut ||
            e.Command == ApplicationCommands.Paste)
        {
            NormalizeSelection();
        }
        else if (e.Command == ApplicationCommands.SelectAll)
        {
            keyboardAnchor = 0;
            keyboardCaret = Text.Length;
        }
    }

    private void OnPasting(object sender, DataObjectPastingEventArgs e)
    {
        NormalizeSelection();
        if (e.DataObject.GetData(DataFormats.UnicodeText) is string raw)
        {
            string displayed = PrepareInvisibles(raw, SelectionStart, out pendingMarkers);
            e.DataObject = new DataObject(DataFormats.UnicodeText, displayed);
            e.FormatToApply = DataFormats.UnicodeText;
        }
    }

    private void OnCopying(object sender, DataObjectCopyingEventArgs e)
    {
        if (rawHistory != null)
        {
            string raw = rawHistory.Restore(Text, SelectionStart, SelectionLength);
            if (raw != SelectedText)
            {
                e.DataObject.SetData(DataFormats.UnicodeText, raw);
                e.DataObject.SetData(DataFormats.Text, raw);
            }
        }
    }

    private string PrepareInvisibles(string text, int offset, out List<RawMarkerHistory.Marker>? converted)
    {
        if (!PreserveRawInput)
        {
            converted = null;
            return Steganography.RenderInvisibles(text);
        }

        List<RawMarkerHistory.Marker>? markers = null;
        string displayed = Steganography.RenderInvisibles(text, (start, end, rune) =>
            (markers ??= []).Add(new RawMarkerHistory.Marker(offset + start, offset + end, rune)));
        converted = markers;
        return displayed;
    }

    private void UpdateTokenEffects()
    {
        if (TokenBrush is not Brush brush || tokens.Length == 0)
        {
            ClearValue(TextElement.TextEffectsProperty);
            return;
        }

        var effects = new TextEffectCollection();
        var (start, end) = tokens[0];
        for (int index = 1; index < tokens.Length; index++)
        {
            var (nextStart, nextEnd) = tokens[index];
            if (nextStart != end)
            {
                effects.Add(new TextEffect(null, brush, null, start, end - start));
                start = nextStart;
            }

            end = nextEnd;
        }

        effects.Add(new TextEffect(null, brush, null, start, end - start));
        SetValue(TextElement.TextEffectsProperty, effects);
    }

    private void MoveCaret(RoutedUICommand command, int direction, bool selecting)
    {
        NormalizeSelection();
        int start = SelectionStart;
        int end = start + SelectionLength;
        bool horizontal = command == EditingCommands.MoveLeftByCharacter || command == EditingCommands.MoveRightByCharacter
            || command == EditingCommands.MoveLeftByWord || command == EditingCommands.MoveRightByWord;
        if (!selecting && horizontal && start != end)
        {
            Select(direction < 0 ? start : end, 0);
            keyboardAnchor = null;
            return;
        }

        int caret = keyboardAnchor.HasValue ? keyboardCaret : end;
        int anchor = keyboardAnchor ?? start;
        if (SelectionLength != 0)
        {
            Select(caret, 0);
        }

        command.Execute(null, this);
        caret = Snap(CaretIndex, direction);
        if (selecting)
        {
            keyboardAnchor = anchor;
            keyboardCaret = caret;
            Select(Math.Min(anchor, caret), Math.Abs(anchor - caret));
        }
        else
        {
            keyboardAnchor = null;
            if (CaretIndex != caret)
            {
                Select(caret, 0);
            }
        }

        ScrollToLine(GetLineIndexFromCharacterIndex(caret));
    }

    private void Delete(bool backward)
    {
        NormalizeSelection();
        if (SelectionLength == 0)
        {
            int caret = CaretIndex;
            int edge = Snap(Math.Clamp(caret + (backward ? -1 : 1), 0, Text.Length), backward ? -1 : 1);
            if (Math.Abs(caret - edge) > 1)
            {
                Select(Math.Min(caret, edge), Math.Abs(caret - edge));
            }
        }

        (backward ? EditingCommands.Backspace : EditingCommands.Delete).Execute(null, this);
    }

    private void NormalizeSelection()
    {
        int start = SelectionStart;
        int end = start + SelectionLength;
        int first = Snap(start, start == end ? 0 : -1);
        int last = start == end ? first : Snap(end, 1);
        if (first != start || last != end)
        {
            Select(first, last - first);
        }
    }

    private int Snap(int offset, int direction = 0)
    {
        int low = 0;
        int high = tokens.Length - 1;
        while (low <= high)
        {
            int index = low + (high - low) / 2;
            var (start, end) = tokens[index];
            if (offset <= start)
            {
                high = index - 1;
            }
            else if (offset >= end)
            {
                low = index + 1;
            }
            else
            {
                return direction < 0 || direction == 0 && offset - start <= end - offset ? start : end;
            }
        }

        return offset;
    }
}
