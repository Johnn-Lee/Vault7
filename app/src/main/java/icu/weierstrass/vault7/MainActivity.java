package icu.weierstrass.vault7;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.InputFilter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int PICK_FOLDER = 10;
    private static final int MEDIA_ACCESS = 11;
    private static final String DIRECTORY = "directory";
    private static final int INK = 0xff17312d;
    private static final int MUTED = 0xff455e58;
    private static final int GREEN = 0xff236b62;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences settings;
    private PasswordStore passwords;
    private Spinner modes;
    private Button permissionButton;
    private Button folderButton;
    private Button passwordButton;
    private Button runButton;
    private Button cancelButton;
    private Button startButton;
    private Button endButton;
    private TextView status;
    private LinearLayout endRow;
    private LinearLayout daysRow;
    private LinearLayout limitRow;
    private EditText days;
    private EditText limit;
    private LocalDate start;
    private LocalDate end;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = PasswordStore.preferences(this);
        passwords = new PasswordStore(this);
        if (state != null) {
            start = date(state.getString("from"));
            end = date(state.getString("to"));
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xfff5f8f6);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout page = column();
        page.setPadding(dp(24), dp(26), dp(24), dp(28));
        scroll.addView(page);
        setContentView(scroll);

        TextView heading = text("Vault7", 36, INK);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        page.addView(heading);
        TextView intro = text("相册原件 · AES-256 加密备份", 15, MUTED);
        page.addView(intro);

        LinearLayout setup = panel(page);
        permissionButton = button("授权读取完整相册", this::requestMedia);
        setup.addView(permissionButton);
        folderButton = button("", this::pickFolder);
        setup.addView(folderButton);
        passwordButton = button("", this::editPassword);
        setup.addView(passwordButton);

        LinearLayout form = panel(page);
        form.addView(text("备份方式", 17, INK));
        modes = new Spinner(this);
        modes.setId(101);
        var adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"手动 · 指定日期", "手动 · 单包大小上限",
                        "批量 · 每隔 N 天生成一包", "批量 · 按大小分包"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modes.setAdapter(adapter);
        modes.setMinimumHeight(dp(48));
        form.addView(modes);

        LinearLayout startRow = row(form, "起始日期");
        startButton = button("", () -> chooseDate(true));
        startRow.addView(startButton);
        endRow = row(form, "结束日期（含当天）");
        endButton = button("", () -> chooseDate(false));
        endRow.addView(endButton);

        daysRow = row(form, "每包天数");
        days = number(102, "30", false);
        daysRow.addView(days, new LinearLayout.LayoutParams(dp(110), -2));

        limitRow = row(form, "每包上限（MiB）");
        limit = number(103, "2048", true);
        limitRow.addView(limit, new LinearLayout.LayoutParams(dp(110), -2));

        modes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                updateMode();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        runButton = button("开始备份", this::begin);
        runButton.setTextColor(Color.WHITE);
        runButton.setBackground(surface(GREEN, 14));
        LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(-1, dp(52));
        action.topMargin = dp(18);
        form.addView(runButton, action);
        cancelButton = button("取消当前备份", () -> startService(
                new Intent(this, BackupService.class).setAction(BackupService.CANCEL)));
        form.addView(cancelButton);

        LinearLayout report = panel(page);
        report.addView(text("备份进度", 17, INK));
        status = text("", 15, MUTED);
        status.setPadding(0, dp(8), 0, 0);
        status.setTextIsSelectable(true);
        report.addView(status);

        updateMode();
        updateSetup();
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        updateSetup();
        handler.post(refresh);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (start != null) state.putString("from", start.toString());
        if (end != null) state.putString("to", end.toString());
    }

    private static LocalDate date(String saved) {
        return saved == null ? null : LocalDate.parse(saved);
    }

    private BackupPlan.Mode mode() {
        return BackupPlan.Mode.values()[modes.getSelectedItemPosition()];
    }

    private void updateMode() {
        BackupPlan.Mode mode = mode();
        endRow.setVisibility(mode == BackupPlan.Mode.EVERY_N_DAYS ? View.GONE : View.VISIBLE);
        daysRow.setVisibility(mode == BackupPlan.Mode.EVERY_N_DAYS ? View.VISIBLE : View.GONE);
        limitRow.setVisibility(mode.sizeLimited() ? View.VISIBLE : View.GONE);
        startButton.setText(start == null
                ? mode == BackupPlan.Mode.DATE ? "选择日期" : "相册最早日期"
                : start.toString());
        endButton.setText(end == null
                ? mode == BackupPlan.Mode.DATE ? "选择日期" : "相册最新日期"
                : end.toString());
    }

    private void chooseDate(boolean first) {
        LocalDate selected = first ? start : end;
        LocalDate initial = selected == null ? LocalDate.now() : selected;
        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            if (first) start = LocalDate.of(year, month + 1, day);
            else end = LocalDate.of(year, month + 1, day);
            updateMode();
        }, initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth());
        if (selected != null) dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "清除", (view, which) -> {
            if (first) start = null;
            else end = null;
            updateMode();
        });
        dialog.show();
    }

    private List<String> mediaPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        return permissions;
    }

    private boolean hasFullAccess() {
        for (String permission : mediaPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private void requestMedia() {
        if (hasFullAccess()) {
            toast("已获得完整相册和原件访问权限");
            return;
        }
        List<String> permissions = mediaPermissions();
        if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED);
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        requestPermissions(permissions.toArray(new String[0]), MEDIA_ACCESS);
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == MEDIA_ACCESS) {
            updateSetup();
            if (!hasFullAccess()) toast("请选择允许访问所有照片和视频，并允许读取原件位置信息");
        }
    }

    private void pickFolder() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(pick, PICK_FOLDER);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri folder = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(folder,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            settings.edit().putString(DIRECTORY, folder.toString()).apply();
            updateSetup();
        } catch (SecurityException e) {
            toast("无法保存目录访问权限");
        }
    }

    private void editPassword() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(128)});
        input.setSaveEnabled(false);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout box = column();
        box.setPadding(dp(22), dp(8), dp(22), 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("全局 7z 密码")
                .setMessage("请妥善保存密码。更改密码后，新包使用新密码，旧包仍需要原密码。")
                .setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnDismissListener(ignored -> input.getText().clear());
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            var value = input.getText();
            if (value.length() == 0) {
                input.setError("请输入密码");
                return;
            }
            char[] password = new char[value.length()];
            value.getChars(0, value.length(), password, 0);
            try {
                passwords.save(password);
                updateSetup();
                dialog.dismiss();
            } catch (Exception e) {
                input.setError("无法保存密码，请重试");
            } finally {
                Arrays.fill(password, '\0');
            }
        });
    }

    private void begin() {
        if (BackupService.status().running()) return;
        if (!hasFullAccess()) {
            requestMedia();
            return;
        }
        String folder = settings.getString(DIRECTORY, null);
        if (folder == null) {
            pickFolder();
            return;
        }
        if (!passwords.isSet()) {
            editPassword();
            return;
        }
        try {
            BackupPlan.Mode selected = mode();
            int dayCount = selected == BackupPlan.Mode.EVERY_N_DAYS
                    ? Integer.parseInt(days.getText().toString().trim()) : 0;
            long bytes = selected.sizeLimited() ? new BigDecimal(limit.getText().toString().trim())
                    .multiply(BigDecimal.valueOf(1_048_576))
                    .setScale(0, RoundingMode.FLOOR).longValueExact() : 0;
            LocalDate until = selected == BackupPlan.Mode.EVERY_N_DAYS ? null : end;
            ZoneId zone = ZoneId.systemDefault();
            new BackupPlan(selected, start, until, dayCount, bytes, zone);
            Intent intent = new Intent(this, BackupService.class)
                    .putExtra("mode", selected.name()).putExtra("folder", folder)
                    .putExtra("start", start == null ? null : start.toString())
                    .putExtra("end", until == null ? null : until.toString())
                    .putExtra("days", dayCount).putExtra("limit", bytes).putExtra("zone", zone.getId());
            startForegroundService(intent);
            runButton.setEnabled(false);
        } catch (NumberFormatException | ArithmeticException e) {
            toast("请输入有效的天数或大小上限");
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            toast(e.getLocalizedMessage() == null ? "无法启动备份" : e.getLocalizedMessage());
        }
    }

    private void updateSetup() {
        permissionButton.setText(hasFullAccess() ? "已可读取完整相册" : "授权读取完整相册");
        String folder = settings.getString(DIRECTORY, null);
        folderButton.setText(folder == null ? "选择输出目录"
                : "输出目录 · " + DocumentsContract.getTreeDocumentId(Uri.parse(folder)));
        passwordButton.setText(passwords.isSet() ? "更改全局密码" : "设置全局密码");
    }

    private void updateStatus() {
        BackupService.Status current = BackupService.status();
        if (!current.message().contentEquals(status.getText())) status.setText(current.message());
        runButton.setEnabled(!current.running());
        folderButton.setEnabled(!current.running());
        passwordButton.setEnabled(!current.running());
        cancelButton.setVisibility(current.running() ? View.VISIBLE : View.GONE);
    }

    private LinearLayout panel(LinearLayout page) {
        LinearLayout panel = column();
        panel.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.setBackground(surface(Color.WHITE, 18));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(16);
        page.addView(panel, layout);
        return panel;
    }

    private LinearLayout row(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.addView(text(label, 15, INK), new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
        return row;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private EditText number(int id, String initial, boolean decimal) {
        EditText input = new EditText(this);
        input.setId(id);
        input.setSingleLine(true);
        input.setText(initial);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        return input;
    }

    private Button button(String label, Runnable click) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setAllCaps(false);
        button.setTextColor(GREEN);
        button.setText(label);
        button.setMinHeight(dp(48));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private TextView text(String label, int size, int color) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private GradientDrawable surface(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int size) {
        return Math.round(size * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }
}
