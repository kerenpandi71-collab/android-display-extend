package io.github.jqssun.displayextend;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.Navigation;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.transition.MaterialSharedAxis;
import io.github.jqssun.displayextend.job.AcquireShizuku;
import io.github.jqssun.displayextend.shizuku.ShizukuUtils;
import java.util.ArrayList;
import java.util.List;

public class OverviewFragment extends Fragment {
  private TextView shizukuStatus;
  private MaterialButton shizukuPermissionBtn;
  private TextView accessibilityStatus;
  private MaterialButton accessibilityPermissionBtn;
  private TextView inputBindingStatus;
  private LinearLayout displaysContainer;
  private TextView permissionsStatusTitle;
  private TextView permissionsStatusDetail;
  private ImageView permissionsStatusIcon;

  private final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
          _refreshDisplayList();
        }

        @Override
        public void onDisplayRemoved(int id) {
          _refreshDisplayList();
        }

        @Override
        public void onDisplayChanged(int id) {
          _refreshDisplayList();
        }
      };

  @Override
  public void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.X, true));
    setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.X, false));
  }

  @Override
  public View onCreateView(
      LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    View view = inflater.inflate(R.layout.fragment_overview, container, false);

    TextView shizukuStatusPrefix = view.findViewById(R.id.shizuku_status_prefix);
    shizukuStatusPrefix.setOnClickListener(
        v ->
            startActivity(
                new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/rikkaapps/shizuku"))));

    TextView rootAccessStatus = view.findViewById(R.id.root_access_status);
    MaterialButton rootAccessButton = view.findViewById(R.id.root_access_button);
    MaterialButton advancedToolsToggle = view.findViewById(R.id.advanced_tools_toggle);
    View rootAccessCard = view.findViewById(R.id.root_access_card);
    advancedToolsToggle.setOnClickListener(
        v -> {
          boolean show = rootAccessCard.getVisibility() != View.VISIBLE;
          rootAccessCard.setVisibility(show ? View.VISIBLE : View.GONE);
          advancedToolsToggle.setText(
              show ? "Sembunyikan alat lanjutan" : "Alat lanjutan: periksa akses root");
        });
    rootAccessButton.setOnClickListener(
        v -> {
          rootAccessButton.setEnabled(false);
          rootAccessStatus.setText(R.string.root_access_waiting);
          new Thread(
                  () -> {
                    RootAccess.Result result = RootAccess.check();
                    if (!isAdded()) return;
                    requireActivity().runOnUiThread(
                        () -> {
                          rootAccessButton.setEnabled(true);
                          if (result.success && result.output.contains("uid=0")) {
                            rootAccessStatus.setText(R.string.root_access_granted);
                            rootAccessButton.setText(R.string.root_access_ready);
                          } else {
                            rootAccessStatus.setText(R.string.root_access_unavailable);
                            rootAccessButton.setText(R.string.root_access_check_again);
                          }
                        });
                  })
              .start();
        });

    view.findViewById(R.id.overlay_display_manager_btn).setOnClickListener(v -> startActivity(new Intent(requireContext(), OverlayDisplayActivity.class)));

    shizukuPermissionBtn = view.findViewById(R.id.shizuku_permission_btn);
    shizukuPermissionBtn.setOnClickListener(v -> State.startNewJob(new AcquireShizuku()));

    shizukuStatus = view.findViewById(R.id.shizuku_status);
    accessibilityStatus = view.findViewById(R.id.accessibility_status);
    accessibilityPermissionBtn = view.findViewById(R.id.accessibility_permission_btn);
    accessibilityPermissionBtn.setOnClickListener(
        v -> {
          TouchpadAccessibilityService.ensureServiceAvailable(requireContext(), true);
          _updateAccessibilityStatus();
          _updatePermissionsCard();
        });
    inputBindingStatus = view.findViewById(R.id.input_binding_status);
    displaysContainer = view.findViewById(R.id.displays_container);
    permissionsStatusTitle = view.findViewById(R.id.permissions_status_title);
    permissionsStatusDetail = view.findViewById(R.id.permissions_status_detail);
    permissionsStatusIcon = view.findViewById(R.id.permissions_status_icon);

    view.findViewById(R.id.screen_off_button)
        .setOnClickListener(
            v -> {
              Intent intent = new Intent(requireContext(), PureBlackActivity.class);
              startActivity(intent);
            });

    view.findViewById(R.id.input_binding_row)
        .setOnClickListener(
            v -> Navigation.findNavController(v).navigate(R.id.action_overview_to_input_binding));

    State.uiState.observe(getViewLifecycleOwner(), this::_updateUI);

    return view;
  }

  @Override
  public void onResume() {
    super.onResume();
    Context ctx = getContext();
    if (ctx != null) {
      ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
          .registerDisplayListener(displayListener, null);
    }
    State.refreshUI();
    _refreshDisplayList();
    _updateInputBindingStatus();
  }

  @Override
  public void onPause() {
    super.onPause();
    Context ctx = getContext();
    if (ctx != null) {
      ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
          .unregisterDisplayListener(displayListener);
    }
  }

  private void _updateUI(ExtendUiState state) {
    if (state == null) return;

    if (state.shizukuStatus != null) {
      shizukuStatus.setText(state.shizukuStatus);
    }
    shizukuPermissionBtn.setVisibility(state.shizukuPermissionVisible ? View.VISIBLE : View.GONE);
    _updateAccessibilityStatus();
    _updatePermissionsCard();

    _refreshDisplayList();
    _updateInputBindingStatus();
  }

  private void _updatePermissionsCard() {
    if (permissionsStatusTitle == null || getContext() == null) return;
    boolean granted =
        ShizukuUtils.hasShizukuStarted()
            && ShizukuUtils.hasPermission()
            && TouchpadAccessibilityService.isAccessibilityServiceEnabled(requireContext());
    permissionsStatusTitle.setText(
        granted ? R.string.permissions_granted : R.string.permissions_not_granted);
    permissionsStatusDetail.setText(
        granted ? R.string.permissions_granted_detail : R.string.permissions_not_granted_detail);
    permissionsStatusIcon.setImageResource(
        granted ? R.drawable.ic_check_circle : R.drawable.ic_error);
  }

  private void _updateAccessibilityStatus() {
    if (accessibilityStatus == null || accessibilityPermissionBtn == null || getContext() == null) {
      return;
    }
    boolean enabled = TouchpadAccessibilityService.isAccessibilityServiceEnabled(requireContext());
    if (enabled) {
      accessibilityStatus.setText(R.string.accessibility_status_enabled);
      accessibilityPermissionBtn.setVisibility(View.GONE);
      return;
    }
    accessibilityStatus.setText(
        Pref.getTouchpadAccessibilityOverlay()
            ? R.string.accessibility_status_required_for_touchpad_surfaces
            : R.string.accessibility_status_optional_for_touchpad_surfaces);
    accessibilityPermissionBtn.setVisibility(View.VISIBLE);
  }

  private void _refreshDisplayList() {
    if (displaysContainer == null || getContext() == null) return;
    DisplayManager dm = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
    Display[] displays = dm.getDisplays();
    List<Display> displayList = new ArrayList<>();
    for (Display d : displays) {
      if (d.getDisplayId() != State.managedVirtualDisplayHostDisplayId
) {
        displayList.add(d);
      }
    }
    displaysContainer.removeAllViews();
    LayoutInflater inflater = LayoutInflater.from(displaysContainer.getContext());
    for (Display display : displayList) {
      displaysContainer.addView(_createDisplayRow(inflater, displaysContainer, display));
    }
  }

  private void _updateInputBindingStatus() {
    if (inputBindingStatus == null) return;
    inputBindingStatus.setText(
        Pref.getAutoBindInput()
            ? R.string.input_binding_status_auto
            : R.string.input_binding_status_manual);
  }

  private void _onDisplayItemClick(Display display) {
    if (getView() != null) {
      Bundle args = new Bundle();
      args.putInt("display_id", display.getDisplayId());
      Navigation.findNavController(getView())
          .navigate(R.id.action_overview_to_display_detail, args);
    }
  }

  private View _createDisplayRow(LayoutInflater inflater, ViewGroup parent, Display display) {
    View row = inflater.inflate(R.layout.item_display, parent, false);
    TextView displayId = row.findViewById(R.id.display_id);
    TextView displayName = row.findViewById(R.id.display_name);
    ImageView displayIcon = row.findViewById(R.id.display_icon);

    String displayInfo =
        String.format(
            requireContext().getString(R.string.display_id_format),
            display.getDisplayId(),
            display.getWidth(),
            display.getHeight());
    displayId.setText(displayInfo);
    displayName.setText(
        requireContext().getString(R.string.display_name_format, display.getName()));
    displayIcon.setImageResource(
        display.getDisplayId() == Display.DEFAULT_DISPLAY
            ? R.drawable.ic_builtin_display
            : R.drawable.ic_screens);
    row.setOnClickListener(v -> _onDisplayItemClick(display));
    return row;
  }
}
