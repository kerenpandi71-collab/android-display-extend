package io.github.jqssun.displayextend.dialog;

import android.content.Context;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import io.github.jqssun.displayextend.TvFocus;
import io.github.jqssun.displayextend.Pref;
import io.github.jqssun.displayextend.R;
import io.github.jqssun.displayextend.State;
import io.github.jqssun.displayextend.job.PresentManagedVirtualDisplay;
import io.github.jqssun.displayextend.job.VirtualDisplayArgs;
import io.github.jqssun.displayextend.shizuku.ServiceUtils;

public class ManagedVirtualDisplayDialog {
  public static void show(Context context, Display display, int displayId) {
    View dialogView =
        LayoutInflater.from(context).inflate(R.layout.dialog_managed_virtual_display, null);

    MaterialCheckBox rotatesWithContentCheckbox =
        dialogView.findViewById(R.id.rotates_with_content_checkbox);
    MaterialCheckBox skipScreenCaptureCheckbox =
        dialogView.findViewById(R.id.skip_screen_capture_checkbox);
    MaterialCheckBox autoManagedVirtualDisplayCheckbox =
        dialogView.findViewById(R.id.auto_managed_virtual_display_checkbox);

    rotatesWithContentCheckbox.setChecked(Pref.getRotatesWithContent());
    skipScreenCaptureCheckbox.setChecked(Pref.getSkipScreenCapturePermission());
    autoManagedVirtualDisplayCheckbox.setChecked(
        Pref.getAutoManagedVirtualDisplay(display.getName()));

    Point initialSize = new Point();
    ServiceUtils.getWindowManager().getInitialDisplaySize(displayId, initialSize);
    android.util.DisplayMetrics initialMetrics = new android.util.DisplayMetrics();
    display.getMetrics(initialMetrics);
    com.google.android.material.textfield.TextInputEditText widthInput =
        dialogView.findViewById(R.id.virtual_display_width);
    com.google.android.material.textfield.TextInputEditText heightInput =
        dialogView.findViewById(R.id.virtual_display_height);
    com.google.android.material.textfield.TextInputEditText dpiInput =
        dialogView.findViewById(R.id.virtual_display_dpi);
    widthInput.setText(String.valueOf(initialSize.x));
    heightInput.setText(String.valueOf(initialSize.y));
    dpiInput.setText(String.valueOf(initialMetrics.densityDpi));
    TvFocus.attach(new MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.managed_virtual_display_settings))
        .setView(dialogView)
        .setPositiveButton(
            context.getString(R.string.ok),
            (dialog, which) -> {
              Pref.setRotatesWithContent(rotatesWithContentCheckbox.isChecked());
              Pref.setSkipScreenCapturePermission(skipScreenCaptureCheckbox.isChecked());
              boolean autoManagedVirtualDisplay = autoManagedVirtualDisplayCheckbox.isChecked();
              Pref.setAutoManagedVirtualDisplay(display.getName(), autoManagedVirtualDisplay);
              if (autoManagedVirtualDisplay) {
                Pref.setAutoOpenLastApp(display.getName(), false);
              }

              int width;
              int height;
              int dpi;
              try {
                width = Integer.parseInt(widthInput.getText().toString().trim());
                height = Integer.parseInt(heightInput.getText().toString().trim());
                dpi = Integer.parseInt(dpiInput.getText().toString().trim());
              } catch (NumberFormatException e) {
                android.widget.Toast.makeText(context, "Enter valid width, height, and DPI values.", android.widget.Toast.LENGTH_LONG).show();
                return;
              }
              if (width < 320 || width > 3840 || height < 320 || height > 3840 || dpi < 80 || dpi > 640) {
                android.widget.Toast.makeText(context, "Use width/height 320–3840 and DPI 80–640.", android.widget.Toast.LENGTH_LONG).show();
                return;
              }
              State.startNewJob(
                  new PresentManagedVirtualDisplay(
                      display,
                      new VirtualDisplayArgs(
                          context.getString(R.string.managed_virtual_display_name),
                          width,
                          height,
                          (int) display.getRefreshRate(),
                          dpi,
                          Pref.getRotatesWithContent())));
            })
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show());
  }
}
