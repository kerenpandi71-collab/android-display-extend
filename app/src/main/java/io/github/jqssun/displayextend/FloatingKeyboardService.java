package io.github.jqssun.displayextend;

import android.app.Service;
import android.app.AlertDialog;
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
  private float dragStartX, dragStartY;
  private int dragOriginX, dragOriginY;
  private android.content.SharedPreferences keyboardPrefs;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService rootInputExecutor = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate() {
    super.onCreate();
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    keyboardPrefs = getSharedPreferences("floating_keyboard", MODE_PRIVATE);
    widthPercent = keyboardPrefs.getInt("width_percent", 96);
    keyHeightDp = keyboardPrefs.getInt("key_height_dp", 42);
    verticalOffsetDp = keyboardPrefs.getInt("vertical_offset_dp", 12);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent != null) targetDisplayId = intent.getIntExtra(EXTRA_DISPLAY_ID, 0);
    if (!Settings.canDrawOverlays(this)) {
      Toast.makeText(this, "Allow display overlay permission first.", Toast.LENGTH_LONG).show();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (ShizukuUtils.hasPermission()) {
      rootMode = false;
      if (keyboardView == null) showKeyboard();
    } else {
      // Request root only when the user explicitly opens the floating keyboard.
      Toast.makeText(this, "Approve the superuser request to route keyboard input.", Toast.LENGTH_LONG).show();
      new Thread(
              () -> {
                RootAccess.Result result = RootAccess.check();
                if (result.success && result.output.contains("uid=0")) {
                  rootMode = true;
                  mainHandler.post(() -> { if (keyboardView == null) showKeyboard(); });
                } else {
                  mainHandler.post(
                      () -> {
                        Toast.makeText(this, "Keyboard needs Shizuku permission or approved root access.", Toast.LENGTH_LONG).show();
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
    panel.setBackgroundColor(Color.rgb(29, 34, 45));

    LinearLayout titleRow = new LinearLayout(this);
    titleRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = new TextView(this);
    title.setText("Keyboard · Layar " + targetDisplayId + "  ↕ geser");
    title.setTextColor(Color.WHITE);
    title.setTextSize(13);
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
    customize.setText("⚙");
    customize.setContentDescription("Atur ukuran keyboard");
    customize.setOnClickListener(v -> showKeyboardSettings());
    titleRow.addView(customize, new LinearLayout.LayoutParams(dp(48), dp(36)));
    Button close = new Button(this);
    close.setText("×");
    close.setOnClickListener(v -> stopSelf());
    titleRow.addView(close, new LinearLayout.LayoutParams(dp(48), dp(36)));
    panel.addView(titleRow);

    addRow(panel, new String[] {"q","w","e","r","t","y","u","i","o","p"});
    addRow(panel, new String[] {"a","s","d","f","g","h","j","k","l"});
    addRow(panel, new String[] {"⇧","z","x","c","v","b","n","m","⌫"});
    LinearLayout bottom = new LinearLayout(this);
    addKey(bottom, "123", () -> sendKey(KeyEvent.KEYCODE_NUM, false), 0.8f);
    addKey(bottom, "Space", () -> sendText(" "), 2.5f);
    addKey(bottom, "Enter", () -> sendKey(KeyEvent.KEYCODE_ENTER, false), 1.1f);
    panel.addView(bottom);

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
    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    params.y = dp(verticalOffsetDp);
    keyboardView = panel;
    try {
      windowManager.addView(keyboardView, params);
    } catch (Exception e) {
      Toast.makeText(this, "Could not show floating keyboard: " + e.getMessage(), Toast.LENGTH_LONG).show();
      stopSelf();
    }
  }

  private void showKeyboardSettings() {
    LinearLayout content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20), dp(8), dp(20), dp(4));
    TextView hint = new TextView(this);
    hint.setText("Atur lebar, tinggi tombol, dan posisi keyboard dari tepi atas layar.");
    content.addView(hint);
    SeekBar width = new SeekBar(this); width.setMax(40); width.setProgress(widthPercent - 60);
    TextView widthLabel = new TextView(this); widthLabel.setText("Lebar: " + widthPercent + "%");
    content.addView(widthLabel); content.addView(width);
    SeekBar height = new SeekBar(this); height.setMax(48); height.setProgress(keyHeightDp - 28);
    TextView heightLabel = new TextView(this); heightLabel.setText("Tinggi tombol: " + keyHeightDp + " dp");
    content.addView(heightLabel); content.addView(height);
    SeekBar offset = new SeekBar(this); offset.setMax(600); offset.setProgress(verticalOffsetDp);
    TextView offsetLabel = new TextView(this); offsetLabel.setText("Posisi vertikal: " + verticalOffsetDp + " dp");
    content.addView(offsetLabel); content.addView(offset);
    width.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
      widthPercent = value + 60; widthLabel.setText("Lebar: " + widthPercent + "%");
    }));
    height.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
      keyHeightDp = value + 28; heightLabel.setText("Tinggi tombol: " + keyHeightDp + " dp");
    }));
    offset.setOnSeekBarChangeListener(new SimpleSeekListener(value -> {
      verticalOffsetDp = value; offsetLabel.setText("Posisi vertikal: " + verticalOffsetDp + " dp");
    }));
    new AlertDialog.Builder(this).setTitle("Ukuran keyboard")
        .setView(content)
        .setNegativeButton("Batal", (d, w) -> {
          widthPercent = keyboardPrefs.getInt("width_percent", 96);
          keyHeightDp = keyboardPrefs.getInt("key_height_dp", 42);
          verticalOffsetDp = keyboardPrefs.getInt("vertical_offset_dp", 12);
        })
        .setPositiveButton("Simpan", (d, w) -> {
          keyboardPrefs.edit().putInt("width_percent", widthPercent)
              .putInt("key_height_dp", keyHeightDp)
              .putInt("vertical_offset_dp", verticalOffsetDp).apply();
          if (params != null && keyboardView != null) {
            try { windowManager.removeView(keyboardView); } catch (Exception ignored) {}
            keyboardView = null;
            showKeyboard();
          }
        }).show();
  }

  private interface SeekValueListener { void onChanged(int value); }
  private static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
    private final SeekValueListener listener;
    SimpleSeekListener(SeekValueListener listener) { this.listener = listener; }
    @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
      listener.onChanged(progress);
    }
    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override public void onStopTrackingTouch(SeekBar seekBar) {}
  }

  private void addRow(LinearLayout panel, String[] keys) {
    LinearLayout row = new LinearLayout(this);
    for (String key : keys) {
      addKey(row, key, () -> {
        if ("⌫".equals(key)) sendKey(KeyEvent.KEYCODE_DEL, false);
        else if ("⇧".equals(key)) Toast.makeText(this, "Use uppercase by long-pressing the key (coming soon).", Toast.LENGTH_SHORT).show();
        else sendText(key);
      }, 1f);
    }
    panel.addView(row);
  }

  private void addKey(LinearLayout row, String label, Runnable action, float weight) {
    Button button = new Button(this);
    button.setText(label);
    button.setTextSize(12);
    button.setAllCaps(false);
    button.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(keyHeightDp), weight);
    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
    row.addView(button, lp);
    button.setOnClickListener(v -> action.run());
  }

  private void sendText(String text) {
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
      Toast.makeText(this, "Input failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
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
      ServiceUtils.getInputManager().injectInputEvent(event, 0);
    } catch (Throwable e) {
      State.log("floating keyboard input failed: " + e.getMessage());
    }
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
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
