using System.Text;
using System.Windows.Controls;

namespace WeChatSteganography;

internal sealed class RawMarkerHistory(int undoLimit)
{
    internal readonly record struct Marker(int Start, int End, Rune Value);

    private readonly List<Marker[]> undo = [];
    private readonly Stack<Marker[]> redo = new();
    private Marker[] current = [];

    // Mirror WPF's undo units for marker metadata only. WPF owns the displayed text.
    internal void Apply(TextChangedEventArgs change, List<Marker>? inserted)
    {
        switch (change.UndoAction)
        {
            case UndoAction.Undo when undo.Count > 0:
                redo.Push(current);
                current = undo[^1];
                undo.RemoveAt(undo.Count - 1);
                return;
            case UndoAction.Redo when redo.TryPop(out Marker[]? restored):
                undo.Add(current);
                current = restored;
                return;
            case UndoAction.Create:
                undo.Add(current);
                if (undo.Count > undoLimit)
                {
                    undo.RemoveAt(0);
                }

                redo.Clear();
                break;
            case UndoAction.Clear:
                ClearUndo();
                break;
        }

        foreach (TextChange edit in change.Changes)
        {
            Shift(edit.Offset, edit.RemovedLength, edit.AddedLength);
        }

        AddMarkers(inserted);
    }

    internal void Rewrite(List<Marker> converted)
    {
        foreach (Marker marker in converted)
        {
            Shift(marker.Start, marker.Value.Utf16SequenceLength, marker.End - marker.Start);
        }

        AddMarkers(converted);
        ClearUndo();
    }

    internal string Restore(string text, int start, int length)
    {
        int end = start + length;
        int cursor = start;
        StringBuilder? result = null;
        foreach (Marker marker in current)
        {
            if (marker.Start < start || marker.End > end)
            {
                continue;
            }

            result ??= new StringBuilder(length);
            result.Append(text, cursor, marker.Start - cursor);
            result.Append(marker.Value.ToString());
            cursor = marker.End;
        }

        return result?.Append(text, cursor, end - cursor).ToString() ?? text.Substring(start, length);
    }

    private void ClearUndo()
    {
        undo.Clear();
        redo.Clear();
    }

    private void AddMarkers(List<Marker>? markers)
    {
        if (markers is { Count: > 0 })
        {
            current = current.Concat(markers).OrderBy(marker => marker.Start).ToArray();
        }
    }

    private void Shift(int offset, int removed, int added)
    {
        if (current.Length == 0)
        {
            return;
        }

        int delta = added - removed;
        var shifted = new List<Marker>(current.Length);
        foreach (Marker marker in current)
        {
            if (marker.End <= offset)
            {
                shifted.Add(marker);
            }
            else if (marker.Start >= offset + removed)
            {
                shifted.Add(marker with { Start = marker.Start + delta, End = marker.End + delta });
            }
        }

        current = shifted.ToArray();
    }
}
