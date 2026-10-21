package io.github.jqssun.displayextend;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.provider.Settings;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.MotionEventHidden;
import android.hardware.input.IInputManager;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.KeyEventHidden;
import dev.rikka.tools.refine.Refine;
import io.github.jqssun.displayextend.shizuku.ServiceUtils;
import io.github.jqssun.displayextend.shizuku.ShizukuUtils;

/**
 * Small movable keyboard hosted on the phone's built-in display. Keystrokes are
 * routed to the selected secondary display, keeping the IME surface off that display.
 */
public class FloatingKeyboardService extends Service {
  public static final String ACTION_SHOW = "io.github.jqssun.displayextend.SHOW_FLOATING_KEYBOARD";
  public static final String ACTION_HIDE = "io.github.jqssun.displayextend.HIDE_FLOATING_KEYBOARD";
  public static final String EXTRA_DISPLAY_ID = "display_id";

  private WindowManager windowManager;
  private View keyboardView;
  private View landscapeTrackpadView;
  private WindowManager.LayoutParams params;
  private WindowManager.LayoutParams landscapeTrackpadParams;
  private int targetDisplayId = 0;
  private boolean rootMode = false;
  private int keyHeightDp = 42;
  private int widthPercent = 70;
  private int verticalOffsetDp = 12;
  private int keyboardLayout = 0;
  private boolean settingsVisible = false;
  private float dragStartX, dragStartY;
  private int dragOriginX, dragOriginY;
  private android.content.SharedPreferences keyboardPrefs;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService rootInputExecutor = Executors.newSingleThreadExecutor();
  private IInputManager inputManager;
  private ImageView targetCursorView;
  private WindowManager targetCursorWindowManager;
  private WindowManager.LayoutParams targetCursorParams;
  private float mouseX = -1f, mouseY = -1f;
  private float lastFingerX, lastFingerY;
  private float filteredDeltaX, filteredDeltaY;
  private boolean trackpadMoved;
  private boolean mouseHoverStarted;
  private boolean twoFingerGesture;
  private int trackpadButton = MotionEvent.BUTTON_PRIMARY;
  // Lower gain and lightly filter touch noise to avoid a twitchy virtual cursor.
  private static final float POINTER_SENSITIVITY = 0.95f;
  private static final float POINTER_FILTER_CURRENT = 0.62f;
  private static final float POINTER_DEADZONE_PX = 0.45f;
  private boolean shiftEnabled = false;
  private boolean capsEnabled = false;
  private boolean ctrlEnabled = false;
  private boolean altEnabled = false;
  private boolean symbolsMode = false;
  private boolean gamepadMode = false;
  private final GameTouchController gameTouchController = new GameTouchController();
  private final java.util.ArrayDeque<String> gamepadDiagnosticLines = new java.util.ArrayDeque<>();
  private static final int MAX_GAMEPAD_DIAGNOSTIC_LINES = 350;
  private long lastGamepadMoveLogAt = 0L;
  private TextView gamepadCoordinateIndicator;
  private View visualMappingMarkerView;
  private WindowManager visualMappingWindowManager;
  private WindowManager.LayoutParams visualMappingMarkerParams;
  private String visualMappingRole;
  private Button visualMappingButton;
  private float visualMappingDragRawX, visualMappingDragRawY;
  private int visualMappingDragOriginX, visualMappingDragOriginY;

  private void updateGamepadCoordinateIndicator(String role, float x, float y) {
    if (gamepadCoordinateIndicator == null) return;
    android.util.DisplayMetrics dm = gameTouchController.metrics();
    float nx = dm.widthPixels > 0 ? x / dm.widthPixels : 0f;
    float ny = dm.heightPixels > 0 ? y / dm.heightPixels : 0f;
    gamepadCoordinateIndicator.setText(role.toUpperCase(java.util.Locale.ROOT)
        + "  X:" + Math.round(x) + " Y:" + Math.round(y)
        + "  ·  " + Math.round(nx * 100) + "% / " + Math.round(ny * 100) + "%");
  }

  /**
   * Lets the user select a HUD action, then drag its marker directly on the
   * target virtual display. Stored coordinates are normalized to that display.
   */
  private void showVisualMappingPicker() {
    java.util.ArrayList<String> labelsList = new java.util.ArrayList<>(java.util.Arrays.asList(
        "Serang", "Skill 1", "Skill 2", "Skill 3", "Ultimate", "Spell / Flicker", "Upgrade", "Recall"));
    java.util.ArrayList<String> rolesList = new java.util.ArrayList<>(java.util.Arrays.asList(
        "attack", "skill1", "skill2", "skill3", "ultimate", "spell", "upgrade", "recall"));
    for (int i = 1; i <= getCustomTriggerCount(); i++) {
      String customRole = "custom_" + i;
      rolesList.add(customRole);
      labelsList.add(getCustomTriggerLabel(i));
    }
    final String[] labels = labelsList.toArray(new String[0]);
    final String[] roles = rolesList.toArray(new String[0]);
    android.widget.Spinner spinner = new android.widget.Spinner(this);
    spinner.setAdapter(new android.widget.ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item, labels));
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView hint = new TextView(this);
    hint.setText("Pilih tombol yang ingin diatur. Setelah Mulai, penanda muncul di virtual display. Geser penanda tepat ke atas tombol HUD di game, lalu tekan Simpan posisi.");
    hint.setTextSize(13);
    content.addView(hint);
    content.addView(spinner);
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
        .setTitle("Mapping langsung di virtual display")
        .setView(content)
        .setPositiveButton("Mulai geser", (d, which) -> {
          int index = Math.max(0, Math.min(roles.length - 1, spinner.getSelectedItemPosition()));
          showVisualMappingMarker(roles[index], labels[index]);
        })
        .setNegativeButton("Batal", null)
        .create();
    if (dialog.getWindow() != null) dialog.getWindow().setType(
        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE);
    dialog.show();
  }

  private void showVisualMappingMarker(String role, String label) {
    removeVisualMappingMarker(false);
    android.hardware.display.DisplayManager displays =
        (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
    android.view.Display target = displays != null ? displays.getDisplay(targetDisplayId) : null;
    if (target == null) {
      Toast.makeText(this, "Virtual display tidak ditemukan. Pilih display target yang aktif.", Toast.LENGTH_LONG).show();
      recordGamepadDiagnostic("VISUAL_MAP_ERROR", "target display missing: " + targetDisplayId);
      return;
    }
    try {
      android.content.Context displayContext = createDisplayContext(target);
      visualMappingWindowManager = (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
      if (visualMappingWindowManager == null) throw new IllegalStateException("WindowManager target tidak tersedia");
      TextView marker = new TextView(displayContext);
      marker.setText(label + "\n↕ GESER");
      marker.setTextColor(Color.rgb(18, 25, 38));
      marker.setTextSize(11);
      marker.setTypeface(null, android.graphics.Typeface.BOLD);
      marker.setGravity(Gravity.CENTER);
      marker.setElevation(dp(5));
      marker.setBackground(createVisualMarkerBackground());
      marker.setContentDescription("Penanda mapping " + label + ". Seret untuk memindahkan, ketuk Simpan posisi di panel.");
      visualMappingRole = role;
      visualMappingMarkerView = marker;
      if (visualMappingButton != null) visualMappingButton.setText("SIMPAN POSISI PENANDA");
      android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
      target.getRealMetrics(dm);
      float[] point = gameTouchController.pointFor(role);
      int markerW = dp(76);
      int markerH = dp(58);
      visualMappingMarkerParams = new WindowManager.LayoutParams(
          markerW, markerH,
          Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
              : WindowManager.LayoutParams.TYPE_PHONE,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT);
      visualMappingMarkerParams.gravity = Gravity.TOP | Gravity.START;
      visualMappingMarkerParams.x = Math.max(0, Math.min(Math.max(0, dm.widthPixels - markerW), Math.round(point[0] - markerW / 2f)));
      visualMappingMarkerParams.y = Math.max(0, Math.min(Math.max(0, dm.heightPixels - markerH), Math.round(point[1] - markerH / 2f)));
      marker.setOnTouchListener((v, event) -> {
        switch (event.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            visualMappingDragRawX = event.getRawX();
            visualMappingDragRawY = event.getRawY();
            visualMappingDragOriginX = visualMappingMarkerParams.x;
            visualMappingDragOriginY = visualMappingMarkerParams.y;
            return true;
          case MotionEvent.ACTION_MOVE: {
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            target.getRealMetrics(metrics);
            int maxX = Math.max(0, metrics.widthPixels - visualMappingMarkerParams.width);
            int maxY = Math.max(0, metrics.heightPixels - visualMappingMarkerParams.height);
            visualMappingMarkerParams.x = Math.max(0, Math.min(maxX, visualMappingDragOriginX
                + Math.round(event.getRawX() - visualMappingDragRawX)));
            visualMappingMarkerParams.y = Math.max(0, Math.min(maxY, visualMappingDragOriginY
                + Math.round(event.getRawY() - visualMappingDragRawY)));
            try { visualMappingWindowManager.updateViewLayout(visualMappingMarkerView, visualMappingMarkerParams); }
            catch (Throwable t) { recordGamepadDiagnostic("VISUAL_MAP_MOVE_ERROR", t.getMessage()); }
            float centerX = visualMappingMarkerParams.x + visualMappingMarkerParams.width / 2f;
            float centerY = visualMappingMarkerParams.y + visualMappingMarkerParams.height / 2f;
            float nx = metrics.widthPixels > 0 ? centerX / metrics.widthPixels : 0f;
            float ny = metrics.heightPixels > 0 ? centerY / metrics.heightPixels : 0f;
            updateGamepadCoordinateIndicator(visualMappingRole, centerX, centerY);
                    return true;
          }
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            Toast.makeText(FloatingKeyboardService.this,
                "Posisi " + label + ": lepaskan penanda, lalu tekan SIMPAN POSISI.", Toast.LENGTH_SHORT).show();
            return true;
        }
        return true;
      });
      visualMappingWindowManager.addView(visualMappingMarkerView, visualMappingMarkerParams);
      recordGamepadDiagnostic("VISUAL_MAP_START", "role=" + role + ", display=" + targetDisplayId);
      Toast.makeText(this, "Geser penanda di atas tombol HUD. Simpan posisi dari panel gamepad.", Toast.LENGTH_LONG).show();
    } catch (Throwable t) {
      recordGamepadDiagnostic("VISUAL_MAP_ERROR", t.getClass().getSimpleName() + ": " + t.getMessage());
      State.log("visual mapping marker failed: " + t);
      removeVisualMappingMarker(false);
      Toast.makeText(this, "Penanda visual gagal dibuka: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
    }
  }

  private android.graphics.drawable.GradientDrawable createVisualMarkerBackground() {
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
    bg.setColor(Color.rgb(111, 211, 244));
    bg.setCornerRadius(dp(14));
    bg.setStroke(dp(2), Color.WHITE);
    return bg;
  }

  private void recordVisualMappingPosition(String role, float nx, float ny, boolean toast) {
    nx = Math.max(0f, Math.min(1f, nx));
    ny = Math.max(0f, Math.min(1f, ny));
    keyboardPrefs.edit().putFloat("game_x_" + role, nx).putFloat("game_y_" + role, ny).apply();
    if (toast) Toast.makeText(this, "Posisi " + role + " disimpan.", Toast.LENGTH_SHORT).show();
  }

  private void saveVisualMappingPosition() {
    if (visualMappingMarkerParams == null || visualMappingRole == null) {
      Toast.makeText(this, "Pilih Mapping Visual dulu.", Toast.LENGTH_SHORT).show();
      return;
    }
    android.hardware.display.DisplayManager displays =
        (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
    android.view.Display target = displays != null ? displays.getDisplay(targetDisplayId) : null;
    if (target == null) return;
    android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
    target.getRealMetrics(dm);
    float cx = visualMappingMarkerParams.x + visualMappingMarkerParams.width / 2f;
    float cy = visualMappingMarkerParams.y + visualMappingMarkerParams.height / 2f;
    recordVisualMappingPosition(visualMappingRole,
        dm.widthPixels > 0 ? cx / dm.widthPixels : 0f,
        dm.heightPixels > 0 ? cy / dm.heightPixels : 0f, true);
    recordGamepadDiagnostic("VISUAL_MAP_SAVED", "role=" + visualMappingRole
        + ", x=" + Math.round(cx) + ", y=" + Math.round(cy)
        + ", display=" + targetDisplayId);
    removeVisualMappingMarker(false);
  }

  private void removeVisualMappingMarker(boolean save) {
    if (save) saveVisualMappingPosition();
    if (visualMappingMarkerView != null && visualMappingWindowManager != null) {
      try { visualMappingWindowManager.removeView(visualMappingMarkerView); } catch (Throwable ignored) {}
    }
    visualMappingMarkerView = null;
    visualMappingMarkerParams = null;
    visualMappingWindowManager = null;
    visualMappingRole = null;
    if (visualMappingButton != null) visualMappingButton.setText("MAPPING VISUAL · GESER PENANDA");
  }

  private synchronized void recordGamepadDiagnostic(String category, String message) {
    String line = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
        .format(new java.util.Date()) + " [" + category + "] " + message;
    if (gamepadDiagnosticLines.size() >= MAX_GAMEPAD_DIAGNOSTIC_LINES) {
      gamepadDiagnosticLines.removeFirst();
    }
    gamepadDiagnosticLines.addLast(line);
  }

  private synchronized String buildGamepadDiagnosticReport() {
    StringBuilder report = new StringBuilder();
    report.append("Android Display Extend — Gamepad Diagnostics\n")
        .append("Display target: ").append(targetDisplayId).append('\n')
        .append("Mode: ").append(gamepadMode ? "GAMEPAD" : "KEYBOARD/TRACKPAD").append('\n')
        .append("Root input: ").append(rootMode).append('\n')
        .append("Log lines retained: ").append(gamepadDiagnosticLines.size()).append('/')
        .append(MAX_GAMEPAD_DIAGNOSTIC_LINES).append('\n')
        .append("Note: accepted=true means Android accepted injection, not that the game registered it.\n")
        .append("--------------------------------------------------\n");
    synchronized (this) {
      for (String line : gamepadDiagnosticLines) report.append(line).append('\n');
    }
    return report.toString();
  }

  private void showGamepadDiagnostics() {
    recordGamepadDiagnostic("SESSION", "Diagnostic viewer opened; display=" + targetDisplayId);
    TextView output = new TextView(this);
    output.setText(buildGamepadDiagnosticReport());
    output.setTextColor(Color.rgb(225, 235, 255));
    output.setTextSize(11);
    output.setTextIsSelectable(true);
    output.setPadding(dp(12), dp(8), dp(12), dp(8));
    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.setFillViewport(true);
    scroll.addView(output);
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
        .setTitle("Log diagnosis gamepad")
        .setView(scroll)
        .setPositiveButton("Salin log", (d, which) -> {
          String report = buildGamepadDiagnosticReport();
          android.content.ClipboardManager clipboard =
              (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
          if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gamepad diagnostics", report));
            Toast.makeText(this, "Log disalin. Tempelkan ke chat untuk dianalisis.", Toast.LENGTH_LONG).show();
          }
        })
        .setNeutralButton("Bagikan", (d, which) -> {
          android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND);
          share.setType("text/plain");
          share.putExtra(android.content.Intent.EXTRA_SUBJECT, "Android Display Extend gamepad diagnostics");
          share.putExtra(android.content.Intent.EXTRA_TEXT, buildGamepadDiagnosticReport());
          share.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
          try {
            startActivity(android.content.Intent.createChooser(share, "Bagikan log diagnosis")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
          } catch (Throwable t) {
            State.log("gamepad diagnostic share failed: " + t.getMessage());
            Toast.makeText(this, "Gagal membuka menu berbagi log.", Toast.LENGTH_SHORT).show();
          }
        })
        .setNegativeButton("Tutup", null)
        .create();
    if (dialog.getWindow() != null && Build.VERSION.SDK_INT >= 26) {
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }
    dialog.show();
    if (dialog.getWindow() != null) dialog.getWindow().setLayout(
        Math.max(dp(260), (int) (getResources().getDisplayMetrics().widthPixels * 0.88f)),
        Math.max(dp(260), (int) (getResources().getDisplayMetrics().heightPixels * 0.72f)));
  }

  private void installCrashLogHandler() {
    final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
      try {
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        String report = "Extend crash report\\nTime: "
            + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
            + "\\nThread: " + thread.getName() + "\\nAndroid: " + Build.VERSION.RELEASE
            + " (SDK " + Build.VERSION.SDK_INT + ")\\n\\n" + trace;
        if (Build.VERSION.SDK_INT >= 29) {
          ContentValues values = new ContentValues();
          values.put(MediaStore.MediaColumns.DISPLAY_NAME, "Extend_crash_"
              + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt");
          values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
          values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ExtendLogs");
          Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
          if (uri != null) {
            try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
              if (stream != null) stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
          }
        }
      } catch (Throwable ignored) { }
      if (previous != null) previous.uncaughtException(thread, error);
    });
  }

  @Override
  public void onCreate() {
    super.onCreate();
    installCrashLogHandler();
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    keyboardPrefs = getSharedPreferences("floating_keyboard", MODE_PRIVATE);
    widthPercent = keyboardPrefs.getInt("width_percent", 70);
    // Older builds defaulted to 96%, which made the landscape keyboard too wide.
    if (widthPercent == 96) {
      widthPercent = 70;
      keyboardPrefs.edit().putInt("width_percent", widthPercent).apply();
    }
    keyHeightDp = keyboardPrefs.getInt("key_height_dp", 42);
    int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
    int defaultY = Math.max(12, screenHeightDp - (keyHeightDp * 4) - 60 - 12);
    verticalOffsetDp = keyboardPrefs.getInt("vertical_offset_dp", defaultY);
    keyboardLayout = keyboardPrefs.getInt("layout", 0);
    gamepadMode = keyboardPrefs.getBoolean("gamepad_mode", false);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent != null) targetDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, 0);
    if (!Settings.canDrawOverlays(this)) {
      Toast.makeText(this, "Aktifkan izin tampil di atas aplikasi lain terlebih dahulu.", Toast.LENGTH_LONG).show();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (ShizukuUtils.hasPermission()) {
      rootMode = false;
      try {
        inputManager = ServiceUtils.getInputManager();
        if (inputManager == null) State.log("floating input: Shizuku InputManager unavailable");
      } catch (Throwable e) {
        inputManager = null;
        State.log("floating input: failed to obtain InputManager: " + e.getMessage());
      }
      if (keyboardView == null) showKeyboard();
    } else {
      // Request root only when the user explicitly opens the floating keyboard.
      Toast.makeText(this, "Setujui permintaan root untuk mengirim input keyboard.", Toast.LENGTH_LONG).show();
      new Thread(
              () -> {
                RootAccess.Result result = RootAccess.check();
                if (result.success && result.output.contains("uid=0")) {
                  rootMode = true;
                  mainHandler.post(() -> { if (keyboardView == null) showKeyboard(); });
                } else {
                  mainHandler.post(
                      () -> {
                        Toast.makeText(this, "Keyboard memerlukan izin Shizuku atau akses root yang disetujui.", Toast.LENGTH_LONG).show();
                        stopSelf();
                      });
                }
              })
          .start();
    }
    return START_NOT_STICKY;
  }

  private void showKeyboard() {
    if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      showLandscapeKeyboard();
      return;
    }
    if (gamepadMode) {
      showPortraitGamepad();
      return;
    }
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(8), dp(8), dp(8), dp(8));
    android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
    panelBg.setColor(Color.rgb(18, 25, 38));
    panelBg.setCornerRadius(dp(18));
    panelBg.setStroke(dp(1), Color.rgb(64, 86, 112));
    panel.setBackground(panelBg);

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(this);
    title.setText("DESK  ·  Layar " + targetDisplayId + "  ·  QWERTY ID");
    title.setTextColor(Color.rgb(225, 241, 255));
    title.setTextSize(12);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
    title.setOnTouchListener((v, event) -> {
      if (params == null) return false;
      switch (event.getAction()) {
        case MotionEvent.ACTION_DOWN:
          dragStartX = event.getRawX(); dragStartY = event.getRawY();
          dragOriginX = params.x; dragOriginY = params.y; return true;
        case MotionEvent.ACTION_MOVE:
          params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
          params.x = dragOriginX + Math.round(event.getRawX() - dragStartX);
          params.y = Math.max(0, dragOriginY + Math.round(event.getRawY() - dragStartY));
          try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
          return true;
        case MotionEvent.ACTION_UP:
          verticalOffsetDp = Math.max(0, Math.round(params.y / getResources().getDisplayMetrics().density));
          keyboardPrefs.edit().putInt("vertical_offset_dp", verticalOffsetDp).apply();
          return true;
      }
      return true;
    });
    Button customize = new Button(this);
    customize.setText(settingsVisible ? "Tutup" : "Atur");
    customize.setAllCaps(false);
    customize.setTextSize(11);
    styleButton(customize, true);
    customize.setContentDescription("Atur tata letak dan ukuran keyboard");
    customize.setOnClickListener(v -> { settingsVisible = !settingsVisible; rebuildKeyboard(); });
    titleRow.addView(customize, new LinearLayout.LayoutParams(dp(56), dp(36)));
    Button close = new Button(this);
    close.setText("×");
    styleButton(close, false);
    close.setOnClickListener(v -> stopSelf());
    titleRow.addView(close, new LinearLayout.LayoutParams(dp(48), dp(36)));
    panel.addView(titleRow);

    if (settingsVisible) addSettingsPanel(panel); else addKeyboardLayout(panel);

    int type = Build.VERSION.SDK_INT >= 26
        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        : WindowManager.LayoutParams.TYPE_PHONE;
    android.util.DisplayMetrics displayMetrics = new android.util.DisplayMetrics();
    windowManager.getDefaultDisplay().getMetrics(displayMetrics);
    params = new WindowManager.LayoutParams(
        Math.max(dp(260), Math.round(displayMetrics.widthPixels * widthPercent / 100f)),
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    params.y = dp(verticalOffsetDp);
    keyboardView = panel;
    try {
      windowManager.addView(keyboardView, params);
    } catch (Exception e) {
      Toast.makeText(this, "Keyboard overlay tidak dapat ditampilkan: " + e.getMessage(), Toast.LENGTH_LONG).show();
      stopSelf();
    }
  }

  /** Portrait gamepad layout: compact right-side overlay with a scrollable control panel. */
  private void showPortraitGamepad() {
    final int screenWidth = getResources().getDisplayMetrics().widthPixels;
    final int screenHeight = getResources().getDisplayMetrics().heightPixels;
    LinearLayout panel = new LinearLayout(this);
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(5), dp(5), dp(5), dp(5));
    panel.setBackground(createPanelBackground());

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(this);
    title.setText("🎮 Gamepad kanan · Portrait");
    title.setTextColor(Color.rgb(225, 235, 255));
    title.setTextSize(11);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(34), 1f));
    title.setOnTouchListener((v, event) -> {
      if (params == null || keyboardView == null) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragStartX = event.getRawX(); dragStartY = event.getRawY();
          dragOriginX = params.x; dragOriginY = params.y; return true;
        case MotionEvent.ACTION_MOVE:
          params.x = Math.max(0, dragOriginX - Math.round(event.getRawX() - dragStartX));
          params.y = Math.max(0, dragOriginY + Math.round(event.getRawY() - dragStartY));
          try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
          return true;
        case MotionEvent.ACTION_UP:
          keyboardPrefs.edit().putInt("portrait_gamepad_x", params.x)
              .putInt("portrait_gamepad_y", params.y).apply();
          return true;
      }
      return true;
    });
    Button mode = new Button(this);
    mode.setText("⌨");
    mode.setAllCaps(false); mode.setTextSize(10); styleButton(mode, false);
    mode.setOnClickListener(v -> {
      gamepadMode = false;
      keyboardPrefs.edit().putBoolean("gamepad_mode", false).apply();
      rebuildKeyboard();
    });
    titleRow.addView(mode, new LinearLayout.LayoutParams(dp(34), dp(32)));
    Button close = new Button(this);
    close.setText("×"); close.setAllCaps(false); styleButton(close, false);
    close.setOnClickListener(v -> stopSelf());
    titleRow.addView(close, new LinearLayout.LayoutParams(dp(34), dp(32)));
    panel.addView(titleRow);

    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(true);
    LinearLayout controls = new LinearLayout(this);
    controls.setOrientation(LinearLayout.VERTICAL);

    TextView moveTitle = new TextView(this);
    moveTitle.setText("ANALOG GERAK");
    moveTitle.setTextColor(Color.rgb(174, 207, 245));
    moveTitle.setTextSize(9);
    moveTitle.setGravity(Gravity.CENTER);
    controls.addView(moveTitle, new LinearLayout.LayoutParams(-1, dp(20)));
    AnalogStickView movementStick = new AnalogStickView();
    movementStick.setContentDescription("Analog gerak karakter");
    controls.addView(movementStick, new LinearLayout.LayoutParams(-1, dp(112)));

    addLandscapeGamepadRight(controls);
    scroll.addView(controls);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

    int type = Build.VERSION.SDK_INT >= 26
        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    int panelWidth = Math.min(Math.max(dp(220), Math.round(screenWidth * 0.62f)), screenWidth);
    int compactHeight = Math.min(screenHeight - dp(24),
        Math.max(dp(300), Math.round(screenHeight * 0.68f)));
    params = new WindowManager.LayoutParams(panelWidth, compactHeight,
        type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.RIGHT;
    params.x = Math.max(0, keyboardPrefs.getInt("portrait_gamepad_x", dp(4)));
    params.y = Math.max(0, keyboardPrefs.getInt("portrait_gamepad_y", dp(4)));
    keyboardView = panel;
    try {
      windowManager.addView(keyboardView, params);
    } catch (Exception e) {
      State.log("portrait gamepad overlay failed: " + e.getMessage());
      Toast.makeText(this, "Gamepad portrait gagal ditampilkan: " + e.getMessage(), Toast.LENGTH_LONG).show();
      stopSelf();
    }
  }

  /**
   * Landscape mode uses two independent overlay windows so the virtual display
   * remains visible and touchable in the center between the keyboard and pad.
   */
  private void showLandscapeKeyboard() {
    final float density = getResources().getDisplayMetrics().density;
    final int screenWidth = getResources().getDisplayMetrics().widthPixels;
    final int screenHeight = getResources().getDisplayMetrics().heightPixels;
    LinearLayout left = new LinearLayout(this);
    left.setOrientation(LinearLayout.VERTICAL);
    left.setPadding(dp(5), dp(5), dp(5), dp(5));
    left.setBackground(createPanelBackground());
    left.setClipToPadding(false);

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(this);
    title.setText("⌨  Keyboard · QWERTY ID");
    title.setTextColor(Color.rgb(225, 235, 255));
    title.setTextSize(12);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(32), 1f));
    title.setOnTouchListener((v, event) -> {
      if (params == null || keyboardView == null) return false;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragStartX = event.getRawX(); dragStartY = event.getRawY();
          dragOriginX = params.x; dragOriginY = params.y; return true;
        case MotionEvent.ACTION_MOVE:
          params.x = Math.max(0, dragOriginX + Math.round(event.getRawX() - dragStartX));
          params.y = Math.max(0, dragOriginY + Math.round(event.getRawY() - dragStartY));
          try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
          return true;
        case MotionEvent.ACTION_UP:
          verticalOffsetDp = Math.max(0, Math.round(params.y / density));
          keyboardPrefs.edit().putInt("vertical_offset_dp", verticalOffsetDp).apply();
          return true;
      }
      return true;
    });
    Button modeButton = new Button(this);
    modeButton.setText(gamepadMode ? "⌨" : "🎮");
    modeButton.setAllCaps(false);
    modeButton.setTextSize(10);
    styleButton(modeButton, gamepadMode);
    modeButton.setContentDescription("Ganti mode keyboard atau gamepad");
    modeButton.setOnClickListener(v -> {
      gamepadMode = !gamepadMode;
      keyboardPrefs.edit().putBoolean("gamepad_mode", gamepadMode).apply();
      settingsVisible = false;
      rebuildKeyboard();
    });
    titleRow.addView(modeButton, new LinearLayout.LayoutParams(dp(38), dp(32)));
    Button customize = new Button(this);
    customize.setText(settingsVisible ? "Tutup" : "⚙");
    customize.setAllCaps(false);
    customize.setTextSize(10);
    styleButton(customize, true);
    customize.setOnClickListener(v -> { settingsVisible = !settingsVisible; rebuildKeyboard(); });
    titleRow.addView(customize, new LinearLayout.LayoutParams(dp(42), dp(32)));
    Button close = new Button(this);
    close.setText("×");
    close.setTextSize(14);
    styleButton(close, false);
    close.setOnClickListener(v -> stopSelf());
    titleRow.addView(close, new LinearLayout.LayoutParams(dp(34), dp(32)));
    left.addView(titleRow);

    if (settingsVisible) {
      addLandscapeSettingsPanel(left);
    } else {
      if (gamepadMode) addLandscapeGamepadLeft(left); else addLandscapeLaptopKeyboard(left);
      addLandscapeResizeGrip(left);
    }
    keyboardView = left;

    int type = Build.VERSION.SDK_INT >= 26
        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        : WindowManager.LayoutParams.TYPE_PHONE;
    params = new WindowManager.LayoutParams(
        Math.max(dp(180), Math.round(screenWidth * 0.50f * widthPercent / 100f)),
        Math.min(screenHeight - dp(12), WindowManager.LayoutParams.WRAP_CONTENT),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.TOP | Gravity.LEFT;
    params.x = dp(6);
    params.y = dp(6);

    LinearLayout right = new LinearLayout(this);
    right.setOrientation(LinearLayout.VERTICAL);
    right.setPadding(dp(5), dp(5), dp(5), dp(5));
    right.setBackground(createPanelBackground());
    TextView trackpadTitle = new TextView(this);
    trackpadTitle.setText(gamepadMode ? "🎮  Gamepad kanan" : "🖱  Trackpad");
    trackpadTitle.setTextColor(Color.rgb(225, 235, 255));
    trackpadTitle.setTextSize(12);
    trackpadTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    trackpadTitle.setGravity(Gravity.CENTER_VERTICAL);
    right.addView(trackpadTitle, new LinearLayout.LayoutParams(-1, dp(32)));
    if (gamepadMode) {
      View gamepadSpacer = new View(this);
      right.addView(gamepadSpacer, new LinearLayout.LayoutParams(-1, 0, 1f));
      addLandscapeGamepadRight(right);
    } else {
      addIntegratedTrackpad(right);
    }
    landscapeTrackpadView = right;
    landscapeTrackpadParams = new WindowManager.LayoutParams(
        Math.max(dp(170), Math.round(screenWidth * 0.25f)),
        Math.min(screenHeight - dp(12), WindowManager.LayoutParams.MATCH_PARENT),
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    landscapeTrackpadParams.gravity = Gravity.TOP | Gravity.RIGHT;
    landscapeTrackpadParams.x = dp(6);
    landscapeTrackpadParams.y = dp(6);

    try {
      windowManager.addView(keyboardView, params);
      windowManager.addView(landscapeTrackpadView, landscapeTrackpadParams);
    } catch (Exception e) {
      State.log("landscape keyboard overlay failed: " + e.getMessage());
      try { if (keyboardView != null) windowManager.removeView(keyboardView); } catch (Exception ignored) {}
      try { if (landscapeTrackpadView != null) windowManager.removeView(landscapeTrackpadView); } catch (Exception ignored) {}
      keyboardView = null;
      landscapeTrackpadView = null;
      stopSelf();
    }
  }

  // Virtual MOBA gamepad: inject real touch contacts into the selected display.
  // Default control coordinates are normalized, so they scale with the virtual display.
  private void addLandscapeGamepadLeft(LinearLayout panel) {
    TextView note = new TextView(this);
    note.setText("ANALOG SENTUH · GERAK");
    note.setTextColor(Color.rgb(174, 207, 245));
    note.setTextSize(10);
    note.setGravity(Gravity.CENTER);
    panel.addView(note, new LinearLayout.LayoutParams(-1, dp(28)));

    AnalogStickView stick = new AnalogStickView();
    LinearLayout.LayoutParams stickLp = new LinearLayout.LayoutParams(-1, dp(250));
    stickLp.setMargins(dp(6), dp(4), dp(6), dp(10));
    panel.addView(stick, stickLp);

    LinearLayout shoulders = new LinearLayout(this);
    shoulders.setGravity(Gravity.CENTER);
    addTouchGameButton(shoulders, "RECALL", 1f, "recall", false);
    addTouchGameButton(shoulders, "SPELL", 1f, "spell", false);
    panel.addView(shoulders, new LinearLayout.LayoutParams(-1, dp(48)));

    TextView hint = new TextView(this);
    hint.setText("Geser analog untuk bergerak · tarik skill untuk bidik");
    hint.setTextColor(Color.rgb(174, 207, 245));
    hint.setTextSize(9);
    hint.setGravity(Gravity.CENTER);
    panel.addView(hint, new LinearLayout.LayoutParams(-1, dp(30)));
  }

  private final class AnalogStickView extends View {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private float centerX, centerY, baseRadius, knobX, knobY;
    private boolean active = false;

    AnalogStickView() { super(FloatingKeyboardService.this); setFocusable(true); }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
      super.onDraw(canvas);
      centerX = getWidth() / 2f;
      centerY = getHeight() / 2f;
      baseRadius = Math.max(dp(40), Math.min(getWidth(), getHeight()) * 0.39f);
      if (!active) { knobX = centerX; knobY = centerY; }
      paint.setStyle(android.graphics.Paint.Style.FILL);
      paint.setColor(Color.rgb(24, 38, 57));
      canvas.drawCircle(centerX, centerY, baseRadius, paint);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(dp(2));
      paint.setColor(Color.rgb(77, 143, 183));
      canvas.drawCircle(centerX, centerY, baseRadius, paint);
      paint.setStyle(android.graphics.Paint.Style.FILL);
      paint.setColor(active ? Color.rgb(76, 171, 204) : Color.rgb(57, 91, 119));
      canvas.drawCircle(knobX, knobY, baseRadius * 0.43f, paint);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1));
      paint.setColor(Color.rgb(167, 202, 255));
      canvas.drawCircle(knobX, knobY, baseRadius * 0.43f, paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      centerX = getWidth() / 2f;
      centerY = getHeight() / 2f;
      baseRadius = Math.max(dp(40), Math.min(getWidth(), getHeight()) * 0.39f);
      float maxDisplayRadius = gameTouchController.getMoveRadius();
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          active = true;
          knobX = centerX;
          knobY = centerY;
          gameTouchController.press("move", gameTouchController.pointFor("move"));
          invalidate();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!active) return true;
          float dx = event.getX() - centerX;
          float dy = event.getY() - centerY;
          float distance = (float) Math.sqrt(dx * dx + dy * dy);
          float visualLimit = Math.max(1f, baseRadius);
          if (distance > visualLimit) { dx *= visualLimit / distance; dy *= visualLimit / distance; }
          knobX = centerX + dx;
          knobY = centerY + dy;
          float nx = dx / visualLimit;
          float ny = dy / visualLimit;
          float[] origin = gameTouchController.pointFor("move");
          gameTouchController.move("move",
              origin[0] + nx * maxDisplayRadius,
              origin[1] + ny * maxDisplayRadius);
          invalidate();
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          active = false;
          gameTouchController.release("move");
          invalidate();
          return true;
      }
      return true;
    }
  }

  private void showGamepadCustomization() {
    final String[] buttons = {"Serang", "S1", "S2", "S3", "Ultimate", "Spell", "Upgrade", "Recall"};
    final String[] roles = {"attack", "skill1", "skill2", "skill3", "ultimate", "spell", "upgrade", "recall"};
    final android.widget.Spinner buttonSpinner = new android.widget.Spinner(this);
    final android.widget.Spinner targetSpinner = new android.widget.Spinner(this);
    android.widget.ArrayAdapter<String> buttonAdapter = new android.widget.ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item, buttons);
    android.widget.ArrayAdapter<String> targetAdapter = new android.widget.ArrayAdapter<>(this,
        android.R.layout.simple_spinner_dropdown_item, buttons);
    buttonSpinner.setAdapter(buttonAdapter);
    targetSpinner.setAdapter(targetAdapter);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView mappingTitle = new TextView(this);
    mappingTitle.setText("LANGKAH 1 · Pilih tombol yang kamu tekan di panel.");
    mappingTitle.setTextSize(14);
    mappingTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    content.addView(mappingTitle);
    content.addView(buttonSpinner);
    TextView targetHint = new TextView(this);
    targetHint.setText("LANGKAH 2 · Pilih tombol HUD di dalam game yang harus disentuh.");
    targetHint.setPadding(0, dp(8), 0, dp(2));
    content.addView(targetHint);
    content.addView(targetSpinner);
    TextView positionTitle = new TextView(this);
    positionTitle.setText("LANGKAH 3 · Atur lokasi tombol HUD. Geser X ke kiri/kanan dan Y ke atas/bawah (persen layar).");
    positionTitle.setPadding(0, dp(12), 0, dp(4));
    content.addView(positionTitle);
    TextView xLabel = new TextView(this);
    TextView yLabel = new TextView(this);
    SeekBar xSeek = new SeekBar(this);
    SeekBar ySeek = new SeekBar(this);
    xSeek.setMax(100); ySeek.setMax(100);
    content.addView(xLabel); content.addView(xSeek);
    content.addView(yLabel); content.addView(ySeek);
    final Runnable[] loadPosition = new Runnable[1];
    loadPosition[0] = () -> {
      int index = targetSpinner.getSelectedItemPosition();
      String role = roles[Math.max(0, Math.min(roles.length - 1, index))];
      float x = keyboardPrefs.getFloat("game_x_" + role, defaultGameX(role));
      float y = keyboardPrefs.getFloat("game_y_" + role, defaultGameY(role));
      xSeek.setProgress(Math.round(x * 100f)); ySeek.setProgress(Math.round(y * 100f));
      xLabel.setText("X: " + xSeek.getProgress() + "%");
      yLabel.setText("Y: " + ySeek.getProgress() + "%");
    };
    targetSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { loadPosition[0].run(); }
      @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    });
    buttonSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
        String mapped = keyboardPrefs.getString("game_map_" + roles[pos], roles[pos]);
        int mappedIndex = 0;
        for (int i = 0; i < roles.length; i++) if (roles[i].equals(mapped)) mappedIndex = i;
        targetSpinner.setSelection(mappedIndex);
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    });
    xSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean user) { xLabel.setText("X: " + p + "%"); }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    ySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int p, boolean user) { yLabel.setText("Y: " + p + "%"); }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    loadPosition[0].run();
    TextView mappingHelp = new TextView(this);
    mappingHelp.setText("Contoh: pilih S1 di langkah 1, lalu pilih Skill 1 di langkah 2. Geser X/Y sampai titiknya tepat di atas tombol Skill 1 pada game. Tekan Simpan, lalu tutup dan buka lagi panel gamepad.");
    mappingHelp.setTextSize(12);
    mappingHelp.setPadding(dp(16), dp(8), dp(16), dp(12));
    mappingHelp.setTextColor(Color.rgb(45, 55, 70));
    content.addView(mappingHelp);
    android.widget.ScrollView dialogScroll = new android.widget.ScrollView(this);
    dialogScroll.setFillViewport(true);
    dialogScroll.addView(content);
    // This UI is launched from a Service/overlay, not an Activity. Create the
    // dialog first and assign an overlay window type before show(), otherwise
    // Android can throw BadTokenException and kill the floating keyboard.
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(
        this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
      .setTitle("Atur tombol gamepad")
      .setView(dialogScroll)
      .setPositiveButton("Simpan", (d, w) -> {
        int buttonIndex = Math.max(0, Math.min(roles.length - 1, buttonSpinner.getSelectedItemPosition()));
        int targetIndex = Math.max(0, Math.min(roles.length - 1, targetSpinner.getSelectedItemPosition()));
        String buttonRole = roles[buttonIndex];
        String targetRole = roles[targetIndex];
        keyboardPrefs.edit()
          .putString("game_map_" + buttonRole, targetRole)
          .putFloat("game_x_" + targetRole, xSeek.getProgress() / 100f)
          .putFloat("game_y_" + targetRole, ySeek.getProgress() / 100f)
          .apply();
        recordGamepadDiagnostic("CONFIG", "map " + buttonRole + " -> " + targetRole
            + ", x=" + xSeek.getProgress() + "%, y=" + ySeek.getProgress() + "%");
        Toast.makeText(this, "Mapping dan posisi disimpan. Tutup lalu buka lagi mode gamepad agar diterapkan.", Toast.LENGTH_LONG).show();
      })
      .setNeutralButton("Reset posisi", (d, w) -> {
        String role = roles[Math.max(0, Math.min(roles.length - 1, targetSpinner.getSelectedItemPosition()))];
        keyboardPrefs.edit().remove("game_x_" + role).remove("game_y_" + role).apply();
        Toast.makeText(this, "Posisi aksi direset ke bawaan.", Toast.LENGTH_SHORT).show();
      })
      .setNegativeButton("Batal", null)
      .create();
    android.view.Window dialogWindow = dialog.getWindow();
    if (dialogWindow != null) {
      dialogWindow.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
      dialogWindow.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
    }
    try {
      dialog.show();
      android.view.Window shownWindow = dialog.getWindow();
      if (shownWindow != null) {
        android.view.WindowManager.LayoutParams layout = shownWindow.getAttributes();
        layout.gravity = Gravity.RIGHT | Gravity.CENTER_VERTICAL;
        layout.x = dp(8);
        layout.width = Math.min(dp(390), getResources().getDisplayMetrics().widthPixels - dp(24));
        layout.height = Math.min(dp(620), getResources().getDisplayMetrics().heightPixels - dp(24));
        shownWindow.setAttributes(layout);
      }
      recordGamepadDiagnostic("CONFIG", "customization dialog shown on right side");
    } catch (Throwable error) {
      recordGamepadDiagnostic("CONFIG_ERROR", error.getClass().getSimpleName() + ": " + error.getMessage());
      State.log("gamepad customization dialog failed: " + error);
      Toast.makeText(this, "Dialog mapping gagal dibuka: " + error.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
    }
  }

  private float defaultGameX(String role) {
    switch (role) {
      case "move": return 0.13f;
      case "skill1": return 0.82f;
      case "skill2": return 0.86f;
      case "skill3": return 0.91f;
      case "ultimate": return 0.96f;
      case "spell": return 0.71f;
      case "upgrade": return 0.70f;
      case "recall": return 0.61f;
      default: return 0.94f;
    }
  }

  private float defaultGameY(String role) {
    switch (role) {
      case "move": return 0.87f;
      case "skill1": return 0.90f;
      case "skill2": return 0.83f;
      case "skill3": return 0.82f;
      case "ultimate": return 0.87f;
      case "spell": return 0.93f;
      case "upgrade": return 0.84f;
      case "recall": return 0.94f;
      default: return 0.87f;
    }
  }

  private int gameButtonHeight(String role, int fallbackDp) {
    return Math.max(fallbackDp, keyboardPrefs.getInt("game_btn_h_" + role, 0));
  }

  private void showServiceOverlayDialog(android.app.AlertDialog dialog) {
    if (dialog == null) return;
    if (dialog.getWindow() != null) {
      dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26
          ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
          : WindowManager.LayoutParams.TYPE_PHONE);
    }
    dialog.show();
  }

  /**
   * Gamepad buttons are visually translated outside their original LinearLayout row
   * while being repositioned. Android clips children at several container levels by
   * default (especially rows inside the ScrollView), which makes a button appear cut
   * off when dragged upward. Disable clipping along this overlay's ancestor chain.
   */
  private void disableGamepadClippingRecursively(android.view.ViewGroup group) {
    group.setClipChildren(false);
    group.setClipToPadding(false);
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child instanceof android.view.ViewGroup) {
        disableGamepadClippingRecursively((android.view.ViewGroup) child);
      }
    }
  }

  private void allowGamepadButtonOverflow(View view) {
    // Rows are descendants of the panel and also clip translated children by default.
    if (view instanceof android.view.ViewGroup) {
      disableGamepadClippingRecursively((android.view.ViewGroup) view);
    }
    // Also disable clipping on ancestors, including the ScrollView that hosts the panel.
    View current = view;
    while (current != null) {
      if (current instanceof android.view.ViewGroup) {
        android.view.ViewGroup group = (android.view.ViewGroup) current;
        group.setClipChildren(false);
        group.setClipToPadding(false);
      }
      android.view.ViewParent parent = current.getParent();
      current = parent instanceof View ? (View) parent : null;
    }
  }

  private void addLandscapeGamepadRight(LinearLayout panel) {
    allowGamepadButtonOverflow(panel);
    Button gamepadSettings = new Button(this);
    gamepadSettings.setText("PENGATURAN GAMEPAD");
    gamepadSettings.setAllCaps(false);
    gamepadSettings.setTextSize(10);
    styleButton(gamepadSettings, true);
    gamepadSettings.setOnClickListener(v -> {
      android.app.AlertDialog settingsDialog = new android.app.AlertDialog.Builder(this)
          .setTitle("Pengaturan gamepad")
          .setItems(new String[] {"Mapping Visual", "Log Diagnosis", "Ukuran Tombol · Resize", "Trigger · Tambah / Kelola"},
              (dialog, which) -> {
                switch (which) {
                  case 0:
                    if (visualMappingMarkerParams != null) saveVisualMappingPosition();
                    else showVisualMappingPicker();
                    break;
                  case 1: showGamepadDiagnostics(); break;
                  case 2: showControlSizeDialog(); break;
                  case 3:
                    android.app.AlertDialog triggerDialog = new android.app.AlertDialog.Builder(this)
                        .setTitle("Trigger")
                        .setItems(new String[] {"Tambah trigger baru", "Kelola trigger"}, (d, action) -> {
                          if (action == 0) showAddCustomTriggerDialog();
                          else showManageCustomTriggersDialog();
                        }).create();
                    showServiceOverlayDialog(triggerDialog);
                    break;
                }
              }).create();
      showServiceOverlayDialog(settingsDialog);
    });
    panel.addView(gamepadSettings, new LinearLayout.LayoutParams(-1, dp(36)));

    TextView coordinateTitle = new TextView(this);
    coordinateTitle.setText("KOORDINAT TARGET · LIVE");
    coordinateTitle.setTextColor(Color.rgb(174, 207, 245));
    coordinateTitle.setTextSize(9);
    coordinateTitle.setGravity(Gravity.CENTER);
    panel.addView(coordinateTitle, new LinearLayout.LayoutParams(-1, dp(20)));
    gamepadCoordinateIndicator = new TextView(this);
    gamepadCoordinateIndicator.setText("Sentuh tombol untuk melihat X/Y");
    gamepadCoordinateIndicator.setTextColor(Color.rgb(225, 241, 255));
    gamepadCoordinateIndicator.setTextSize(10);
    gamepadCoordinateIndicator.setGravity(Gravity.CENTER);
    gamepadCoordinateIndicator.setSingleLine(true);
    gamepadCoordinateIndicator.setPadding(dp(3), 0, dp(3), 0);
    panel.addView(gamepadCoordinateIndicator, new LinearLayout.LayoutParams(-1, dp(24)));

    TextView note = new TextView(this);
    note.setText("SENTUHAN MOBA · TAP / TAHAN / BIDIK");
    note.setTextColor(Color.rgb(174, 207, 245));
    note.setTextSize(9);
    note.setGravity(Gravity.CENTER);
    panel.addView(note, new LinearLayout.LayoutParams(-1, dp(22)));

    TextView aimTitle = new TextView(this);
    aimTitle.setText("ANALOG BIDIK SKILL");
    aimTitle.setTextColor(Color.rgb(174, 207, 245));
    aimTitle.setTextSize(9);
    aimTitle.setGravity(Gravity.CENTER);
    panel.addView(aimTitle, new LinearLayout.LayoutParams(-1, dp(20)));

    LinearLayout skillSticks = new LinearLayout(this);
    skillSticks.setGravity(Gravity.CENTER);
    addSkillAimStick(skillSticks, "S1", "skill1");
    addSkillAimStick(skillSticks, "S2", "skill2");
    addSkillAimStick(skillSticks, "S3", "skill3");
    int skillRowHeight = Math.max(gameButtonHeight("skill1", 76),
        Math.max(gameButtonHeight("skill2", 76), gameButtonHeight("skill3", 76)));
    panel.addView(skillSticks, new LinearLayout.LayoutParams(-1, dp(skillRowHeight)));

    LinearLayout row3 = new LinearLayout(this); row3.setGravity(Gravity.CENTER);
    addTouchGameButton(row3, "ULT", 1f, "ultimate", true);
    addTouchGameButton(row3, "SPELL", 1f, "spell", true);
    int ultimateRowHeight = Math.max(gameButtonHeight("ultimate", 54), gameButtonHeight("spell", 54));
    panel.addView(row3, new LinearLayout.LayoutParams(-1, dp(ultimateRowHeight)));

    LinearLayout row4 = new LinearLayout(this); row4.setGravity(Gravity.CENTER);
    addTouchGameButton(row4, "UPGRADE", 1f, "upgrade", false);
    addTouchGameButton(row4, "RECALL", 1f, "recall", false);
    int utilityRowHeight = Math.max(gameButtonHeight("upgrade", 40), gameButtonHeight("recall", 40));
    panel.addView(row4, new LinearLayout.LayoutParams(-1, dp(utilityRowHeight)));

    // Flexible space pins the primary attack control to the bottom-center.
    View spacer = new View(this);
    panel.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

    LinearLayout attackRow = new LinearLayout(this);
    attackRow.setGravity(Gravity.CENTER);
    addTouchGameButton(attackRow, "SERANG", 1f, "attack", false);
    addCustomTriggerButtons(attackRow);
    LinearLayout.LayoutParams attackLp = new LinearLayout.LayoutParams(-1, dp(gameButtonHeight("attack", 46)));
    attackLp.setMargins(dp(4), dp(2), dp(4), dp(4));
    panel.addView(attackRow, attackLp);

    TextView footnote = new TextView(this);
    footnote.setText("Angka X/Y mengikuti koordinat display target.");
    footnote.setTextColor(Color.rgb(174, 207, 245));
    footnote.setTextSize(8);
    footnote.setGravity(Gravity.CENTER);
    panel.addView(footnote, new LinearLayout.LayoutParams(-1, dp(18)));
  }

  private int getCustomTriggerCount() {
    return Math.max(0, Math.min(20, keyboardPrefs.getInt("custom_trigger_count", 0)));
  }

  private String getCustomTriggerLabel(int index) {
    String label = keyboardPrefs.getString("custom_trigger_label_" + index, "Trigger " + index);
    if (label == null || label.trim().isEmpty()) return "Trigger " + index;
    return label.trim();
  }

  private void addCustomTriggerButtons(LinearLayout row) {
    int count = Math.min(3, getCustomTriggerCount());
    for (int index = 1; index <= count; index++) {
      addTouchGameButton(row, getCustomTriggerLabel(index), 0.65f, "custom_" + index, false);
    }
  }

  private void showAddCustomTriggerDialog() {
    final int currentCount = getCustomTriggerCount();
    if (currentCount >= 20) {
      Toast.makeText(this, "Maksimal 20 trigger tambahan.", Toast.LENGTH_SHORT).show();
      return;
    }
    final android.widget.EditText nameInput = new android.widget.EditText(this);
    nameInput.setSingleLine(true);
    nameInput.setHint("Contoh: SHOP, CHAT, ITEM, MINIMAP");
    nameInput.setText("Trigger " + (currentCount + 1));
    nameInput.setSelectAllOnFocus(true);
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(4));
    TextView hint = new TextView(this);
    hint.setText("Buat tombol tambahan. Setelah dibuat, buka Mapping Visual lalu pilih tombol ini dan geser penandanya ke lokasi HUD yang sesuai, misalnya Shop.");
    hint.setTextSize(13);
    content.addView(hint);
    content.addView(nameInput, new LinearLayout.LayoutParams(-1, dp(52)));
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
        .setTitle("Tambah trigger baru")
        .setView(content)
        .setPositiveButton("Buat trigger", null)
        .setNegativeButton("Batal", null)
        .create();
    if (dialog.getWindow() != null && Build.VERSION.SDK_INT >= 26) {
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    }
    dialog.setOnShowListener(ignored -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(v -> {
          String label = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
          if (label.isEmpty()) {
            nameInput.setError("Masukkan nama tombol");
            return;
          }
          if (label.length() > 14) {
            nameInput.setError("Maksimal 14 karakter");
            return;
          }
          int next = getCustomTriggerCount() + 1;
          keyboardPrefs.edit()
              .putString("custom_trigger_label_" + next, label)
              .putInt("custom_trigger_count", next)
              .putFloat("game_x_custom_" + next, 0.5f)
              .putFloat("game_y_custom_" + next, 0.5f)
              .apply();
          recordGamepadDiagnostic("CUSTOM_TRIGGER_ADD", "role=custom_" + next + ", label=" + label);
          dialog.dismiss();
          Toast.makeText(this, "Trigger " + label + " dibuat. Panel diperbarui.", Toast.LENGTH_SHORT).show();
          rebuildKeyboard();
        }));
    dialog.show();
  }

  private void showManageCustomTriggersDialog() {
    final int count = getCustomTriggerCount();
    if (count == 0) {
      Toast.makeText(this, "Belum ada trigger tambahan untuk dikelola.", Toast.LENGTH_SHORT).show();
      return;
    }
    final String[] labels = new String[count];
    for (int i = 0; i < count; i++) labels[i] = getCustomTriggerLabel(i + 1);
    android.widget.Spinner spinner = new android.widget.Spinner(this);
    spinner.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    final android.widget.EditText name = new android.widget.EditText(this);
    name.setSingleLine(true);
    name.setHint("Nama trigger (maks. 14 karakter)");
    name.setText(labels[0]);
    name.setSelectAllOnFocus(true);
    spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
        name.setText(getCustomTriggerLabel(position + 1));
        name.setSelection(name.length());
      }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(4));
    TextView hint = new TextView(this);
    hint.setText("Pilih trigger yang sudah ada untuk mengganti nama atau menghapusnya. Perubahan berlaku untuk trigger lama maupun yang baru dibuat.");
    hint.setTextSize(12);
    content.addView(hint);
    content.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
    content.addView(name, new LinearLayout.LayoutParams(-1, dp(52)));
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
        .setTitle("Kelola trigger")
        .setView(content)
        .setPositiveButton("Simpan nama", null)
        .setNeutralButton("Hapus trigger", null)
        .setNegativeButton("Batal", null)
        .create();
    if (dialog.getWindow() != null && Build.VERSION.SDK_INT >= 26)
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    dialog.setOnShowListener(ignored -> {
      dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
        String label = name.getText() == null ? "" : name.getText().toString().trim();
        if (label.isEmpty()) { name.setError("Nama tidak boleh kosong"); return; }
        if (label.length() > 14) { name.setError("Maksimal 14 karakter"); return; }
        int index = spinner.getSelectedItemPosition() + 1;
        keyboardPrefs.edit().putString("custom_trigger_label_" + index, label).apply();
        recordGamepadDiagnostic("CUSTOM_TRIGGER_RENAME", "role=custom_" + index + ", label=" + label);
        dialog.dismiss();
        rebuildKeyboard();
      });
      dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
        final int selected = spinner.getSelectedItemPosition() + 1;
        android.app.AlertDialog confirmDialog = new android.app.AlertDialog.Builder(this)
            .setTitle("Hapus " + getCustomTriggerLabel(selected) + "?")
            .setMessage("Tombol ini dan posisi mapping-nya akan dihapus. Trigger lain akan dirapikan urutannya.")
            .setPositiveButton("Hapus", (confirm, which) -> {
              deleteCustomTrigger(selected);
              dialog.dismiss();
              rebuildKeyboard();
            })
            .setNegativeButton("Batal", null)
            .create();
        showServiceOverlayDialog(confirmDialog);
      });
    });
    dialog.show();
  }

  private void deleteCustomTrigger(int selected) {
    int count = getCustomTriggerCount();
    if (selected < 1 || selected > count) return;
    android.content.SharedPreferences.Editor edit = keyboardPrefs.edit();
    for (int i = selected; i < count; i++) {
      int next = i + 1;
      edit.putString("custom_trigger_label_" + i, getCustomTriggerLabel(next));
      edit.putFloat("game_x_custom_" + i, keyboardPrefs.getFloat("game_x_custom_" + next, 0.5f));
      edit.putFloat("game_y_custom_" + i, keyboardPrefs.getFloat("game_y_custom_" + next, 0.5f));
      copyIntPreference(edit, "game_btn_w_custom_" + i, "game_btn_w_custom_" + next, 0);
      copyIntPreference(edit, "game_btn_h_custom_" + i, "game_btn_h_custom_" + next, 0);
      copyIntPreference(edit, "game_btn_font_custom_" + i, "game_btn_font_custom_" + next, 9);
      copyIntPreference(edit, "game_btn_shape_custom_" + i, "game_btn_shape_custom_" + next, 0);
      edit.putFloat("game_ui_x_custom_" + i, keyboardPrefs.getFloat("game_ui_x_custom_" + next, 0f));
      edit.putFloat("game_ui_y_custom_" + i, keyboardPrefs.getFloat("game_ui_y_custom_" + next, 0f));
      String nextMap = keyboardPrefs.getString("game_map_custom_" + next, null);
      if (nextMap != null) edit.putString("game_map_custom_" + i, nextMap);
      else edit.remove("game_map_custom_" + i);
    }
    edit.remove("custom_trigger_label_" + count);
    edit.remove("game_x_custom_" + count);
    edit.remove("game_y_custom_" + count);
    edit.remove("game_map_custom_" + count);
    edit.remove("game_btn_w_custom_" + count);
    edit.remove("game_btn_h_custom_" + count);
    edit.remove("game_btn_font_custom_" + count);
    edit.remove("game_btn_shape_custom_" + count);
    edit.remove("game_ui_x_custom_" + count);
    edit.remove("game_ui_y_custom_" + count);
    edit.putInt("custom_trigger_count", count - 1).apply();
    recordGamepadDiagnostic("CUSTOM_TRIGGER_DELETE", "deleted index=" + selected + ", remaining=" + (count - 1));
  }

  private void copyIntPreference(android.content.SharedPreferences.Editor edit, String target, String source, int fallback) {
    edit.putInt(target, keyboardPrefs.getInt(source, fallback));
  }

  private void showControlSizeDialog() {
    final java.util.ArrayList<String> labelsList = new java.util.ArrayList<>(java.util.Arrays.asList(
        "Serang", "Skill 1", "Skill 2", "Skill 3", "Ultimate", "Spell / Flicker", "Upgrade", "Recall"));
    final java.util.ArrayList<String> rolesList = new java.util.ArrayList<>(java.util.Arrays.asList(
        "attack", "skill1", "skill2", "skill3", "ultimate", "spell", "upgrade", "recall"));
    for (int i = 1; i <= getCustomTriggerCount(); i++) {
      labelsList.add(getCustomTriggerLabel(i));
      rolesList.add("custom_" + i);
    }
    final String[] labels = labelsList.toArray(new String[0]);
    final String[] roles = rolesList.toArray(new String[0]);
    android.widget.Spinner selector = new android.widget.Spinner(this);
    selector.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
    final TextView widthValue = new TextView(this);
    final TextView heightValue = new TextView(this);
    final TextView fontValue = new TextView(this);
    android.widget.SeekBar width = new android.widget.SeekBar(this);
    width.setMax(220);
    android.widget.SeekBar height = new android.widget.SeekBar(this);
    height.setMax(92);
    android.widget.SeekBar font = new android.widget.SeekBar(this);
    font.setMax(10);
    android.widget.Spinner shape = new android.widget.Spinner(this);
    final String[] shapeNames = {"Sudut bulat", "Kotak", "Pil", "Lingkaran"};
    shape.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, shapeNames));
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(16), dp(8), dp(16), dp(4));
    TextView hint = new TextView(this);
    hint.setText("Pilih tombol, lalu atur lebar dan tinggi. Lebar 0 berarti otomatis mengikuti baris. Ukuran disimpan per tombol.");
    hint.setTextSize(12);
    content.addView(hint);
    content.addView(selector, new LinearLayout.LayoutParams(-1, dp(48)));
    content.addView(widthValue);
    content.addView(width);
    content.addView(heightValue);
    content.addView(height);
    content.addView(fontValue);
    content.addView(font);
    TextView shapeTitle = new TextView(this);
    shapeTitle.setText("Bentuk tombol");
    content.addView(shapeTitle);
    content.addView(shape, new LinearLayout.LayoutParams(-1, dp(44)));
    final Runnable refreshLabels = () -> {
      String role = roles[Math.max(0, selector.getSelectedItemPosition())];
      int w = keyboardPrefs.getInt("game_btn_w_" + role, 0);
      int h = keyboardPrefs.getInt("game_btn_h_" + role, 0);
      int f = keyboardPrefs.getInt("game_btn_font_" + role, 9);
      int sh = keyboardPrefs.getInt("game_btn_shape_" + role, 0);
      width.setProgress(Math.max(0, Math.min(220, w)));
      height.setProgress(h <= 0 ? 42 : Math.max(28, Math.min(120, h)) - 28);
      font.setProgress(Math.max(0, Math.min(10, f - 8)));
      shape.setSelection(Math.max(0, Math.min(shapeNames.length - 1, sh)));
      widthValue.setText("Lebar: " + (w == 0 ? "Otomatis" : w + " dp"));
      heightValue.setText("Tinggi: " + (h <= 0 ? "Default" : h + " dp"));
      fontValue.setText("Ukuran teks: " + f + " sp");
    };
    selector.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) { refreshLabels.run(); }
      @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    });
    width.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
        widthValue.setText("Lebar: " + (value == 0 ? "Otomatis" : value + " dp"));
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
    });
    height.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
        heightValue.setText("Tinggi: " + (value + 28) + " dp");
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
    });
    font.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(android.widget.SeekBar bar, int value, boolean fromUser) {
        fontValue.setText("Ukuran teks: " + (value + 8) + " sp");
      }
      @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}
      @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {}
    });
    android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
        .setTitle("Ukuran, bentuk & teks tombol")
        .setView(content)
        .setPositiveButton("Simpan ukuran", (d, which) -> {
          String role = roles[Math.max(0, selector.getSelectedItemPosition())];
          int w = width.getProgress();
          int h = height.getProgress() + 28;
          int f = font.getProgress() + 8;
          int sh = shape.getSelectedItemPosition();
          keyboardPrefs.edit().putInt("game_btn_w_" + role, w)
              .putInt("game_btn_h_" + role, h)
              .putInt("game_btn_font_" + role, f)
              .putInt("game_btn_shape_" + role, sh).apply();
          recordGamepadDiagnostic("BUTTON_RESIZE", "role=" + role + ", widthDp=" + w
              + ", heightDp=" + h + ", fontSp=" + f + ", shape=" + sh);
          rebuildKeyboard();
        })
        .setNeutralButton("Reset ukuran", (d, which) -> {
          String role = roles[Math.max(0, selector.getSelectedItemPosition())];
          keyboardPrefs.edit().remove("game_btn_w_" + role).remove("game_btn_h_" + role)
              .remove("game_btn_font_" + role).remove("game_btn_shape_" + role).apply();
          rebuildKeyboard();
        })
        .setNegativeButton("Batal", null)
        .create();
    if (dialog.getWindow() != null && Build.VERSION.SDK_INT >= 26)
      dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
    dialog.show();
    refreshLabels.run();
  }

  private void addSkillAimStick(LinearLayout row, String label, String role) {
    SkillAimStickView stick = new SkillAimStickView(label, role);
    int customWidth = keyboardPrefs.getInt("game_btn_w_" + role, 0);
    int customHeight = keyboardPrefs.getInt("game_btn_h_" + role, 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
        customWidth > 0 ? dp(customWidth) : 0,
        dp(customHeight > 0 ? customHeight : 76),
        customWidth > 0 ? 0f : 1f);
    lp.setMargins(dp(3), dp(2), dp(3), dp(2));
    row.addView(stick, lp);
  }

  private final class SkillAimStickView extends View {
    private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final String label;
    private final String role;
    private float cx, cy, radius, knobX, knobY;
    private float lastFingerX, lastFingerY;
    private boolean active;
    private float[] currentTarget = new float[2];

    SkillAimStickView(String label, String role) {
      super(FloatingKeyboardService.this);
      this.label = label;
      this.role = role;
      setFocusable(true);
      setContentDescription(label + " — area geser untuk membidik");
    }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
      super.onDraw(canvas);
      cx = getWidth() / 2f;
      cy = getHeight() / 2f;
      radius = Math.max(dp(18), Math.min(getWidth(), getHeight()) * 0.39f);
      if (!active) { knobX = cx; knobY = cy; }
      float left = dp(3), top = dp(3), right = getWidth() - dp(3), bottom = getHeight() - dp(3);
      float corner = dp(12);
      paint.setStyle(android.graphics.Paint.Style.FILL);
      paint.setColor(Color.rgb(24, 38, 57));
      canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(1.5f * getResources().getDisplayMetrics().density);
      paint.setColor(active ? Color.rgb(105, 216, 245) : Color.rgb(77, 143, 183));
      canvas.drawRoundRect(left, top, right, bottom, corner, corner, paint);
      // A crosshair pad communicates swipe-to-aim more clearly than a tiny joystick.
      paint.setStrokeWidth(dp(1));
      paint.setColor(Color.rgb(77, 111, 139));
      canvas.drawLine(cx - dp(13), cy, cx + dp(13), cy, paint);
      canvas.drawLine(cx, cy - dp(13), cx, cy + dp(13), paint);
      paint.setStyle(android.graphics.Paint.Style.FILL);
      paint.setColor(active ? Color.rgb(105, 216, 245) : Color.rgb(57, 91, 119));
      canvas.drawCircle(knobX, knobY, dp(5), paint);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1));
      paint.setColor(Color.rgb(167, 202, 255));
      canvas.drawCircle(knobX, knobY, dp(5), paint);
      paint.setStyle(android.graphics.Paint.Style.FILL);
      paint.setTextAlign(android.graphics.Paint.Align.CENTER);
      paint.setTextSize(dp(10));
      paint.setColor(Color.rgb(235, 243, 255));
      canvas.drawText(label, cx, getHeight() - dp(5), paint);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
      cx = getWidth() / 2f;
      cy = getHeight() / 2f;
      radius = Math.max(dp(18), Math.min(getWidth(), getHeight()) * 0.39f);
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          active = true;
          lastFingerX = event.getX();
          lastFingerY = event.getY();
          knobX = lastFingerX;
          knobY = lastFingerY;
          currentTarget = gameTouchController.pointFor(role);
          boolean accepted = gameTouchController.press(role, currentTarget);
          recordGamepadDiagnostic("SKILL_STICK_DOWN", "role=" + role + ", accepted=" + accepted);
          invalidate();
          return true;
        case MotionEvent.ACTION_MOVE:
          if (!active) return true;
          float fingerX = event.getX();
          float fingerY = event.getY();
          float deltaX = fingerX - lastFingerX;
          float deltaY = fingerY - lastFingerY;
          lastFingerX = fingerX;
          lastFingerY = fingerY;
          // Relative drag: each finger movement nudges the injected touch point.
          // Unlike a normalized mini-stick, this does not hit maximum travel after a
          // short swipe and does not snap back toward a fixed origin.
          float sensitivity = 1.65f;
          currentTarget[0] += deltaX * sensitivity;
          currentTarget[1] += deltaY * sensitivity;
          android.util.DisplayMetrics targetMetrics = gameTouchController.metrics();
          currentTarget[0] = Math.max(0f, Math.min(targetMetrics.widthPixels - 1f, currentTarget[0]));
          currentTarget[1] = Math.max(0f, Math.min(targetMetrics.heightPixels - 1f, currentTarget[1]));
          float padLimitX = Math.max(dp(8), getWidth() / 2f - dp(8));
          float padLimitY = Math.max(dp(8), getHeight() / 2f - dp(8));
          knobX = Math.max(dp(8), Math.min(getWidth() - dp(8), fingerX));
          knobY = Math.max(dp(8), Math.min(getHeight() - dp(8), fingerY));
          boolean moved = gameTouchController.moveSmooth(role, currentTarget[0], currentTarget[1]);
          recordGamepadDiagnostic("AIM_PAD_MOVE", "role=" + role + ", fingerDelta=("
              + Math.round(deltaX) + "," + Math.round(deltaY) + "), target=("
              + Math.round(currentTarget[0]) + "," + Math.round(currentTarget[1])
              + "), accepted=" + moved);
          invalidate();
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL:
          active = false;
          boolean released = gameTouchController.release(role);
          recordGamepadDiagnostic("SKILL_STICK_UP", "role=" + role + ", accepted=" + released);
          invalidate();
          return true;
      }
      return true;
    }
  }

  private void showAutoMapMlbbDialog() {
    try {
      android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
          .setTitle("Auto map HUD MLBB")
          .setMessage("Terapkan preset posisi umum Mobile Legends untuk layar landscape? Posisi HUD tiap akun/perangkat bisa berbeda, jadi periksa dan sesuaikan lewat Atur Posisi / Mapping.")
          .setPositiveButton("Terapkan", (d, w) -> {
            try {
              applyAutoMapMlbb();
            } catch (Throwable t) {
              recordGamepadDiagnostic("AUTO_MAP_ERROR", t.getClass().getSimpleName() + ": " + t.getMessage());
              State.log("auto map MLBB failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
              Toast.makeText(FloatingKeyboardService.this,
                  "Auto mapping gagal. Buka Log Diagnosis untuk detail.", Toast.LENGTH_LONG).show();
            }
          })
          .setNegativeButton("Batal", null)
          .create();
      if (dialog.getWindow() != null) {
        dialog.getWindow().setType(Build.VERSION.SDK_INT >= 26
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE);
      }
      dialog.show();
    } catch (Throwable t) {
      recordGamepadDiagnostic("AUTO_MAP_DIALOG_ERROR", t.getClass().getSimpleName() + ": " + t.getMessage());
      State.log("auto map dialog failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
      Toast.makeText(this, "Dialog Auto Mapping gagal dibuka. Cek Log Diagnosis.", Toast.LENGTH_LONG).show();
    }
  }

  private void applyAutoMapMlbb() {
    // Practical MLBB HUD preset for landscape screens. Values are normalized to
    // the target display, so they scale to different virtual-display sizes.
    keyboardPrefs.edit()
      .putFloat("game_x_attack", 0.84f).putFloat("game_y_attack", 0.83f)
      .putFloat("game_x_skill1", 0.91f).putFloat("game_y_skill1", 0.88f)
      .putFloat("game_x_skill2", 0.86f).putFloat("game_y_skill2", 0.78f)
      .putFloat("game_x_skill3", 0.80f).putFloat("game_y_skill3", 0.74f)
      .putFloat("game_x_ultimate", 0.94f).putFloat("game_y_ultimate", 0.76f)
      .putFloat("game_x_spell", 0.73f).putFloat("game_y_spell", 0.90f)
      .putFloat("game_x_upgrade", 0.72f).putFloat("game_y_upgrade", 0.80f)
      .putFloat("game_x_recall", 0.62f).putFloat("game_y_recall", 0.92f)
      .apply();
    recordGamepadDiagnostic("AUTO_MAP", "Applied normalized MLBB landscape HUD preset");
    Toast.makeText(this, "Auto mapping MLBB diterapkan. Sesuaikan jika HUD game berbeda.", Toast.LENGTH_LONG).show();
  }

  private void addTouchGameButton(LinearLayout row, String label, float weight,
      String role, boolean aimable) {
    final String mappedRole = keyboardPrefs.getString("game_map_" + role, role);
    Button b = new Button(this);
    int customFont = keyboardPrefs.getInt("game_btn_font_" + role, aimable ? 11 : 9);
    b.setText(label); b.setAllCaps(false); b.setTextSize(Math.max(8, Math.min(18, customFont)));
    b.setMinWidth(0); b.setMinimumWidth(0);
    b.setPadding(dp(2), 0, dp(2), 0);
    int customWidth = keyboardPrefs.getInt("game_btn_w_" + role, 0);
    int customHeight = keyboardPrefs.getInt("game_btn_h_" + role, 0);
    if (aimable) {
      android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
      int shapeMode = keyboardPrefs.getInt("game_btn_shape_" + role, 3);
      if (shapeMode == 3) {
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      } else {
        circle.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        circle.setCornerRadius(dp(shapeMode == 1 ? 3 : (shapeMode == 2 ? 40 : 12)));
      }
      circle.setColor(Color.rgb(42, 59, 78));
      circle.setStroke(dp(1), Color.rgb(79, 126, 160));
      b.setBackground(circle);
      b.setTextColor(Color.rgb(235, 243, 255));
      b.setElevation(dp(2));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          dp(customWidth > 0 ? customWidth : 54), dp(customHeight > 0 ? customHeight : 54));
      lp.setMargins(dp(4), dp(2), dp(4), dp(2));
      row.addView(b, lp);
    } else {
      styleButton(b, "SERANG".equals(label) || "ULT".equals(label));
      int shapeMode = keyboardPrefs.getInt("game_btn_shape_" + role, 0);
      android.graphics.drawable.GradientDrawable triggerShape = new android.graphics.drawable.GradientDrawable();
      triggerShape.setColor(("SERANG".equals(label) || "ULT".equals(label))
          ? Color.rgb(111, 211, 244) : Color.rgb(43, 61, 78));
      triggerShape.setStroke(dp(1), Color.rgb(79, 126, 160));
      if (shapeMode == 3) {
        triggerShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
      } else {
        triggerShape.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        triggerShape.setCornerRadius(dp(shapeMode == 1 ? 3 : (shapeMode == 2 ? 40 : 12)));
      }
      b.setBackgroundTintList(null);
      b.setBackground(triggerShape);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
          customWidth > 0 ? dp(customWidth) : 0,
          customHeight > 0 ? dp(customHeight) : -1,
          customWidth > 0 ? 0f : weight);
      lp.setMargins(dp(3), dp(3), dp(3), dp(3));
      row.addView(b, lp);
    }
    final float[] start = new float[2];
    final float[] aimOrigin = new float[2];
    final float[] viewOrigin = new float[2];
    final boolean[] repositioning = new boolean[1];
    final boolean[] movedForReposition = new boolean[1];
    final Handler dragHandler = new Handler(Looper.getMainLooper());
    final Runnable beginReposition = () -> {
      repositioning[0] = true;
      movedForReposition[0] = false;
      viewOrigin[0] = b.getTranslationX();
      viewOrigin[1] = b.getTranslationY();
      gameTouchController.release(mappedRole);
      b.setPressed(false);
      b.setAlpha(0.78f);
      recordGamepadDiagnostic("BUTTON_REPOSITION_START", "role=" + role + ", label=" + label);
      Toast.makeText(FloatingKeyboardService.this, "Seret " + label + " ke posisi baru.", Toast.LENGTH_SHORT).show();
    };
    b.setOnTouchListener((v, e) -> {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          // Use screen-space finger coordinates. Local button coordinates can
          // saturate almost immediately when the finger leaves the small skill button.
          start[0] = e.getRawX();
          start[1] = e.getRawY();
          repositioning[0] = false;
          movedForReposition[0] = false;
          float[] down = gameTouchController.pointFor(mappedRole);
          aimOrigin[0] = down[0];
          aimOrigin[1] = down[1];
          updateGamepadCoordinateIndicator(mappedRole, down[0], down[1]);
          boolean accepted = gameTouchController.press(mappedRole, down);
          recordGamepadDiagnostic("BUTTON_DOWN", "label=" + label + ", role=" + mappedRole
              + ", accepted=" + accepted + ", target=(" + Math.round(down[0]) + ","
              + Math.round(down[1]) + "), display=" + targetDisplayId);
          dragHandler.postDelayed(beginReposition, 550L);
          v.setPressed(true);
          return true;
        case MotionEvent.ACTION_MOVE:
          if (repositioning[0]) {
            float dx = e.getRawX() - start[0];
            float dy = e.getRawY() - start[1];
            if (Math.abs(dx) + Math.abs(dy) > dp(3)) movedForReposition[0] = true;
            if (movedForReposition[0]) {
              b.setTranslationX(viewOrigin[0] + dx);
              b.setTranslationY(viewOrigin[1] + dy);
            }
            return true;
          }
          if (Math.abs(e.getRawX() - start[0]) + Math.abs(e.getRawY() - start[1]) > dp(12)) {
            dragHandler.removeCallbacks(beginReposition);
          }
          if (aimable && gameTouchController.isActive(mappedRole)) {
            android.util.DisplayMetrics dm = gameTouchController.metrics();
            // Convert the real finger drag into normalized stick-like movement.
            // A larger gesture radius gives the player fine control instead of
            // reaching maximum aim travel after only a few pixels.
            float dragX = e.getRawX() - start[0];
            float dragY = e.getRawY() - start[1];
            float gestureRadius = Math.max(dp(80), Math.min(dm.widthPixels, dm.heightPixels) * 0.22f);
            float nx = dragX / gestureRadius;
            float ny = dragY / gestureRadius;
            float magnitude = (float) Math.hypot(nx, ny);
            if (magnitude > 1f) { nx /= magnitude; ny /= magnitude; }
            float aimRadius = Math.max(30f, Math.min(dm.widthPixels, dm.heightPixels) * 0.18f);
            float targetX = Math.max(0f, Math.min(dm.widthPixels - 1f, aimOrigin[0] + nx * aimRadius));
            float targetY = Math.max(0f, Math.min(dm.heightPixels - 1f, aimOrigin[1] + ny * aimRadius));
            recordGamepadDiagnostic("AIM_TOUCH", "role=" + mappedRole + ", fingerDelta=("
                + Math.round(dragX) + "," + Math.round(dragY) + "), normalized=("
                + String.format(java.util.Locale.ROOT, "%.2f", nx) + ","
                + String.format(java.util.Locale.ROOT, "%.2f", ny) + ")");
            updateGamepadCoordinateIndicator(mappedRole, targetX, targetY);
            gameTouchController.moveSmooth(mappedRole, targetX, targetY);
          }
          return true;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL: {
          dragHandler.removeCallbacks(beginReposition);
          if (repositioning[0]) {
            b.setAlpha(1f);
            if (movedForReposition[0]) {
              float density = Math.max(1f, getResources().getDisplayMetrics().density);
              keyboardPrefs.edit()
                  .putFloat("game_ui_x_" + role, b.getTranslationX() / density)
                  .putFloat("game_ui_y_" + role, b.getTranslationY() / density)
                  .apply();
              recordGamepadDiagnostic("BUTTON_REPOSITION_SAVED", "role=" + role
                  + ", tx=" + Math.round(b.getTranslationX()) + ", ty=" + Math.round(b.getTranslationY()));
              Toast.makeText(FloatingKeyboardService.this, "Posisi " + label + " tersimpan.", Toast.LENGTH_SHORT).show();
            }
            repositioning[0] = false;
          }
          boolean releaseAccepted = gameTouchController.release(mappedRole);
          recordGamepadDiagnostic("BUTTON_UP", "label=" + label + ", role=" + mappedRole
              + ", accepted=" + releaseAccepted);
          v.setPressed(false);
          return true;
        }
      }
      return true;
    });
    float density = Math.max(1f, getResources().getDisplayMetrics().density);
    b.setTranslationX(dp(Math.round(keyboardPrefs.getFloat("game_ui_x_" + role, 0f))));
    b.setTranslationY(dp(Math.round(keyboardPrefs.getFloat("game_ui_y_" + role, 0f))));
  }

  private final class GameTouchController {
    private final java.util.LinkedHashMap<String, GameContact> contacts = new java.util.LinkedHashMap<>();
    private long downTime = 0L;

    private final class GameContact {
      final int id;
      float x;
      float y;
      GameContact(int id, float x, float y) { this.id = id; this.x = x; this.y = y; }
    }

    synchronized boolean isActive(String role) { return contacts.containsKey(role); }

    android.util.DisplayMetrics metrics() {
      android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
      try {
        android.hardware.display.DisplayManager displays =
            (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display display = displays != null ? displays.getDisplay(targetDisplayId) : null;
        if (display != null) {
          display.getRealMetrics(dm);
          return dm;
        }
      } catch (Throwable e) {
        State.log("gamepad target display metrics failed: " + e.getMessage());
      }
      dm.widthPixels = Math.max(1, getResources().getDisplayMetrics().widthPixels);
      dm.heightPixels = Math.max(1, getResources().getDisplayMetrics().heightPixels);
      dm.density = getResources().getDisplayMetrics().density;
      return dm;
    }

    float[] pointFor(String role) {
      android.util.DisplayMetrics dm = metrics();
      float x = keyboardPrefs.getFloat("game_x_" + role, defaultGameX(role));
      float y = keyboardPrefs.getFloat("game_y_" + role, defaultGameY(role));
      return new float[] {
        Math.max(0f, Math.min(dm.widthPixels - 1f, dm.widthPixels * x)),
        Math.max(0f, Math.min(dm.heightPixels - 1f, dm.heightPixels * y))
      };
    }

    float getMoveRadius() {
      android.util.DisplayMetrics dm = metrics();
      // A little more travel makes turning easier without losing the
      // game's virtual joystick center; normalized to the target display.
      return Math.max(30f, Math.min(dm.widthPixels, dm.heightPixels) * 0.14f);
    }

    private int pointerIdFor(String role) {
      if (role != null && role.startsWith("custom_")) {
        try {
          int customIndex = Integer.parseInt(role.substring("custom_".length()));
          // Keep every user-created trigger on a distinct Android pointer ID.
          return Math.max(9, Math.min(28, 8 + customIndex));
        } catch (NumberFormatException ignored) {}
      }
      switch (role) {
        case "move": return 0;
        case "attack": return 1;
        case "skill1": return 2;
        case "skill2": return 3;
        case "skill3": return 4;
        case "ultimate": return 5;
        case "spell": return 6;
        case "recall": return 7;
        case "upgrade": return 8;
        default: return 29;
      }
    }

    synchronized boolean press(String role, float[] point) {
      if (point == null || contacts.containsKey(role)) return false;
      long now = android.os.SystemClock.uptimeMillis();
      if (contacts.isEmpty()) downTime = now;
      int action;
      if (contacts.isEmpty()) {
        action = MotionEvent.ACTION_DOWN;
      } else {
        action = MotionEvent.ACTION_POINTER_DOWN | (contacts.size() << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
      }
      contacts.put(role, new GameContact(pointerIdFor(role), point[0], point[1]));
      boolean accepted = injectContacts(action);
      recordGamepadDiagnostic("CONTACT_DOWN", "role=" + role + ", pointerId="
          + pointerIdFor(role) + ", accepted=" + accepted + ", x=" + Math.round(point[0])
          + ", y=" + Math.round(point[1]) + ", pointerCount=" + contacts.size());
      if (!accepted) {
        contacts.remove(role);
        if (contacts.isEmpty()) downTime = 0L;
      }
      return accepted;
    }

    synchronized boolean move(String role, float x, float y) {
      GameContact contact = contacts.get(role);
      if (contact == null) return false;
      android.util.DisplayMetrics dm = metrics();
      contact.x = Math.max(0f, Math.min(dm.widthPixels - 1f, x));
      contact.y = Math.max(0f, Math.min(dm.heightPixels - 1f, y));
      return injectContacts(MotionEvent.ACTION_MOVE);
    }

    synchronized boolean moveSmooth(String role, float targetX, float targetY) {
      GameContact contact = contacts.get(role);
      if (contact == null) {
        State.log("gamepad aim rejected: role=" + role + " is not active");
        return false;
      }
      // Do not burst several synthetic MOVE events in one UI frame. The old
      // loop emitted up to six events with effectively identical timestamps,
      // which can make drag-to-aim feel notchy on slower devices. Send one
      // accurately clamped point per real finger movement instead.
      float dx = targetX - contact.x;
      float dy = targetY - contact.y;
      if (Math.hypot(dx, dy) < 0.75f) return true;
      long now = android.os.SystemClock.uptimeMillis();
      long interval = lastGamepadMoveLogAt == 0L ? 0L : now - lastGamepadMoveLogAt;
      lastGamepadMoveLogAt = now;
      long started = android.os.SystemClock.elapsedRealtimeNanos();
      boolean accepted = move(role, targetX, targetY);
      long costMs = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1000000L;
      recordGamepadDiagnostic("AIM_MOVE", "role=" + role + ", dx=" + Math.round(dx)
          + ", dy=" + Math.round(dy) + ", intervalMs=" + interval + ", injectCostMs=" + costMs
          + ", accepted=" + accepted + ", target=(" + Math.round(contact.x) + ","
          + Math.round(contact.y) + "), activePointers=" + contacts.size());
      if (!accepted) State.log("gamepad aim injection rejected: role=" + role
          + ", dx=" + Math.round(dx) + ", dy=" + Math.round(dy));
      return accepted;
    }

    synchronized boolean release(String role) {
      if (!contacts.containsKey(role)) return false;
      int index = 0;
      for (String key : contacts.keySet()) {
        if (key.equals(role)) break;
        index++;
      }
      int action = contacts.size() == 1 ? MotionEvent.ACTION_UP
          : MotionEvent.ACTION_POINTER_UP | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
      boolean accepted = injectContacts(action);
      recordGamepadDiagnostic("CONTACT_UP", "role=" + role + ", accepted=" + accepted
          + ", pointerCountBeforeRemove=" + contacts.size());
      contacts.remove(role);
      if (contacts.isEmpty()) downTime = 0L;
      return accepted;
    }

    synchronized void releaseAll() {
      for (String role : new java.util.ArrayList<>(contacts.keySet())) release(role);
    }

    private boolean injectContacts(int action) {
      IInputManager manager = getActiveInputManager();
      if (manager == null) {
        State.log("gamepad touch unavailable: InputManager unavailable; grant Shizuku input permission");
        return false;
      }
      int count = contacts.size();
      if (count == 0) return false;
      MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
      MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
      int index = 0;
      for (GameContact contact : contacts.values()) {
        properties[index] = new MotionEvent.PointerProperties();
        properties[index].id = contact.id;
        properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
        coords[index] = new MotionEvent.PointerCoords();
        coords[index].x = contact.x;
        coords[index].y = contact.y;
        coords[index].pressure = 1f;
        coords[index].size = 1f;
        index++;
      }
      long now = android.os.SystemClock.uptimeMillis();
      MotionEvent event = MotionEvent.obtain(
          downTime == 0L ? now : downTime, now, action, count,
          properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
      try {
        MotionEventHidden hidden = Refine.unsafeCast(event);
        hidden.setDisplayId(targetDisplayId);
        long injectStart = android.os.SystemClock.elapsedRealtimeNanos();
        boolean accepted = manager.injectInputEvent(event, 0);
        long injectCostMs = (android.os.SystemClock.elapsedRealtimeNanos() - injectStart) / 1000000L;
        recordGamepadDiagnostic("INJECT", "display=" + targetDisplayId + ", action=" + action
            + ", pointers=" + count + ", injectCostMs=" + injectCostMs + ", accepted=" + accepted);
        if (!accepted) State.log("gamepad touch injection rejected: display=" + targetDisplayId
            + ", action=" + action + ", pointers=" + count);
        return accepted;
      } catch (Throwable e) {
        recordGamepadDiagnostic("INJECT_ERROR", "display=" + targetDisplayId + ", action="
            + action + ", error=" + e.getClass().getSimpleName() + ": " + e.getMessage());
        State.log("gamepad touch injection failed: " + e.getMessage());
        return false;
      } finally {
        event.recycle();
      }
    }
  }

  private void addLandscapeSettingsPanel(LinearLayout panel) {
    TextView hint = new TextView(this);
    hint.setText("Sesuaikan panel agar nyaman untuk layar virtual 900×900.");
    hint.setTextColor(Color.rgb(191, 213, 239));
    hint.setTextSize(11);
    hint.setPadding(dp(4), dp(4), dp(4), dp(8));
    panel.addView(hint);
    addSettingSlider(panel, "Lebar keyboard", widthPercent, 35, 100, value -> {
      widthPercent = value;
      keyboardPrefs.edit().putInt("width_percent", value).apply();
      updateOverlayWidth();
    }, "%");
    addSettingSlider(panel, "Tinggi tombol keyboard", keyHeightDp, 28, 96, value -> {
      keyHeightDp = value;
      keyboardPrefs.edit().putInt("key_height_dp", value).apply();
      rebuildKeyboard();
    }, " dp");
    Button apply = new Button(this);
    apply.setText("Selesai");
    apply.setAllCaps(false);
    styleButton(apply, true);
    apply.setOnClickListener(v -> { settingsVisible = false; rebuildKeyboard(); });
    panel.addView(apply, new LinearLayout.LayoutParams(-1, dp(38)));
  }

  private void addLandscapeLaptopKeyboard(LinearLayout panel) {
    int oldHeight = keyHeightDp;
    keyHeightDp = Math.max(36, Math.min(96, oldHeight));

    // Compact, familiar Indonesian QWERTY layout; avoid fake suggestion chips.
    if (symbolsMode) {
      addLandscapeKeyRow(panel, new String[] {"1","2","3","4","5","6","7","8","9","0"});
      addLandscapeKeyRow(panel, new String[] {"@","#","$","%","&","-","+","(",")","⌫"});
      addLandscapeKeyRow(panel, new String[] {"*","\"","'",":",";","!","?",".",",","ABC"});
    } else {
      addLandscapeKeyRow(panel, new String[] {"q","w","e","r","t","y","u","i","o","p"});
      addLandscapeKeyRow(panel, new String[] {"a","s","d","f","g","h","j","k","l"});
      addLandscapeKeyRow(panel, new String[] {"Shift","z","x","c","v","b","n","m","⌫"});
    }

    LinearLayout bottom = new LinearLayout(this);
    bottom.setGravity(Gravity.CENTER);
    addLaptopKey(bottom, "?123", 1.15f, () -> { symbolsMode = !symbolsMode; rebuildKeyboard(); });
    addLaptopKey(bottom, ",", 0.7f, () -> sendText(","));
    addLaptopKey(bottom, "Space", 3.4f, () -> sendText(" "));
    addLaptopKey(bottom, ".", 0.7f, () -> sendText("."));
    addLaptopKey(bottom, "Enter", 1.3f, () -> sendKey(KeyEvent.KEYCODE_ENTER, false));
    panel.addView(bottom);
    keyHeightDp = oldHeight;
  }

  private void addLandscapeKeyRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    for (String key : keys) {
      float weight = 1f;
      if ("Tab".equals(key) || "Caps".equals(key)) weight = 1.35f;
      if ("Shift".equals(key)) weight = 1.6f;
      if ("Enter".equals(key) || "⌫".equals(key)) weight = 1.45f;
      addLaptopKey(row, key, weight, () -> {
        switch (key) {
          case "⌫": sendKey(KeyEvent.KEYCODE_DEL, false); break;
          case "Tab": sendKey(KeyEvent.KEYCODE_TAB, false); break;
          case "Enter": sendKey(KeyEvent.KEYCODE_ENTER, false); break;
          case "Shift": shiftEnabled = !shiftEnabled; break;
          case "Caps": capsEnabled = !capsEnabled; break;
          case "ABC": symbolsMode = false; rebuildKeyboard(); break;
          default:
            String output = key;
            if (key.length() == 1 && Character.isLetter(key.charAt(0)) && (shiftEnabled || capsEnabled)) {
              output = key.toUpperCase(java.util.Locale.ROOT);
            } else if (shiftEnabled) {
              switch (key) {
                case "1": output = "!"; break; case "2": output = "@"; break;
                case "3": output = "#"; break; case "4": output = "$"; break;
                case "5": output = "%"; break; case "6": output = "^"; break;
                case "7": output = "&"; break; case "8": output = "*"; break;
                case "9": output = "("; break; case "0": output = ")"; break;
                case ",": output = "<"; break; case ".": output = ">"; break;
                case "/": output = "?"; break; case "'": output = "\""; break;
              }
            }
            sendText(output);
            if (shiftEnabled) shiftEnabled = false;
            break;
        }
      });
    }
    panel.addView(row);
  }

  private void addLaptopKey(LinearLayout row, String label, float weight, Runnable action) {
    Button button = new Button(this);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(label.length() > 4 ? 9 : 11);
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(dp(1), 0, dp(1), 0);
    styleButton(button, "Enter".equals(label) || "?123".equals(label));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(keyHeightDp), weight);
    lp.setMargins(dp(1), dp(1), dp(1), dp(1));
    row.addView(button, lp);
    button.setOnClickListener(v -> action.run());
  }

  private void toggleModifier(String key) {
    if ("Ctrl".equals(key)) {
      ctrlEnabled = !ctrlEnabled;
      Toast.makeText(this, ctrlEnabled ? "Ctrl aktif" : "Ctrl nonaktif", Toast.LENGTH_SHORT).show();
    } else {
      altEnabled = !altEnabled;
      Toast.makeText(this, altEnabled ? "Alt aktif" : "Alt nonaktif", Toast.LENGTH_SHORT).show();
    }
  }

  /**
   * Landscape-only drag grip. Dragging horizontally resizes the keyboard panel;
   * widthPercent is 20..100% of the left half of the phone, leaving the center
   * virtual display and the right trackpad area available.
   */
  private void addLandscapeResizeGrip(LinearLayout panel) {
    LinearLayout gripRow = new LinearLayout(this);
    gripRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
    TextView grip = new TextView(this);
    grip.setText("↔ Lebar");
    grip.setTextColor(Color.rgb(167, 202, 255));
    grip.setTextSize(10);
    grip.setGravity(Gravity.CENTER);
    grip.setPadding(dp(8), dp(2), dp(8), dp(2));
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setColor(Color.rgb(31, 47, 69));
    bg.setCornerRadius(dp(10));
    grip.setBackground(bg);
    gripRow.addView(grip, new LinearLayout.LayoutParams(-2, dp(28)));
    gripRow.setPadding(0, dp(2), dp(2), 0);
    grip.setOnTouchListener((v, event) -> {
      if (params == null) return false;
      float density = getResources().getDisplayMetrics().density;
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragStartX = event.getRawX();
          dragOriginX = params.width;
          return true;
        case MotionEvent.ACTION_MOVE:
          int maxWidth = Math.max(dp(180), getResources().getDisplayMetrics().widthPixels / 2);
          int minWidth = dp(180);
          int nextWidth = Math.max(minWidth, Math.min(maxWidth,
              dragOriginX + Math.round(event.getRawX() - dragStartX)));
          params.width = nextWidth;
          widthPercent = Math.max(20, Math.min(100,
              Math.round(nextWidth * 100f / Math.max(1f, getResources().getDisplayMetrics().widthPixels * 0.5f))));
          try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
          return true;
        case MotionEvent.ACTION_UP:
          keyboardPrefs.edit().putInt("width_percent", widthPercent).apply();
          return true;
      }
      return true;
    });
    panel.addView(gripRow, new LinearLayout.LayoutParams(-1, dp(30)));

    // Second grip adjusts the keyboard's vertical size by changing key height.
    TextView heightGrip = new TextView(this);
    heightGrip.setText("↕  Tarik untuk ubah panjang keyboard");
    heightGrip.setTextColor(Color.rgb(167, 202, 255));
    heightGrip.setTextSize(10);
    heightGrip.setGravity(Gravity.CENTER);
    heightGrip.setPadding(dp(6), dp(2), dp(6), dp(2));
    android.graphics.drawable.GradientDrawable heightBg = new android.graphics.drawable.GradientDrawable();
    heightBg.setColor(Color.rgb(31, 47, 69));
    heightBg.setCornerRadius(dp(9));
    heightGrip.setBackground(heightBg);
    heightGrip.setOnTouchListener((v, event) -> {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragStartY = event.getRawY();
          dragOriginY = keyHeightDp;
          return true;
        case MotionEvent.ACTION_MOVE:
          int deltaDp = Math.round((event.getRawY() - dragStartY) /
              Math.max(0.5f, getResources().getDisplayMetrics().density));
          int next = Math.max(28, Math.min(96, Math.round(dragOriginY + deltaDp)));
          heightGrip.setText("↕  Tinggi tombol: " + next + " dp");
          return true;
        case MotionEvent.ACTION_UP:
          int finalDelta = Math.round((event.getRawY() - dragStartY) /
              Math.max(0.5f, getResources().getDisplayMetrics().density));
          keyHeightDp = Math.max(28, Math.min(96, Math.round(dragOriginY + finalDelta)));
          keyboardPrefs.edit().putInt("key_height_dp", keyHeightDp).apply();
          rebuildKeyboard();
          return true;
      }
      return true;
    });
    panel.addView(heightGrip, new LinearLayout.LayoutParams(-1, dp(28)));
  }

  private android.graphics.drawable.GradientDrawable createPanelBackground() {
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setColor(Color.rgb(12, 19, 32));
    bg.setCornerRadius(dp(16));
    bg.setStroke(dp(1), Color.rgb(55, 91, 143));
    return bg;
  }

  private void rebuildKeyboard() {
    if (keyboardView != null) {
      try { windowManager.removeView(keyboardView); } catch (Exception ignored) {}
      keyboardView = null;
    }
    if (landscapeTrackpadView != null) {
      try { windowManager.removeView(landscapeTrackpadView); } catch (Exception ignored) {}
      landscapeTrackpadView = null;
      landscapeTrackpadParams = null;
    }
    showKeyboard();
  }

  private void addSettingsPanel(LinearLayout panel) {
    TextView hint = new TextView(this);
    hint.setText("Pilih layout. Geser judul untuk memindahkan keyboard.");
    hint.setTextColor(Color.rgb(206, 213, 226));
    hint.setPadding(dp(4), dp(4), dp(4), dp(8));
    panel.addView(hint);
    LinearLayout layouts = new LinearLayout(this);
    String[] names = {"PC", "Standar", "Hacker", "Gamepad", "Gabung"};
    for (int i = 0; i < names.length; i++) {
      final int selected = i;
      Button choice = new Button(this);
      choice.setText((keyboardLayout == i ? "✓ " : "") + names[i]);
      choice.setAllCaps(false);
      choice.setTextSize(10);
      styleButton(choice, keyboardLayout == i);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
      lp.setMargins(dp(2), dp(2), dp(2), dp(2));
      layouts.addView(choice, lp);
      choice.setOnClickListener(v -> {
        keyboardLayout = selected;
        gamepadMode = selected == 3;
        keyboardPrefs.edit().putInt("layout", keyboardLayout)
            .putBoolean("gamepad_mode", gamepadMode).apply();
        settingsVisible = false;
        rebuildKeyboard();
      });
    }
    panel.addView(layouts);
    addSettingSlider(panel, "Lebar keyboard", widthPercent, 20, 100, value -> {
      widthPercent = value;
      keyboardPrefs.edit().putInt("width_percent", widthPercent).apply();
      updateOverlayWidth();
    }, "%");
    addSettingSlider(panel, "Tinggi tombol", keyHeightDp, 28, 76, value -> {
      keyHeightDp = value;
      keyboardPrefs.edit().putInt("key_height_dp", keyHeightDp).apply();
    }, " dp");
    addSettingSlider(panel, "Posisi dari atas", verticalOffsetDp, 0, 600, value -> {
      verticalOffsetDp = value;
      keyboardPrefs.edit().putInt("vertical_offset_dp", verticalOffsetDp).apply();
      if (params != null) {
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(verticalOffsetDp);
        try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
      }
    }, " dp");
    Button apply = new Button(this);
    apply.setText("Terapkan ukuran");
    styleButton(apply, true);
    apply.setAllCaps(false);
    apply.setOnClickListener(v -> { settingsVisible = false; rebuildKeyboard(); });
    panel.addView(apply, new LinearLayout.LayoutParams(-1, dp(42)));
  }

  private interface SettingValueListener { void onValue(int value); }

  private void addSettingSlider(LinearLayout panel, String title, int current, int min,
      int max, SettingValueListener listener, String unit) {
    TextView label = new TextView(this);
    label.setText(title + ": " + current + unit);
    label.setTextColor(Color.rgb(206, 213, 226));
    panel.addView(label);
    SeekBar seek = new SeekBar(this);
    seek.setMax(max - min);
    seek.setProgress(Math.max(0, Math.min(max - min, current - min)));
    panel.addView(seek);
    seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
        int value = progress + min;
        label.setText(title + ": " + value + unit);
        if (fromUser) listener.onValue(value);
      }
      @Override public void onStartTrackingTouch(SeekBar bar) {}
      @Override public void onStopTrackingTouch(SeekBar bar) {}
    });
  }

  private void updateOverlayWidth() {
    if (params == null || keyboardView == null) return;
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    windowManager.getDefaultDisplay().getMetrics(metrics);
    if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
      params.width = Math.max(dp(180), Math.round(metrics.widthPixels * 0.50f * widthPercent / 100f));
    } else {
      params.width = Math.max(dp(260), Math.round(metrics.widthPixels * widthPercent / 100f));
    }
    try { windowManager.updateViewLayout(keyboardView, params); } catch (Exception ignored) {}
  }

  private void addKeyboardLayout(LinearLayout panel) {
    if (keyboardLayout == 0) {
      addRow(panel, new String[] {"1","2","3","4","5","6","7","8","9","0"});
      addRow(panel, new String[] {"q","w","e","r","t","y","u","i","o","p"});
      addRow(panel, new String[] {"a","s","d","f","g","h","j","k","l"});
      addRow(panel, new String[] {"⇧","z","x","c","v","b","n","m","⌫"});
      addBottomRow(panel, "Ctrl", "Alt", "Space", "Tab", "Enter");
    } else if (keyboardLayout == 1) {
      addRow(panel, new String[] {"q","w","e","r","t","y","u","i","o","p"});
      addRow(panel, new String[] {"a","s","d","f","g","h","j","k","l"});
      addRow(panel, new String[] {"⇧","z","x","c","v","b","n","m","⌫"});
      addBottomRow(panel, "?123", ",", "Space", ".", "Enter");
    } else if (keyboardLayout == 2) {
      addRow(panel, new String[] {"!","@","#","$","%","^","&","*","(",")"});
      addRow(panel, new String[] {"~","|","\\","{","}","[","]","<",">"});
      addRow(panel, new String[] {"_","+","-","=","/",":",";","$"});
      addBottomRow(panel, "ESC", "TAB", "Space", "DEL", "Enter");
    } else if (keyboardLayout == 3) {
      addGamepadRow(panel, new String[] {"↑", "Y", "X", "B", "A", "↓"});
      addGamepadRow(panel, new String[] {"L1", "←", "OK", "→", "R1"});
      addBottomRow(panel, "Select", "L2", "Start", "R2", "Back");
    } else {
      // Default is Indonesian QWERTY. The symbol switch exposes numbers and
      // punctuation on the same panel without sacrificing the trackpad.
      if (symbolsMode) {
        addTypingRow(panel, new String[] {"1","2","3","4","5","6","7","8","9","0"});
        addTypingRow(panel, new String[] {"@","#","$","%","&","-","+","(",")","/"});
        addTypingRow(panel, new String[] {"*","~","'",":",";","!","?",".",",","⌫"});
      } else {
        addTypingRow(panel, new String[] {"q","w","e","r","t","y","u","i","o","p"});
        addTypingRow(panel, new String[] {"a","s","d","f","g","h","j","k","l"});
        addTypingActionRow(panel, new String[] {"Shift","z","x","c","v","b","n","m","⌫"});
      }
      addIndonesianBottomRow(panel);
      addIntegratedControls(panel);
      if (getResources().getConfiguration().orientation != android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
        addIntegratedTrackpad(panel);
      }
    }
  }

  private void addSpecialKeyRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    for (String key : keys) {
      float weight = "Esc".equals(key) ? 1.35f : 1f;
      addKey(row, key, () -> {
        int code;
        if ("Esc".equals(key)) code = KeyEvent.KEYCODE_ESCAPE;
        else {
          try { code = KeyEvent.KEYCODE_F1 + Integer.parseInt(key.substring(1)) - 1; }
          catch (Exception ex) { code = KeyEvent.KEYCODE_UNKNOWN; }
        }
        if (code != KeyEvent.KEYCODE_UNKNOWN) sendKey(code, false);
      }, weight);
    }
    panel.addView(row);
  }

  private void addNavigationModifierRow(LinearLayout panel) {
    LinearLayout row = new LinearLayout(this);
    String[] keys = {"Ctrl","Fn","Alt","←","↓","→","Home","End","Space","Enter"};
    for (String key : keys) {
      float weight = "Space".equals(key) ? 2.6f : 1f;
      addKey(row, key, () -> {
        switch (key) {
          case "Ctrl":
            ctrlEnabled = !ctrlEnabled;
            Toast.makeText(this, ctrlEnabled ? "Ctrl aktif" : "Ctrl nonaktif", Toast.LENGTH_SHORT).show();
            break;
          case "Alt":
            altEnabled = !altEnabled;
            Toast.makeText(this, altEnabled ? "Alt aktif" : "Alt nonaktif", Toast.LENGTH_SHORT).show();
            break;
          case "Fn":
            keyboardLayout = keyboardLayout == 4 ? 5 : 4;
            keyboardPrefs.edit().putInt("layout", keyboardLayout).apply();
            rebuildKeyboard();
            break;
          case "←": sendKey(KeyEvent.KEYCODE_DPAD_LEFT, false); break;
          case "↓": sendKey(KeyEvent.KEYCODE_DPAD_DOWN, false); break;
          case "→": sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, false); break;
          case "Home": sendKey(KeyEvent.KEYCODE_MOVE_HOME, false); break;
          case "End": sendKey(KeyEvent.KEYCODE_MOVE_END, false); break;
          case "Space": sendText(" "); break;
          case "Enter": sendKey(KeyEvent.KEYCODE_ENTER, false); break;
        }
      }, weight);
    }
    panel.addView(row);
  }

  private void addTypingRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    for (String key : keys) {
      addKey(row, key, () -> {
        if ("⌫".equals(key)) sendKey(KeyEvent.KEYCODE_DEL, false);
        else sendText(key);
      }, 1f);
    }
    panel.addView(row);
  }

  private void addTypingActionRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    for (String key : keys) {
      float weight = ("Shift".equals(key) || "⌫".equals(key)) ? 1.55f : 1f;
      addKey(row, key, () -> {
        if ("Shift".equals(key)) {
          shiftEnabled = !shiftEnabled;
          Toast.makeText(this, shiftEnabled ? "Shift aktif" : "Shift nonaktif", Toast.LENGTH_SHORT).show();
        } else if ("⌫".equals(key)) {
          sendKey(KeyEvent.KEYCODE_DEL, false);
        } else {
          sendText(key);
        }
      }, weight);
    }
    panel.addView(row);
  }

  private void addIndonesianBottomRow(LinearLayout panel) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    addKey(row, symbolsMode ? "ABC" : "?123", () -> {
      symbolsMode = !symbolsMode;
      rebuildKeyboard();
    }, 1.35f);
    addKey(row, ",", () -> sendText(","), 0.8f);
    addKey(row, "Space", () -> sendText(" "), 4.2f);
    addKey(row, ".", () -> sendText("."), 0.8f);
    addKey(row, "Enter", () -> sendKey(KeyEvent.KEYCODE_ENTER, false), 1.4f);
    panel.addView(row);
  }

  private void addIntegratedControls(LinearLayout panel) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER);
    addKey(row, "Klik kanan", () -> { trackpadButton = MotionEvent.BUTTON_SECONDARY; Toast.makeText(this, "Sekarang ketuk trackpad untuk klik kanan", Toast.LENGTH_SHORT).show(); }, 1.3f);
    addKey(row, "⌨", () -> {
      // Keep the integrated keyboard + trackpad layout active; this key cycles
      // between the integrated layout and the compact laptop layout.
      keyboardLayout = (keyboardLayout == 4) ? 0 : 4;
      keyboardPrefs.edit().putInt("layout", keyboardLayout).apply();
      rebuildKeyboard();
    }, 1f);
    addKey(row, "⚙", () -> {
      settingsVisible = true;
      rebuildKeyboard();
    }, 1f);
    View settingsKey = row.getChildAt(row.getChildCount() - 1);
    settingsKey.setOnLongClickListener(v -> {
      copyDiagnosticLog();
      return true;
    });
    settingsKey.setContentDescription("Pengaturan. Tekan lama untuk menyalin log diagnostik");
    panel.addView(row);
  }

  private void addIntegratedTrackpad(LinearLayout panel) {
    TextView label = new TextView(this);
    label.setText("TRACKPAD  ·  Geser kursor  ·  2 jari scroll  ·  ketuk klik");
    label.setTextColor(Color.rgb(165, 208, 236));
    label.setTextSize(10);
    label.setPadding(dp(4), dp(5), dp(4), dp(7));
    panel.addView(label);

    TextView pad = new TextView(this);
    pad.setText("⠿\n\nTRACKPAD");
    pad.setTextColor(Color.rgb(180, 222, 247));
    pad.setTextSize(12);
    pad.setGravity(Gravity.CENTER);
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setColor(Color.rgb(31, 44, 58));
    bg.setCornerRadius(dp(16));
    bg.setStroke(dp(1), Color.rgb(75, 128, 160));
    pad.setBackground(bg);
    int defaultPadHeight = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 220 : 155;
    int padHeight = keyboardPrefs.getInt("trackpad_height_dp", defaultPadHeight);
    int savedPadWidth = keyboardPrefs.getInt("trackpad_width_dp", -1);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(savedPadWidth > 0 ? dp(savedPadWidth) : -1, dp(padHeight));
    if (savedPadWidth > 0) lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.setMargins(dp(2), 0, dp(2), dp(4));
    panel.addView(pad, lp);
    TextView resizeHandle = new TextView(this);
    resizeHandle.setText("⤢  Ubah ukuran trackpad");
    resizeHandle.setTextColor(Color.rgb(167, 202, 255));
    resizeHandle.setTextSize(10);
    resizeHandle.setGravity(Gravity.CENTER);
    resizeHandle.setPadding(dp(4), dp(3), dp(4), dp(3));
    android.graphics.drawable.GradientDrawable handleBg = new android.graphics.drawable.GradientDrawable();
    handleBg.setColor(Color.rgb(25, 38, 57));
    handleBg.setCornerRadius(dp(9));
    resizeHandle.setBackground(handleBg);
    LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(-1, dp(28));
    handleLp.setMargins(dp(2), dp(2), dp(2), dp(4));
    panel.addView(resizeHandle, handleLp);
    final int[] padSize = {padHeight, -1};
    resizeHandle.setOnTouchListener((v, event) -> {
      switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          dragStartX = event.getRawX();
          dragStartY = event.getRawY();
          dragOriginX = pad.getWidth();
          dragOriginY = pad.getHeight();
          return true;
        case MotionEvent.ACTION_MOVE:
          int maxH = Math.max(dp(120), getResources().getDisplayMetrics().heightPixels - dp(100));
          int minH = dp(100);
          int nextH = Math.max(minH, Math.min(maxH,
              Math.round(dragOriginY + event.getRawY() - dragStartY)));
          int maxW = Math.max(dp(130), panel.getWidth() - dp(12));
          int minW = dp(100);
          int nextW = Math.max(minW, Math.min(maxW,
              Math.round(dragOriginX + event.getRawX() - dragStartX)));
          LinearLayout.LayoutParams current = (LinearLayout.LayoutParams) pad.getLayoutParams();
          current.height = nextH;
          current.width = nextW;
          current.weight = 0f;
          current.gravity = Gravity.CENTER_HORIZONTAL;
          pad.setLayoutParams(current);
          padSize[0] = nextH;
          padSize[1] = nextW;
          return true;
        case MotionEvent.ACTION_UP:
          keyboardPrefs.edit().putInt("trackpad_height_dp", Math.round(padSize[0] / getResources().getDisplayMetrics().density))
              .putInt("trackpad_width_dp", Math.round(padSize[1] / getResources().getDisplayMetrics().density)).apply();
          return true;
      }
      return true;
    });

    pad.setOnTouchListener((v, e) -> {
      int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        lastFingerX = e.getX();
        lastFingerY = e.getY();
        filteredDeltaX = 0f;
        filteredDeltaY = 0f;
        trackpadMoved = false;
        twoFingerGesture = false;
        if (!ensureMousePosition()) return true;
        updateIntegratedCursor();
        State.log("integrated trackpad ready: targetDisplayId=" + targetDisplayId
            + ", inputManager=" + (getActiveInputManager() != null) + ", rootMode=" + rootMode);
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_DOWN) {
        if (e.getPointerCount() >= 2) {
          twoFingerGesture = true;
          lastFingerX = e.getX(0);
          lastFingerY = e.getY(0);
        }
        return true;
      }
      if (action == MotionEvent.ACTION_MOVE) {
        if (e.getPointerCount() >= 2) {
          twoFingerGesture = true;
          float dy = e.getY(0) - lastFingerY;
          if (Math.abs(dy) >= dp(2)) {
            float scroll = -dy / Math.max(1f, dp(18));
            if (!TouchpadActivity.scrollFloatingTrackpad(targetDisplayId, scroll)) {
              if (rootMode) {
                runRootMouseCommand("mouse scroll " + Math.round(mouseX) + " "
                    + Math.round(mouseY) + " 0 " + Math.round(scroll));
              } else {
                injectMouseScroll(scroll);
              }
            }
          }
          lastFingerX = e.getX(0);
          lastFingerY = e.getY(0);
          trackpadMoved = true;
          return true;
        }
        if (twoFingerGesture) return true;
        float rawDx = e.getX() - lastFingerX;
        float rawDy = e.getY() - lastFingerY;
        lastFingerX = e.getX();
        lastFingerY = e.getY();
        // Ignore sub-pixel sensor noise, then smooth each delta rather than
        // smoothing absolute coordinates (which would add noticeable lag).
        if (Math.abs(rawDx) < POINTER_DEADZONE_PX) rawDx = 0f;
        if (Math.abs(rawDy) < POINTER_DEADZONE_PX) rawDy = 0f;
        filteredDeltaX += POINTER_FILTER_CURRENT * (rawDx - filteredDeltaX);
        filteredDeltaY += POINTER_FILTER_CURRENT * (rawDy - filteredDeltaY);
        float dx = filteredDeltaX;
        float dy = filteredDeltaY;
        if (Math.abs(dx) + Math.abs(dy) > 0.8f) trackpadMoved = true;

        android.hardware.display.DisplayManager manager =
            (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        android.view.Display display = manager != null ? manager.getDisplay(targetDisplayId) : null;
        if (display == null) {
          State.log("integrated trackpad target display missing: " + targetDisplayId);
          return true;
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        mouseX = Math.max(0, Math.min(metrics.widthPixels - 1,
            mouseX + dx * POINTER_SENSITIVITY * metrics.widthPixels / Math.max(1f, v.getWidth())));
        mouseY = Math.max(0, Math.min(metrics.heightPixels - 1,
            mouseY + dy * POINTER_SENSITIVITY * metrics.heightPixels / Math.max(1f, v.getHeight())));

        // Match the working built-in trackpad: move its software cursor on the
        // target display instead of relying on injected mouse ACTION_MOVE events.
        updateIntegratedCursor();
        return true;
      }
      if (action == MotionEvent.ACTION_UP) {
        if (!trackpadMoved && !twoFingerGesture) {
          injectTrackpadTap();
        }
        trackpadButton = MotionEvent.BUTTON_PRIMARY;
        twoFingerGesture = false;
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_UP) {
        if (e.getPointerCount() <= 2) {
          lastFingerX = e.getX(0);
          lastFingerY = e.getY(0);
        }
        return true;
      }
      if (action == MotionEvent.ACTION_CANCEL) {
        trackpadButton = MotionEvent.BUTTON_PRIMARY;
        twoFingerGesture = false;
        return true;
      }
      return true;
    });
  }

  private void runRootMouseCommand(String mouseArgs) {
    if (!rootMode) return;
    String command = "input -d " + targetDisplayId + " " + mouseArgs;
    rootInputExecutor.execute(() -> {
      RootAccess.Result result = RootAccess.run(command);
      if (!result.success) {
        State.log("root mouse command failed: " + command + " ; " + result.output);
      }
    });
  }

  private void copyDiagnosticLog() {
    StringBuilder out = new StringBuilder();
    out.append("=== Extended Desk diagnostic ===\n")
        .append("targetDisplayId=").append(targetDisplayId).append("\n")
        .append("rootMode=").append(rootMode).append("\n")
        .append("shizukuInputManager=").append(inputManager != null).append("\n");
    synchronized (State.logs) {
      int start = Math.max(0, State.logs.size() - 160);
      for (int i = start; i < State.logs.size(); i++) out.append(State.logs.get(i)).append("\n");
    }
    android.content.ClipboardManager clipboard =
        (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    if (clipboard != null) {
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Extended Desk log", out.toString()));
      Toast.makeText(this, "Log disalin. Tempelkan langsung ke chat.", Toast.LENGTH_LONG).show();
    } else {
      State.log("diagnostic clipboard unavailable");
    }
  }

  private boolean ensureMousePosition() {
    android.hardware.display.DisplayManager dmgr =
        (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
    android.view.Display d = dmgr != null ? dmgr.getDisplay(targetDisplayId) : null;
    if (d == null) {
      State.log("trackpad cannot initialize pointer: display " + targetDisplayId + " not found");
      return false;
    }
    android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
    d.getRealMetrics(metrics);
    if (mouseX < 0 || mouseY < 0) {
      float[] builtInCursor = TouchpadActivity.getFloatingTrackpadCursor(targetDisplayId);
      if (builtInCursor != null && builtInCursor.length >= 2) {
        mouseX = builtInCursor[0];
        mouseY = builtInCursor[1];
      } else {
        mouseX = metrics.widthPixels / 2f;
        mouseY = metrics.heightPixels / 2f;
      }
    }
    return true;
  }

  private void ensureTargetCursorOverlay() {
    if (targetCursorView != null && targetCursorWindowManager != null) return;
    android.hardware.display.DisplayManager manager =
        (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
    android.view.Display display = manager != null ? manager.getDisplay(targetDisplayId) : null;
    if (display == null) {
      State.log("trackpad cursor overlay: target display " + targetDisplayId + " missing");
      return;
    }
    try {
      android.content.Context displayContext = createDisplayContext(display);
      targetCursorWindowManager =
          (WindowManager) displayContext.getSystemService(WINDOW_SERVICE);
      if (targetCursorWindowManager == null) {
        State.log("trackpad cursor overlay: WindowManager unavailable on display " + targetDisplayId);
        return;
      }
      targetCursorView = new ImageView(displayContext);
      targetCursorView.setImageResource(R.drawable.mouse_cursor);
      targetCursorView.setScaleType(ImageView.ScaleType.FIT_CENTER);
      targetCursorParams = new WindowManager.LayoutParams(
          dp(26),
          dp(32),
          WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
              | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
          PixelFormat.TRANSLUCENT);
      targetCursorParams.gravity = Gravity.TOP | Gravity.START;
      targetCursorParams.x = Math.round(mouseX);
      targetCursorParams.y = Math.round(mouseY);
      targetCursorWindowManager.addView(targetCursorView, targetCursorParams);
      State.log("trackpad cursor overlay created on display " + targetDisplayId);
    } catch (Throwable t) {
      State.log("trackpad cursor overlay failed: " + t.getMessage());
      targetCursorView = null;
      targetCursorParams = null;
      targetCursorWindowManager = null;
    }
  }

  private void updateIntegratedCursor() {
    if (TouchpadActivity.moveFloatingTrackpadCursor(targetDisplayId, mouseX, mouseY)) {
      // When the main Extend trackpad is alive, move its original cursor instead
      // of creating a second cursor overlay.
      removeTargetCursorOverlay();
    } else {
      updateTargetCursorOverlay();
    }
  }

  private void updateTargetCursorOverlay() {
    ensureTargetCursorOverlay();
    if (targetCursorView == null || targetCursorWindowManager == null || targetCursorParams == null) {
      return;
    }
    targetCursorParams.x = Math.round(mouseX);
    targetCursorParams.y = Math.round(mouseY);
    try {
      targetCursorWindowManager.updateViewLayout(targetCursorView, targetCursorParams);
    } catch (Throwable t) {
      State.log("trackpad cursor update failed: " + t.getMessage());
    }
  }

  private void injectTrackpadTap() {
    if (!ensureMousePosition()) return;
    updateIntegratedCursor();
    if (TouchpadActivity.clickFloatingTrackpad(targetDisplayId, trackpadButton)) {
      State.log("integrated trackpad delegated click to built-in controller: display=" + targetDisplayId);
      return;
    }

    if (trackpadButton == MotionEvent.BUTTON_SECONDARY) {
      if (rootMode) {
        State.log("trackpad right-click unavailable in root shell fallback on this build");
      } else {
        injectMouseClick(MotionEvent.BUTTON_SECONDARY);
      }
      return;
    }

    if (rootMode) {
      runRootMouseCommand("tap " + Math.round(mouseX) + " " + Math.round(mouseY));
      State.log("trackpad root tap requested: display=" + targetDisplayId
          + ", x=" + Math.round(mouseX) + ", y=" + Math.round(mouseY));
      return;
    }

    IInputManager manager = getActiveInputManager();
    if (manager == null) {
      State.log("trackpad tap skipped: InputManager unavailable and root mode is off");
      Toast.makeText(this, "Klik trackpad gagal: InputManager tidak tersedia.", Toast.LENGTH_SHORT).show();
      return;
    }
    long downTime = android.os.SystemClock.uptimeMillis();
    boolean downAccepted = injectTouchAtCursor(
        manager, MotionEvent.ACTION_DOWN, android.os.SystemClock.uptimeMillis(), downTime);
    boolean upAccepted = injectTouchAtCursor(
        manager, MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis(), downTime);
    State.log("trackpad tap result: display=" + targetDisplayId
        + ", downAccepted=" + downAccepted + ", upAccepted=" + upAccepted);
  }

  private boolean injectTouchAtCursor(
      IInputManager manager, int action, long eventTime, long downTime) {
    MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties()};
    properties[0].id = 0;
    properties[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords[] coordinates = {new MotionEvent.PointerCoords()};
    coordinates[0].x = mouseX;
    coordinates[0].y = mouseY;
    coordinates[0].pressure = action == MotionEvent.ACTION_DOWN ? 1f : 0f;
    coordinates[0].size = 1f;
    MotionEvent event = MotionEvent.obtain(
        downTime,
        eventTime,
        action,
        1,
        properties,
        coordinates,
        0,
        0,
        1f,
        1f,
        0,
        0,
        InputDevice.SOURCE_TOUCHSCREEN,
        0);
    try {
      MotionEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      boolean accepted = manager.injectInputEvent(event, 0);
      if (!accepted) {
        State.log("trackpad touch event rejected: display=" + targetDisplayId + ", action=" + action);
      }
      return accepted;
    } catch (Throwable t) {
      State.log("trackpad touch injection failed: " + t.getMessage());
      return false;
    } finally {
      event.recycle();
    }
  }

  private void removeTargetCursorOverlay() {
    if (targetCursorView != null && targetCursorWindowManager != null) {
      try {
        targetCursorWindowManager.removeView(targetCursorView);
      } catch (Throwable t) {
        State.log("trackpad cursor overlay removal failed: " + t.getMessage());
      }
    }
    targetCursorView = null;
    targetCursorParams = null;
    targetCursorWindowManager = null;
  }

  private void injectMouseHover(int action) {
    // Match TouchpadActivity's working input path: use SOURCE_MOUSE + ACTION_MOVE.
    // ACTION_HOVER_MOVE (9) is rejected on some Android 15 virtual displays.
    if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE
        || action == MotionEvent.ACTION_HOVER_EXIT) {
      injectMouseEvent(MotionEvent.ACTION_MOVE, 0, 0);
    } else {
      injectMouseEvent(action, 0, 0);
    }
  }

  private IInputManager getActiveInputManager() {
    try {
      return inputManager != null ? inputManager : ServiceUtils.getInputManager();
    } catch (Throwable t) {
      State.log("trackpad cannot obtain InputManager: " + t.getMessage());
      return null;
    }
  }

  private void injectMouseClick(int button) {
    if (getActiveInputManager() == null || !ensureMousePosition()) {
      State.log("trackpad click skipped: InputManager unavailable or target display missing");
      return;
    }
    long now = android.os.SystemClock.uptimeMillis();
    // Send the same down/press/release/up sequence used by the built-in Extended trackpad.
    injectMouseEvent(MotionEvent.ACTION_DOWN, 0, button);
    injectMouseEvent(MotionEvent.ACTION_BUTTON_PRESS, button, button);
    injectMouseEvent(MotionEvent.ACTION_BUTTON_RELEASE, button, 0);
    injectMouseEvent(MotionEvent.ACTION_UP, 0, 0);
    State.log("trackpad click sequence sent: display=" + targetDisplayId + ", button=" + button);
  }

  private void injectMouseScroll(float amount) {
    IInputManager manager = getActiveInputManager();
    if (manager == null || targetDisplayId < 0 || mouseX < 0 || mouseY < 0) {
      State.log("trackpad scroll skipped: InputManager unavailable or pointer not initialized");
      return;
    }
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
    props[0].id = 0;
    props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
    coords[0].x = mouseX;
    coords[0].y = mouseY;
    coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, amount);
    MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    try {
      MotionEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      boolean accepted = manager.injectInputEvent(event, 0);
      if (!accepted) State.log("trackpad scroll rejected: display=" + targetDisplayId);
    } catch (Throwable t) {
      State.log("trackpad scroll injection failed: " + t.getMessage());
    } finally {
      event.recycle();
    }
  }

  private void injectMouseEvent(int action, int actionButton, int buttonState) {
    IInputManager manager = getActiveInputManager();
    if (manager == null || targetDisplayId < 0 || mouseX < 0 || mouseY < 0) {
      State.log("trackpad event skipped: InputManager unavailable or pointer not initialized");
      return;
    }
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
    props[0].id = 0;
    props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
    coords[0].x = mouseX;
    coords[0].y = mouseY;
    MotionEvent event = MotionEvent.obtain(now, now, action, 1, props, coords, 0,
        buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    try {
      MotionEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
        hidden.setActionButton(actionButton);
      }
      IInputManager activeManager = getActiveInputManager();
      if (activeManager == null) {
        State.log("trackpad event skipped: InputManager unavailable");
        return;
      }
      boolean accepted = activeManager.injectInputEvent(event, 0);
      if (!accepted) State.log("trackpad event rejected: display=" + targetDisplayId + ", action=" + action);
    } catch (Throwable t) {
      State.log("trackpad event injection failed: " + t.getMessage());
    } finally {
      event.recycle();
    }
  }

  private void addGamepadRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    for (String key : keys) addLayoutKey(row, key, 1f);
    panel.addView(row);
  }

  private void addBottomRow(LinearLayout panel, String a, String b, String c, String d, String e) {
    LinearLayout row = new LinearLayout(this);
    addLayoutKey(row, a, 1f); addLayoutKey(row, b, 1f); addLayoutKey(row, c, 2f);
    addLayoutKey(row, d, 1f); addLayoutKey(row, e, 1f);
    panel.addView(row);
  }

  private void addLayoutKey(LinearLayout row, String label, float weight) {
    addKey(row, label, () -> {
      if (keyboardLayout == 3) {
        int code;
        switch (label) {
          case "↑": code = KeyEvent.KEYCODE_DPAD_UP; break;
          case "↓": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
          case "←": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
          case "→": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
          case "A": case "OK": code = KeyEvent.KEYCODE_BUTTON_A; break;
          case "B": case "Back": code = KeyEvent.KEYCODE_BUTTON_B; break;
          case "X": code = KeyEvent.KEYCODE_BUTTON_X; break;
          case "Y": code = KeyEvent.KEYCODE_BUTTON_Y; break;
          case "L1": code = KeyEvent.KEYCODE_BUTTON_L1; break;
          case "R1": code = KeyEvent.KEYCODE_BUTTON_R1; break;
          case "L2": code = KeyEvent.KEYCODE_BUTTON_L2; break;
          case "R2": code = KeyEvent.KEYCODE_BUTTON_R2; break;
          case "Start": code = KeyEvent.KEYCODE_BUTTON_START; break;
          case "Select": code = KeyEvent.KEYCODE_BUTTON_SELECT; break;
          default: code = KeyEvent.KEYCODE_ENTER; break;
        }
        sendKey(code, false);
      } else {
        switch (label) {
          case "Space": sendText(" "); break;
          case "Enter": sendKey(KeyEvent.KEYCODE_ENTER, false); break;
          case "⌫": case "DEL": sendKey(KeyEvent.KEYCODE_DEL, false); break;
          case "Tab": case "TAB": sendKey(KeyEvent.KEYCODE_TAB, false); break;
          case "ESC": sendKey(KeyEvent.KEYCODE_ESCAPE, false); break;
          case "Ctrl": ctrlEnabled = !ctrlEnabled; Toast.makeText(this, ctrlEnabled ? "Ctrl aktif — tekan tombol berikutnya" : "Ctrl nonaktif", Toast.LENGTH_SHORT).show(); break;
          case "Alt": altEnabled = !altEnabled; Toast.makeText(this, altEnabled ? "Alt aktif — tekan tombol berikutnya" : "Alt nonaktif", Toast.LENGTH_SHORT).show(); break;
          case "⇧": case "Shift": shiftEnabled = !shiftEnabled; Toast.makeText(this, shiftEnabled ? "Shift aktif" : "Shift nonaktif", Toast.LENGTH_SHORT).show(); break;
          case "Caps": capsEnabled = !capsEnabled; Toast.makeText(this, capsEnabled ? "Caps Lock aktif" : "Caps Lock nonaktif", Toast.LENGTH_SHORT).show(); break;
          default: sendText(label);
        }
      }
    }, weight);
  }

  private void addRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    for (String key : keys) {
      addKey(row, key, () -> {
        if ("⌫".equals(key)) sendKey(KeyEvent.KEYCODE_DEL, false);
        else if ("⇧".equals(key) || "Shift".equals(key)) { shiftEnabled = !shiftEnabled; Toast.makeText(this, shiftEnabled ? "Shift aktif" : "Shift nonaktif", Toast.LENGTH_SHORT).show(); }
        else if ("Caps".equals(key)) { capsEnabled = !capsEnabled; Toast.makeText(this, capsEnabled ? "Caps Lock aktif" : "Caps Lock nonaktif", Toast.LENGTH_SHORT).show(); }
        else sendText(key);
      }, 1f);
    }
    panel.addView(row);
  }

  private void addKey(LinearLayout row, String label, Runnable action, float weight) {
    Button button = new Button(this);
    button.setText(label);
    button.setTextSize(12);
    styleButton(button, false);
    button.setAllCaps(false);
    button.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(keyHeightDp), weight);
    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
    row.addView(button, lp);
    button.setOnClickListener(v -> action.run());
  }

  private void sendText(String text) {
    if (text != null && text.length() == 1 && Character.isLetter(text.charAt(0))) {
      if (shiftEnabled || capsEnabled) text = text.toUpperCase(java.util.Locale.ROOT);
      if (shiftEnabled) shiftEnabled = false;
    }
    if ((ctrlEnabled || altEnabled) && text != null && text.length() == 1) {
      char ch = Character.toUpperCase(text.charAt(0));
      int keyCode = ch >= 'A' && ch <= 'Z'
          ? KeyEvent.KEYCODE_A + (ch - 'A')
          : (ch >= '0' && ch <= '9' ? KeyEvent.KEYCODE_0 + (ch - '0') : KeyEvent.KEYCODE_UNKNOWN);
      if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
        sendModifiedKey(keyCode);
        ctrlEnabled = false;
        altEnabled = false;
        shiftEnabled = false;
        return;
      }
    }
    if (rootMode) {
      String safeText = text.replace("%", "\\%").replace(" ", "%s").replace("'", "");
      rootInputExecutor.execute(() -> RootAccess.run("input -d " + targetDisplayId + " text '" + safeText + "'"));
      return;
    }
    try {
      KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
      if (events == null) return;
      for (KeyEvent event : events) inject(event);
    } catch (Throwable e) {
      Toast.makeText(this, "Input gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  private void sendModifiedKey(int keyCode) {
    if (rootMode) {
      java.util.ArrayList<String> combo = new java.util.ArrayList<>();
      if (ctrlEnabled) combo.add("KEYCODE_CTRL_LEFT");
      if (altEnabled) combo.add("KEYCODE_ALT_LEFT");
      combo.add(KeyEvent.keyCodeToString(keyCode));
      String command = "input keycombination " + android.text.TextUtils.join(" ", combo);
      rootInputExecutor.execute(() -> RootAccess.run(command));
      return;
    }
    long now = android.os.SystemClock.uptimeMillis();
    int meta = 0;
    if (ctrlEnabled) meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
    if (altEnabled) meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
    if (shiftEnabled) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
    inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
    inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
  }

  private void sendKey(int keyCode, boolean shift) {
    if (rootMode) {
      rootInputExecutor.execute(
          () -> {
            RootAccess.run("input -d " + targetDisplayId + " keyevent " + keyCode);
          });
      return;
    }
    long now = android.os.SystemClock.uptimeMillis();
    int meta = shift ? KeyEvent.META_SHIFT_ON : 0;
    inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
    inject(new KeyEvent(now, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0, meta));
  }

  private void inject(KeyEvent event) {
    try {
      KeyEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      IInputManager manager = inputManager != null ? inputManager : ServiceUtils.getInputManager();
      if (manager == null) {
        State.log("floating keyboard input failed: InputManager unavailable");
        return;
      }
      boolean accepted = manager.injectInputEvent(event, 0);
      if (!accepted) State.log("floating keyboard input rejected for display " + targetDisplayId + ", keyCode=" + event.getKeyCode());
    } catch (Throwable e) {
      State.log("floating keyboard input failed: " + e.getMessage());
    }
  }

  private void styleButton(Button button, boolean accent) {
    button.setAllCaps(false);
    button.setTextColor(accent ? Color.rgb(24, 25, 36) : Color.rgb(235, 238, 246));
    button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
        accent ? Color.rgb(111, 211, 244) : Color.rgb(43, 61, 78)));
    button.setStateListAnimator(null);
    button.setMinHeight(dp(38));
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    mainHandler.post(() -> {
      if (keyboardView != null) rebuildKeyboard();
    });
  }

  @Override
  public void onDestroy() {
    try { gameTouchController.releaseAll(); } catch (Throwable ignored) {}
    if (keyboardView != null && windowManager != null) {
      try { windowManager.removeView(keyboardView); } catch (Exception ignored) {}
      keyboardView = null;
    }
    if (landscapeTrackpadView != null && windowManager != null) {
      try { windowManager.removeView(landscapeTrackpadView); } catch (Exception ignored) {}
      landscapeTrackpadView = null;
      landscapeTrackpadParams = null;
    }
    rootInputExecutor.shutdownNow();
    removeTargetCursorOverlay();
    removeVisualMappingMarker(false);
    inputManager = null;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
