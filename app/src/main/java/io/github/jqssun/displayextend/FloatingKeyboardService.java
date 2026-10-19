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
  private WindowManager.LayoutParams params;
  private int targetDisplayId = 0;
  private boolean rootMode = false;
  private int keyHeightDp = 42;
  private int widthPercent = 96;
  private int verticalOffsetDp = 12;
  private int keyboardLayout = 0;
  private boolean settingsVisible = false;
  private float dragStartX, dragStartY;
  private int dragOriginX, dragOriginY;
  private android.content.SharedPreferences keyboardPrefs;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService rootInputExecutor = Executors.newSingleThreadExecutor();
  private IInputManager inputManager;
  private float mouseX = -1f, mouseY = -1f;
  private float lastFingerX, lastFingerY;
  private boolean trackpadMoved;
  private int trackpadButton = MotionEvent.BUTTON_PRIMARY;
  private static final float POINTER_SENSITIVITY = 2.2f;
  private boolean shiftEnabled = false;
  private boolean capsEnabled = false;

  @Override
  public void onCreate() {
    super.onCreate();
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    keyboardPrefs = getSharedPreferences("floating_keyboard", MODE_PRIVATE);
    widthPercent = keyboardPrefs.getInt("width_percent", 96);
    keyHeightDp = keyboardPrefs.getInt("key_height_dp", 42);
    int screenHeightDp = Math.round(getResources().getDisplayMetrics().heightPixels / getResources().getDisplayMetrics().density);
    int defaultY = Math.max(12, screenHeightDp - (keyHeightDp * 4) - 60 - 12);
    verticalOffsetDp = keyboardPrefs.getInt("vertical_offset_dp", defaultY);
    keyboardLayout = keyboardPrefs.getInt("layout", 0);
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
    title.setText("EXTENDED DESK  ·  Display " + targetDisplayId + "  ↕");
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

  private void rebuildKeyboard() {
    if (keyboardView != null) {
      try { windowManager.removeView(keyboardView); } catch (Exception ignored) {}
      keyboardView = null;
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
        keyboardPrefs.edit().putInt("layout", keyboardLayout).apply();
        settingsVisible = false;
        rebuildKeyboard();
      });
    }
    panel.addView(layouts);
    addSettingSlider(panel, "Lebar", widthPercent, 60, 100, value -> {
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
    params.width = Math.max(dp(260), Math.round(metrics.widthPixels * widthPercent / 100f));
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
      addRow(panel, new String[] {"Esc","F1","F2","F3","F4","F5","F6"});
      addRow(panel, new String[] {"Tab","q","w","e","r","t","y","u","i","o","p"});
      addRow(panel, new String[] {"Caps","a","s","d","f","g","h","j","k","l"});
      addBottomRow(panel, "Shift", "z", "x", "c", "⌫");
      addBottomRow(panel, "Ctrl", "Fn", "Alt", "Space", "Enter");
      addIntegratedControls(panel);
      addIntegratedTrackpad(panel);
    }
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
    panel.addView(row);
  }

  private void addIntegratedTrackpad(LinearLayout panel) {
    TextView label = new TextView(this);
    label.setText("TRACKPAD  •  Geser: pointer   •   2 jari: scroll   •   ketuk: klik");
    label.setTextColor(Color.rgb(165, 208, 236));
    label.setTextSize(10);
    label.setPadding(dp(4), dp(4), dp(4), dp(6));
    panel.addView(label);
    TextView pad = new TextView(this);
    pad.setText("◉\n\nTouchpad");
    pad.setTextColor(Color.rgb(180, 222, 247));
    pad.setTextSize(13);
    pad.setGravity(Gravity.CENTER);
    pad.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(38, 42, 54)));
    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
    bg.setColor(Color.rgb(35, 48, 63)); bg.setCornerRadius(dp(16));
    bg.setStroke(dp(1), Color.rgb(75, 128, 160)); pad.setBackground(bg);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(230));
    lp.setMargins(dp(2), 0, dp(2), dp(4)); panel.addView(pad, lp);
    pad.setOnTouchListener((v, e) -> {
      if (inputManager == null) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
          Toast.makeText(this, "Trackpad mouse perlu Shizuku aktif dan diizinkan. Keyboard tetap bisa memakai root.", Toast.LENGTH_LONG).show();
        }
        return true;
      }
      final int action = e.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        lastFingerX = e.getX(); lastFingerY = e.getY(); trackpadMoved = false;
        if (mouseX < 0 || mouseY < 0) {
          android.view.Display d = ((android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(targetDisplayId);
          if (d == null) return true;
          android.util.DisplayMetrics dm = new android.util.DisplayMetrics(); d.getRealMetrics(dm);
          mouseX = dm.widthPixels / 2f; mouseY = dm.heightPixels / 2f;
        }
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_DOWN) {
        // Reset the scroll baseline when the second finger touches down.
        lastFingerX = e.getX(0);
        lastFingerY = e.getY(0);
        return true;
      }
      if (action == MotionEvent.ACTION_MOVE) {
        if (e.getPointerCount() >= 2) {
          float dy = e.getY(0) - lastFingerY;
          if (Math.abs(dy) > dp(2)) injectMouseScroll(-dy / Math.max(1f, dp(18)));
          lastFingerX = e.getX(0); lastFingerY = e.getY(0);
          trackpadMoved = true;
          return true;
        }
        float dx = e.getX() - lastFingerX, dy = e.getY() - lastFingerY;
        if (Math.abs(dx) + Math.abs(dy) > dp(1)) trackpadMoved = true;
        lastFingerX = e.getX(); lastFingerY = e.getY();
        android.view.Display d = ((android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(targetDisplayId);
        if (d == null) return true;
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics(); d.getRealMetrics(dm);
        mouseX = Math.max(0, Math.min(dm.widthPixels - 1, mouseX + dx * POINTER_SENSITIVITY * dm.widthPixels / Math.max(1f, v.getWidth())));
        mouseY = Math.max(0, Math.min(dm.heightPixels - 1, mouseY + dy * POINTER_SENSITIVITY * dm.heightPixels / Math.max(1f, v.getHeight())));
        injectMouseEvent(MotionEvent.ACTION_HOVER_MOVE, 0, 0);
        return true;
      }
      if (action == MotionEvent.ACTION_UP) {
        if (!trackpadMoved) {
          // A standard mouse click is DOWN followed by UP. Sending BUTTON_PRESS/
          // BUTTON_RELEASE as well can cause some Android builds to reject the sequence.
          injectMouseEvent(MotionEvent.ACTION_DOWN, 0, trackpadButton);
          injectMouseEvent(MotionEvent.ACTION_UP, 0, 0);
        }
        trackpadButton = MotionEvent.BUTTON_PRIMARY;
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_UP) {
        lastFingerX = e.getX(0);
        lastFingerY = e.getY(0);
        return true;
      }
      if (action == MotionEvent.ACTION_CANCEL) { trackpadButton = MotionEvent.BUTTON_PRIMARY; return true; }
      return true;
    });;
  }

  private void injectMouseScroll(float amount) {
    if (inputManager == null || targetDisplayId < 0 || mouseX < 0 || mouseY < 0) return;
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
    props[0].id = 0; props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
    coords[0].x = mouseX; coords[0].y = mouseY;
    coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, amount);
    MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    try {
      MotionEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      boolean accepted = inputManager.injectInputEvent(event, 0);
      if (!accepted) State.log("trackpad scroll event rejected for display " + targetDisplayId);
    } catch (Throwable t) { State.log("trackpad scroll injection failed: " + t.getMessage()); }
    finally { event.recycle(); }
  }

  private void injectMouseEvent(int action, int actionButton, int buttonState) {
    if (inputManager == null || targetDisplayId < 0 || mouseX < 0 || mouseY < 0) return;
    long now = android.os.SystemClock.uptimeMillis();
    MotionEvent.PointerProperties[] props = {new MotionEvent.PointerProperties()};
    props[0].id = 0; props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords()};
    coords[0].x = mouseX; coords[0].y = mouseY;
    MotionEvent event = MotionEvent.obtain(now, now, action, 1, props, coords, 0,
        buttonState, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
    try {
      MotionEventHidden hidden = Refine.unsafeCast(event);
      hidden.setDisplayId(targetDisplayId);
      if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) hidden.setActionButton(actionButton);
      boolean accepted = inputManager.injectInputEvent(event, 0);
      if (!accepted) State.log("trackpad mouse event rejected for display " + targetDisplayId + ", action=" + action);
    } catch (Throwable t) { State.log("trackpad mouse injection failed: " + t.getMessage()); }
    finally { event.recycle(); }
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
          case "Ctrl": sendKey(KeyEvent.KEYCODE_CTRL_LEFT, false); break;
          case "Alt": sendKey(KeyEvent.KEYCODE_ALT_LEFT, false); break;
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
  public void onDestroy() {
    if (keyboardView != null && windowManager != null) {
      try { windowManager.removeView(keyboardView); } catch (Exception ignored) {}
      keyboardView = null;
    }
    rootInputExecutor.shutdownNow();
    inputManager = null;
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
