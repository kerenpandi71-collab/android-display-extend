package io.github.jqssun.displayextend;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.material.button.MaterialButton;
import java.util.ArrayList;
import java.util.List;

/** Manages Android's developer overlay displays through the explicitly approved root shell. */
public class OverlayDisplayActivity extends Activity {
  private EditText widthInput, heightInput, dpiInput;
  private TextView statusText, helpText;
  private RadioGroup overlayChoices;
  private final List<String> overlays = new ArrayList<>();

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    int pad = dp(20);
    ScrollView scroll = new ScrollView(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(pad, pad, pad, pad);
    scroll.addView(root);
    setContentView(scroll);

    TextView title = new TextView(this);
    title.setText(R.string.overlay_manager_title);
    title.setTextSize(24);
    root.addView(title);
    helpText = new TextView(this);
    helpText.setText(R.string.overlay_manager_help);
    helpText.setTextSize(14);
    addGap(root, 6);
    root.addView(helpText);

    widthInput = field(root, R.string.width_label, "900", InputType.TYPE_CLASS_NUMBER);
    heightInput = field(root, R.string.height_label, "1080", InputType.TYPE_CLASS_NUMBER);
    dpiInput = field(root, R.string.dpi_label_short, "240", InputType.TYPE_CLASS_NUMBER);

    TextView presetsTitle = new TextView(this);
    presetsTitle.setText("Preset resolusi");
    presetsTitle.setTextSize(16);
    addGap(root, 10);
    root.addView(presetsTitle);
    LinearLayout presets = new LinearLayout(this);
    presets.setOrientation(LinearLayout.HORIZONTAL);
    MaterialButton preset800 = new MaterialButton(this);
    preset800.setText("800×800 · 240 DPI");
    preset800.setTextSize(12);
    presets.addView(preset800, new LinearLayout.LayoutParams(0, -2, 1f));
    MaterialButton preset900 = new MaterialButton(this);
    preset900.setText("900×900 · 240 DPI");
    preset900.setTextSize(12);
    presets.addView(preset900, new LinearLayout.LayoutParams(0, -2, 1f));
    root.addView(presets);
    preset800.setOnClickListener(v -> {
      widthInput.setText("800");
      heightInput.setText("800");
      dpiInput.setText("240");
      createOverlay();
    });
    preset900.setOnClickListener(v -> {
      widthInput.setText("900");
      heightInput.setText("900");
      dpiInput.setText("240");
      createOverlay();
    });

    MaterialButton create = new MaterialButton(this);
    create.setText(R.string.overlay_create);
    root.addView(create);
    create.setOnClickListener(v -> createOverlay());

    MaterialButton performanceLog = new MaterialButton(this);
    performanceLog.setText("Rekam Log Performa Virtual Display");
    addGap(root, 8);
    root.addView(performanceLog);
    TextView performanceHint = new TextView(this);
    performanceHint.setText("Catat display, SurfaceFlinger, CPU, memori, dan frame timing untuk diagnosis. File disimpan ke Download.");
    performanceHint.setTextSize(12);
    root.addView(performanceHint);
    performanceLog.setOnClickListener(v -> capturePerformanceLog(performanceLog));

    addGap(root, 20);
    TextView listTitle = new TextView(this);
    listTitle.setText(R.string.overlay_existing_title);
    listTitle.setTextSize(18);
    root.addView(listTitle);
    statusText = new TextView(this);
    statusText.setText(R.string.overlay_loading);
    root.addView(statusText);
    overlayChoices = new RadioGroup(this);
    root.addView(overlayChoices);
    MaterialButton refresh = new MaterialButton(this);
    refresh.setText(R.string.refresh);
    root.addView(refresh);
    refresh.setOnClickListener(v -> loadOverlays());
    MaterialButton delete = new MaterialButton(this);
    delete.setText(R.string.overlay_delete_selected);
    root.addView(delete);
    delete.setOnClickListener(v -> deleteSelected());
    loadOverlays();
  }

  private void capturePerformanceLog(MaterialButton button) {
    button.setEnabled(false);
    button.setText("Sedang merekam log...");
    new Thread(() -> {
      StringBuilder log = new StringBuilder();
      log.append("Android Display Extend - Virtual Display Performance Log\n");
      log.append("Waktu: ").append(new java.util.Date()).append("\n");
      log.append("Package: ").append(getPackageName()).append("\n");
      log.append("Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
      log.append("Device: ").append(android.os.Build.MANUFACTURER).append(" ")
          .append(android.os.Build.MODEL).append("\n");
      log.append("Catatan: ambil log saat lag terjadi; beberapa statistik bergantung pada ROM/root.\n\n");
      String[] commands = {
          "echo '===== DISPLAY ====='; dumpsys display",
          "echo '===== SURFACEFLINGER LAYERS ====='; dumpsys SurfaceFlinger --list",
          "echo '===== SURFACEFLINGER LATENCY HELP ====='; dumpsys SurfaceFlinger --latency",
          "echo '===== CPU INFO ====='; dumpsys cpuinfo",
          "echo '===== MEMORY: EXTEND APP ====='; dumpsys meminfo " + getPackageName(),
          "echo '===== GRAPHICS: EXTEND APP ====='; dumpsys gfxinfo " + getPackageName() + " framestats",
          "echo '===== SYSTEM PROPERTIES ====='; getprop ro.hardware; getprop ro.build.version.release; getprop debug.hwui.renderer; getprop debug.angle.backend"
      };
      boolean rootOk = false;
      Process process = null;
      try {
        String script = "for c in " + commands.length + "; do :; done; " +
            "echo '===== ROOT CHECK ====='; id; " +
            String.join("; ", commands);
        process = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()))) {
          String line;
          while ((line = reader.readLine()) != null) {
            if (log.length() < 1_500_000) log.append(line).append('\n');
          }
        }
        rootOk = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        if (!rootOk) {
          process.destroyForcibly();
          log.append("\nERROR: pengambilan data melewati batas waktu 20 detik.\n");
        } else {
          log.append("\nExit code: ").append(process.exitValue()).append('\n');
        }
      } catch (Throwable t) {
        if (process != null) process.destroyForcibly();
        log.append("\nERROR root capture: ").append(t).append('\n');
      }

      String filename = "DisplayExtend_Performance_" +
          new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
              .format(new java.util.Date()) + ".txt";
      String message;
      try {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename);
        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(android.provider.MediaStore.Downloads.IS_PENDING, 1);
        android.net.Uri uri = getContentResolver().insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new java.io.IOException("MediaStore tidak mengembalikan URI");
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
          if (out == null) throw new java.io.IOException("Gagal membuka file Download");
          out.write(log.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.content.ContentValues ready = new android.content.ContentValues();
        ready.put(android.provider.MediaStore.Downloads.IS_PENDING, 0);
        getContentResolver().update(uri, ready, null, null);
        message = "Log disimpan: Download/" + filename;
      } catch (Throwable t) {
        message = "Gagal menyimpan log: " + t.getMessage();
      }
      final String result = message;
      runOnUiThread(() -> {
        button.setEnabled(true);
        button.setText("Rekam Log Performa Virtual Display");
        Toast.makeText(this, result, Toast.LENGTH_LONG).show();
      });
    }, "display-performance-log").start();
  }

  private EditText field(LinearLayout parent, int label, String value, int type) {
    TextView text = new TextView(this);
    text.setText(label);
    text.setTextSize(14);
    addGap(parent, 12);
    parent.addView(text);
    EditText edit = new EditText(this);
    edit.setSingleLine(true);
    edit.setInputType(type);
    edit.setText(value);
    parent.addView(edit);
    return edit;
  }

  private void addGap(LinearLayout parent, int dp) {
    View gap = new View(this);
    parent.addView(gap, new LinearLayout.LayoutParams(1, dp(dp)));
  }

  private int dp(int value) {
    return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
  }

  private void createOverlay() {
    final int width, height, dpi;
    try {
      width = Integer.parseInt(widthInput.getText().toString().trim());
      height = Integer.parseInt(heightInput.getText().toString().trim());
      dpi = Integer.parseInt(dpiInput.getText().toString().trim());
    } catch (NumberFormatException e) {
      toast(R.string.overlay_invalid_values);
      return;
    }
    if (width < 320 || width > 3840 || height < 320 || height > 3840 || dpi < 80 || dpi > 640) {
      toast(R.string.overlay_values_out_of_range);
      return;
    }
    setBusy(true);
    new Thread(() -> {
      RootAccess.Result current = RootAccess.run("settings get global overlay_display_devices");
      if (!current.success) { runOnUiThread(() -> finishAction(false, current.output)); return; }
      String existing = current.output.trim();
      if ("null".equals(existing)) existing = "";
      String item = width + "x" + height + "/" + dpi;
      List<String> parts = split(existing);
      if (parts.contains(item)) {
        runOnUiThread(() -> finishAction(false, getString(R.string.overlay_already_exists)));
        return;
      }
      parts.add(item);
      RootAccess.Result result = RootAccess.run("settings put global overlay_display_devices " + quote(join(parts)));
      runOnUiThread(() -> {
        finishAction(result.success, result.success ? getString(R.string.overlay_created, item) : result.output);
        if (result.success) loadOverlays();
      });
    }).start();
  }

  private void loadOverlays() {
    statusText.setText(R.string.overlay_loading);
    new Thread(() -> {
      RootAccess.Result result = RootAccess.run("settings get global overlay_display_devices");
      runOnUiThread(() -> {
        overlays.clear();
        overlayChoices.removeAllViews();
        if (!result.success) {
          statusText.setText(getString(R.string.overlay_root_required, result.output));
          return;
        }
        String value = result.output.trim();
        if (!value.isEmpty() && !"null".equals(value)) overlays.addAll(split(value));
        if (overlays.isEmpty()) statusText.setText(R.string.overlay_none);
        else statusText.setText(getString(R.string.overlay_count, overlays.size()));
        for (int i = 0; i < overlays.size(); i++) {
          RadioButton choice = new RadioButton(this);
          choice.setId(View.generateViewId());
          choice.setText(overlays.get(i));
          choice.setTag(i);
          overlayChoices.addView(choice);
          if (i == overlays.size() - 1) choice.setChecked(true);
        }
      });
    }).start();
  }

  private void deleteSelected() {
    int checked = overlayChoices.getCheckedRadioButtonId();
    if (checked == -1) { toast(R.string.overlay_select_delete); return; }
    View selected = findViewById(checked);
    if (!(selected instanceof RadioButton)) return;
    Object tag = selected.getTag();
    if (!(tag instanceof Integer)) return;
    int index = (Integer) tag;
    if (index < 0 || index >= overlays.size()) return;
    String item = overlays.get(index);
    setBusy(true);
    new Thread(() -> {
      RootAccess.Result current = RootAccess.run("settings get global overlay_display_devices");
      if (!current.success) { runOnUiThread(() -> finishAction(false, current.output)); return; }
      List<String> latest = split(current.output.trim());
      if (index >= latest.size() || !latest.get(index).equals(item)) {
        runOnUiThread(() -> { finishAction(false, getString(R.string.overlay_changed_refresh)); loadOverlays(); });
        return;
      }
      latest.remove(index);
      String next = join(latest);
      String command = next.isEmpty()
          ? "settings delete global overlay_display_devices"
          : "settings put global overlay_display_devices " + quote(next);
      RootAccess.Result result = RootAccess.run(command);
      runOnUiThread(() -> {
        finishAction(result.success, result.success ? getString(R.string.overlay_deleted, item) : result.output);
        if (result.success) loadOverlays();
      });
    }).start();
  }

  private List<String> split(String value) {
    List<String> result = new ArrayList<>();
    if (value == null || value.trim().isEmpty() || "null".equals(value.trim())) return result;
    for (String part : value.split(";")) if (!part.trim().isEmpty()) result.add(part.trim());
    return result;
  }

  private String join(List<String> values) {
    StringBuilder result = new StringBuilder();
    for (String value : values) {
      if (result.length() > 0) result.append(';');
      result.append(value);
    }
    return result.toString();
  }

  private String quote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private void setBusy(boolean busy) {
    statusText.setText(busy ? R.string.overlay_working : R.string.overlay_loading);
  }

  private void finishAction(boolean success, String message) {
    statusText.setText(message == null || message.isEmpty()
        ? getString(success ? R.string.overlay_done : R.string.overlay_failed)
        : message);
    if (!success) toastText(message);
  }

  private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }
  private void toastText(String message) {
    Toast.makeText(this, message == null ? getString(R.string.overlay_failed) : message, Toast.LENGTH_LONG).show();
  }
}