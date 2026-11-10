package app.westeg;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.Editable;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ReplacementSpan;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

import java.util.regex.Matcher;

final class TokenEditText extends EditText {
    private final int tokenColor;
    private final boolean copyDisplayed;
    private boolean adjustingSelection;
    private boolean ready;

    TokenEditText(Context context, int tokenColor, boolean copyDisplayed) {
        super(context);
        this.tokenColor = tokenColor;
        this.copyDisplayed = copyDisplayed;
        addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { refreshMarkers(); }
        });
        ready = true;
    }

    // Spans draw labels over the original characters without rewriting the Editable or its undo history.
    private void refreshMarkers() {
        Editable text = getText();
        if (BaseInputConnection.getComposingSpanStart(text) >= 0) return;

        for (Marker marker : text.getSpans(0, text.length(), Marker.class)) text.removeSpan(marker);
        Matcher literal = Steganography.TOKEN.matcher(text);
        boolean hasLiteral = literal.find();
        for (int index = 0; index < text.length();) {
            if (hasLiteral && literal.start() == index) {
                mark(text, index, literal.end(), literal.group());
                index = literal.end();
                hasLiteral = literal.find();
                continue;
            }

            int value = Character.codePointAt(text, index);
            int end = index + Character.charCount(value);
            if (Steganography.isInvisible(value)) mark(text, index, end, Steganography.marker(value));
            index = end;
        }
    }

    private void mark(Editable text, int start, int end, String label) {
        text.setSpan(new Marker(label, tokenColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Override protected void onSelectionChanged(int start, int end) {
        super.onSelectionChanged(start, end);
        if (!ready || adjustingSelection || start < 0 || end < 0
                || BaseInputConnection.getComposingSpanStart(getText()) >= 0) return;

        int first = snap(Math.min(start, end), start == end ? 0 : -1);
        int last = start == end ? first : snap(Math.max(start, end), 1);
        int anchor = start <= end ? first : last;
        int caret = start <= end ? last : first;
        if (anchor != start || caret != end) {
            adjustingSelection = true;
            try { setSelection(anchor, caret); }
            finally { adjustingSelection = false; }
        }
    }

    private int snap(int offset, int direction) {
        Editable text = getText();
        for (Marker marker : text.getSpans(offset, offset, Marker.class)) {
            int start = text.getSpanStart(marker);
            int end = text.getSpanEnd(marker);
            if (start < offset && offset < end) {
                return direction < 0 || direction == 0 && offset - start <= end - offset ? start : end;
            }
        }
        return offset;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int caret = getSelectionStart();
        if (getKeyListener() != null && caret >= 0 && caret == getSelectionEnd()
                && (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL)) {
            Editable text = getText();
            for (Marker marker : text.getSpans(caret, caret, Marker.class)) {
                int start = text.getSpanStart(marker);
                int end = text.getSpanEnd(marker);
                if (keyCode == KeyEvent.KEYCODE_DEL ? end == caret : start == caret) {
                    text.delete(start, end);
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        InputConnection base = super.onCreateInputConnection(info);
        if (base == null) return null;
        return new InputConnectionWrapper(base, false) {
            @Override public boolean deleteSurroundingText(int before, int after) {
                int caret = getSelectionStart();
                if (caret < 0 || caret != getSelectionEnd()) return super.deleteSurroundingText(before, after);
                int start = snap(caret - Math.min(caret, before), -1);
                int end = snap(caret + Math.min(getText().length() - caret, after), 1);
                return super.deleteSurroundingText(caret - start, end - caret);
            }

            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                int caret = getSelectionStart();
                if (caret < 0 || caret != getSelectionEnd()) return super.deleteSurroundingTextInCodePoints(before, after);
                String text = getText().toString();
                int start = text.offsetByCodePoints(caret, -Math.min(before, text.codePointCount(0, caret)));
                int end = text.offsetByCodePoints(caret, Math.min(after, text.codePointCount(caret, text.length())));
                return deleteSurroundingText(caret - start, end - caret);
            }

            @Override public boolean finishComposingText() {
                boolean handled = super.finishComposingText();
                refreshMarkers();
                return handled;
            }
        };
    }

    @Override public boolean onTextContextMenuItem(int id) {
        if (copyDisplayed && (id == android.R.id.copy || id == android.R.id.cut && getKeyListener() != null)) {
            int start = Math.min(getSelectionStart(), getSelectionEnd());
            int end = Math.max(getSelectionStart(), getSelectionEnd());
            if (start >= 0 && end > start) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("字符标记", Steganography.render(getText().subSequence(start, end).toString())));
                if (id == android.R.id.cut) getText().delete(start, end);
                return true;
            }
        }
        return super.onTextContextMenuItem(id);
    }

    private static final class Marker extends ReplacementSpan {
        private final String label;
        private final int color;

        Marker(String label, int color) {
            this.label = label;
            this.color = color;
            if (Build.VERSION.SDK_INT >= 30) setContentDescription(label);
        }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int) Math.ceil(paint.measureText(label));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                                   int top, int baseline, int bottom, Paint paint) {
            int original = paint.getColor();
            paint.setColor(color);
            canvas.drawText(label, x, baseline, paint);
            paint.setColor(original);
        }
    }
}
