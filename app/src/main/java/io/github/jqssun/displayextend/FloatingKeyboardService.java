package io.github.jqssun.displayextend;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
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

  @Override
  public void onCreate() {
    super.onCreate();
    windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
    if (!ShizukuUtils.hasPermission()) {
      Toast.makeText(this, "Authorize Shizuku to route keyboard input to the display.", Toast.LENGTH_LONG).show();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (keyboardView == null) showKeyboard();
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
    title.setText("Floating keyboard  ·  Display " + targetDisplayId);
    title.setTextColor(Color.WHITE);
    title.setTextSize(13);
    titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
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
    params = new WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    params.y = dp(12);
    keyboardView = panel;
    try {
      windowManager.addView(keyboardView, params);
    } catch (Exception e) {
      Toast.makeText(this, "Could not show floating keyboard: " + e.getMessage(), Toast.LENGTH_LONG).show();
      stopSelf();
    }
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
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), weight);
    lp.setMargins(dp(2), dp(2), dp(2), dp(2));
    row.addView(button, lp);
    button.setOnClickListener(v -> action.run());
  }

  private void sendText(String text) {
    try {
      android.view.InputDevice device = android.view.InputDevice.getDevice(KeyCharacterMap.VIRTUAL_KEYBOARD);
      KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
      if (events == null) return;
      for (KeyEvent event : events) inject(event);
    } catch (Throwable e) {
      Toast.makeText(this, "Input failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
    }
  }

  private void sendKey(int keyCode, boolean shift) {
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
    } finally {
      event.recycle();
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
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) { return null; }
}
