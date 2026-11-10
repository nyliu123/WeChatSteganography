package app.westeg;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int PAGE = 0xFF0B1020;
    private static final int PANEL = 0xFF131C30;
    private static final int INPUT = 0xFF0E1729;
    private static final int INK = 0xFFEDF5FF;
    private static final int MUTED = 0xFFA8B9CF;
    private static final int LINE = 0xFF526A8B;
    private static final int ACCENT = 0xFF72F0D2;
    private static final int ACCENT_INK = 0xFF092523;
    private static final int ACCENT_WASH = 0xFF183938;
    private static final int OUTPUT = 0xFFB6FFE6;
    private static final int TOKEN = 0xFFB5B5B5;
    private static final int SELECTION = 0xFF304660;
    private static final int ERROR = 0xFFFFA3B4;

    private LinearLayout encodePane;
    private LinearLayout decodePane;
    private Button encodeTab;
    private Button decodeTab;
    private TokenEditText plainInput;
    private TokenEditText encodedOutput;
    private TokenEditText mixedInput;
    private TokenEditText decodedOutput;
    private TextView encodeCount;
    private TextView encodeMessage;
    private TextView decodeCount;
    private TextView decodeMessage;
    private String encodedRaw = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView root = new ScrollView(this);
        root.setFillViewport(true);
        root.setBackgroundColor(PAGE);
        root.setClipToPadding(false);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom);
                return windowInsets;
            });
        }

        LinearLayout content = column();
        content.setPadding(dp(16), dp(13), dp(16), dp(15));
        content.setFocusableInTouchMode(true);
        root.addView(content);
        content.addView(header());

        LinearLayout tabs = actionRow();
        encodeTab = button("ENC  编码", false, () -> showMode(true));
        decodeTab = button("DEC  解码", false, () -> showMode(false));
        addAction(tabs, encodeTab);
        addAction(tabs, decodeTab);
        add(content, tabs, 10);

        encodePane = createEncodePane();
        decodePane = createDecodePane();
        add(content, encodePane, 8);
        add(content, decodePane, 8);
        showMode(true);
        setContentView(root);
        content.requestFocus();
    }

    private View header() {
        LinearLayout header = column();
        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("微信隐写工具", 25, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout badge = column();
        badge.setBackground(shape(ACCENT_WASH, LINE));
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        badge.addView(label("零宽载体示例", 11, MUTED));
        TextView carrier = label("<0x200C>", 15, ACCENT);
        carrier.setTypeface(Typeface.MONOSPACE);
        badge.addView(carrier);
        titleRow.addView(badge);
        header.addView(titleRow);
        add(header, label("将 ASCII 明文藏入零宽字符，或从混合文本中提取。", 13, MUTED), 4);
        return header;
    }

    private LinearLayout createEncodePane() {
        LinearLayout pane = panel();
        plainInput = editor(false, false);
        encodedOutput = editor(true, true);
        addFieldLabel(pane, plainInput, "明文 (ASCII 32 - 126)", 0);
        add(pane, label("\\ 后为混淆消息，\\\\ 表示一个待编码的反斜杠。", 12, MUTED), 1);
        addEditor(pane, plainInput, 150);

        LinearLayout actions = actionRow();
        addAction(actions, button("编码", true, this::encode));
        addAction(actions, button("清空", false, () -> {
            plainInput.getText().clear();
            setEncoded(new Steganography.Encoded("", 0));
            plainInput.requestFocus();
        }));
        addAction(actions, button("复制编码结果", false, this::copy), 1.5f);
        add(pane, actions, 7);
        addFieldLabel(pane, encodedOutput, "编码结果", 10);
        addEditor(pane, encodedOutput, 139);
        encodeCount = status(pane, "已注入不可见字符：0", ACCENT);
        encodeMessage = status(pane, "", MUTED);
        onChange(plainInput, () -> showMessage(encodeMessage, "", false));
        return pane;
    }

    private LinearLayout createDecodePane() {
        LinearLayout pane = panel();
        mixedInput = editor(false, true);
        decodedOutput = editor(true, false);
        addFieldLabel(pane, mixedInput, "待检测文本", 0);
        addEditor(pane, mixedInput, 150);
        LinearLayout actions = actionRow();
        addAction(actions, button("检测并解码", true, this::decode));
        addAction(actions, button("清空", false, () -> {
            mixedInput.getText().clear();
            updateDecodeStatus();
            mixedInput.requestFocus();
        }));
        add(pane, actions, 7);
        addFieldLabel(pane, decodedOutput, "解码明文", 10);
        addEditor(pane, decodedOutput, 139);
        decodeCount = status(pane, "检测到不可见字符：0（有效字符：0）", ACCENT);
        decodeMessage = status(pane, "", MUTED);
        onChange(mixedInput, this::updateDecodeStatus);
        return pane;
    }

    private void showMode(boolean encoding) {
        encodePane.setVisibility(encoding ? View.VISIBLE : View.GONE);
        decodePane.setVisibility(encoding ? View.GONE : View.VISIBLE);
        styleTab(encodeTab, encoding);
        styleTab(decodeTab, !encoding);
        hideKeyboard();
    }

    private void encode() {
        try {
            setEncoded(Steganography.encodeMessage(plainInput.getText().toString()));
            hideKeyboard();
        } catch (IllegalArgumentException error) {
            showMessage(encodeMessage, error.getMessage(), true);
            plainInput.requestFocus();
        }
    }

    private void setEncoded(Steganography.Encoded result) {
        encodedRaw = result.text;
        encodedOutput.setText(result.text);
        encodeCount.setText("已注入不可见字符：" + result.injected);
        showMessage(encodeMessage, "", false);
    }

    private void copy() {
        if (encodedRaw.isEmpty()) {
            showMessage(encodeMessage, "请先进行编码。", true);
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("编码结果", encodedRaw));
        showMessage(encodeMessage, "已复制编码结果。", false);
    }

    private void updateDecodeStatus() {
        Steganography.Counts counts = Steganography.count(Steganography.restore(mixedInput.getText().toString()));
        decodeCount.setText("检测到不可见字符：" + counts.total + "（有效字符：" + counts.carriers + "）");
        decodedOutput.setText("");
        showMessage(decodeMessage, "", false);
    }

    private void decode() {
        if (mixedInput.getText().length() == 0) {
            showMessage(decodeMessage, "请先粘贴待检测文本。", true);
            mixedInput.requestFocus();
            return;
        }
        String result = Steganography.decode(Steganography.restore(mixedInput.getText().toString()));
        decodedOutput.setText(result);
        showMessage(decodeMessage, result.isEmpty() ? "未解码出明文。" : "", false);
        hideKeyboard();
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) {
            InputMethodManager keyboard = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private TokenEditText editor(boolean readOnly, boolean copyDisplayed) {
        TokenEditText editor = new TokenEditText(this, TOKEN, copyDisplayed);
        editor.setId(View.generateViewId());
        editor.setSaveEnabled(false);
        editor.setTextSize(15);
        editor.setTextColor(readOnly ? OUTPUT : INK);
        editor.setHighlightColor(SELECTION);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setHorizontallyScrolling(false);
        editor.setVerticalScrollBarEnabled(true);
        editor.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        if (readOnly) {
            editor.setKeyListener(null);
            editor.setTextIsSelectable(true);
            editor.setCursorVisible(false);
        }
        StateListDrawable background = new StateListDrawable();
        int color = readOnly ? PAGE : INPUT;
        background.addState(new int[] { android.R.attr.state_focused }, shape(color, ACCENT));
        background.addState(new int[0], shape(color, LINE));
        editor.setBackground(background);
        editor.setPadding(dp(9), dp(8), dp(9), dp(8));
        return editor;
    }

    private LinearLayout panel() {
        LinearLayout pane = column();
        pane.setBackground(shape(PANEL, LINE));
        pane.setPadding(dp(12), dp(9), dp(12), dp(8));
        return pane;
    }

    private Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumWidth(0);
        button.setPadding(dp(7), dp(6), dp(7), dp(6));
        button.setTextColor(primary ? ACCENT_INK : INK);
        button.setBackground(ripple(primary ? ACCENT : PANEL, primary ? ACCENT : LINE));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void styleTab(Button tab, boolean active) {
        tab.setSelected(active);
        tab.setTextColor(active ? ACCENT : MUTED);
        tab.setBackground(ripple(active ? ACCENT_WASH : PAGE, active ? ACCENT : LINE));
    }

    private RippleDrawable ripple(int fill, int stroke) {
        return new RippleDrawable(ColorStateList.valueOf(0x3372F0D2), shape(fill, stroke), null);
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(7));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private void addFieldLabel(LinearLayout pane, TokenEditText editor, String text, int margin) {
        TextView label = label(text, 14, INK);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setLabelFor(editor.getId());
        add(pane, label, margin);
    }

    private TextView status(LinearLayout pane, String value, int color) {
        TextView view = label(value, 13, color);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        view.setMinHeight(dp(19));
        add(pane, view, 3);
        return view;
    }

    private void showMessage(TextView view, String text, boolean error) {
        view.setTextColor(error ? ERROR : MUTED);
        view.setText(text);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private void onChange(TokenEditText view, Runnable listener) {
        view.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) { listener.run(); }
        });
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row() {
        return new LinearLayout(this);
    }

    private LinearLayout actionRow() {
        LinearLayout row = row();
        row.setBaselineAligned(false);
        return row;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private void addEditor(LinearLayout parent, View child, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.topMargin = dp(4);
        parent.addView(child, params);
    }

    private void addAction(LinearLayout parent, Button button) {
        addAction(parent, button, 1);
    }

    private void addAction(LinearLayout parent, Button button, float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        if (parent.getChildCount() > 0) params.leftMargin = dp(6);
        parent.addView(button, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
