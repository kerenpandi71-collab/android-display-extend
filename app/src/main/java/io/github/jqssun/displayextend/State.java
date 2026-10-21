package io.github.jqssun.displayextend;

import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.IBinder;
import android.util.Log;
import android.view.Display;
import androidx.lifecycle.MutableLiveData;
import io.github.jqssun.displayextend.job.Job;
import io.github.jqssun.displayextend.job.YieldException;
import io.github.jqssun.displayextend.shizuku.IUserService;
import io.github.jqssun.displayextend.shizuku.ServiceUtils;
import io.github.jqssun.displayextend.shizuku.ShizukuUtils;
import io.github.jqssun.displayextend.shizuku.UserService;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import rikka.shizuku.Shizuku;

public class State {
  public static final class RunningTaskEntry {
    public final int taskId;
    public final int displayId;
    public final String packageName;
    public final long lastActiveTime;
    public final int visibilityScore;

    public RunningTaskEntry(
        int taskId, int displayId, String packageName, long lastActiveTime, int visibilityScore) {
      this.taskId = taskId;
      this.displayId = displayId;
      this.packageName = packageName;
      this.lastActiveTime = lastActiveTime;
      this.visibilityScore = visibilityScore;
    }
  }

  public static WeakReference<Activity> currentActivity = new WeakReference<>(null);
  private static Job currentJob;
  public static List<String> logs = Collections.synchronizedList(new ArrayList<>());
  public static final MutableLiveData<ExtendUiState> uiState =
      new MutableLiveData<>(new ExtendUiState());
  private static final java.util.concurrent.atomic.AtomicInteger _logVersion =
      new java.util.concurrent.atomic.AtomicInteger(0);
  public static final MutableLiveData<Integer> logVersion = new MutableLiveData<>(0);
  private static MediaProjection mediaProjection;
  public static MediaProjection mediaProjectionInUse;
  public static int lastSingleAppDisplay;
  public static volatile IUserService userService;
  public static VirtualDisplay managedVirtualDisplay;
  public static int managedVirtualDisplayHostDisplayId = -1;
  public static FloatingButtonService floatingButtonService;
  public static Activity isInPureBlackActivity = null;

  private static final android.os.Handler mainHandler =
      new android.os.Handler(android.os.Looper.getMainLooper());

  private static final Object performanceLock = new Object();
  private static final StringBuilder performanceBuffer = new StringBuilder();
  private static java.util.concurrent.ScheduledExecutorService performanceExecutor;
  private static volatile boolean performanceRecording;
  private static long lastCursorSampleMs;
  private static long cursorSampleCount;
  private static long cursorIntervalSumMs;
  private static long cursorSlowIntervals;
  private static long cursorProcessingSumUs;
  private static long cursorMaxProcessingUs;
  private static final int MAX_PERFORMANCE_LOG_CHARS = 700_000;

  public static boolean isPerformanceRecording() {
    return performanceRecording;
  }

  public static void startPerformanceMonitor(Context context) {
    synchronized (performanceLock) {
      if (performanceRecording) return;
      performanceRecording = true;
      performanceBuffer.setLength(0);
      lastCursorSampleMs = 0;
      cursorSampleCount = cursorIntervalSumMs = cursorSlowIntervals = 0;
      cursorProcessingSumUs = cursorMaxProcessingUs = 0;
      appendPerformance("=== VIRTUAL DISPLAY PERFORMANCE RECORDING START ===");
      appendPerformance("time=" + new java.util.Date());
      appendPerformance("device=" + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
          + " android=" + android.os.Build.VERSION.RELEASE + " api=" + android.os.Build.VERSION.SDK_INT);
      appendPerformance("Sampling system CPU/memory/display every 10 seconds; cursor movement samples are event-based.");
      performanceExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "extend-performance-monitor");
        t.setDaemon(true);
        return t;
      });
      final String packageName = context.getPackageName();
      performanceExecutor.scheduleAtFixedRate(() -> {
        if (!performanceRecording) return;
        long start = android.os.SystemClock.elapsedRealtime();
        String command = "echo '--- SAMPLE " + new java.util.Date() + " ---'; "
            + "dumpsys cpuinfo | head -n 15; "
            + "dumpsys meminfo " + packageName + " | head -n 22; "
            + "dumpsys display | grep -E 'DisplayDeviceInfo|mDisplayId|modeId|refreshRate' | head -n 30";
        try {
          Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
          boolean finished = p.waitFor(7, java.util.concurrent.TimeUnit.SECONDS);
          if (!finished) {
            p.destroyForcibly();
            appendPerformance("SYSTEM_SAMPLE timeout after 7s");
            return;
          }
          StringBuilder sample = new StringBuilder();
          try (java.io.BufferedReader reader = new java.io.BufferedReader(
              new java.io.InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null && sample.length() < 10000) {
              sample.append(line).append('\n');
            }
          }
          appendPerformance("SYSTEM_SAMPLE elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - start)
              + " exit=" + p.exitValue() + "\n" + sample);
        } catch (Throwable e) {
          appendPerformance("SYSTEM_SAMPLE error=" + e);
        }
      }, 0, 10, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  public static void recordCursorMovement(int displayId, float dx, float dy, long processingUs) {
    if (!performanceRecording) return;
    long now = android.os.SystemClock.elapsedRealtime();
    synchronized (performanceLock) {
      long interval = lastCursorSampleMs == 0 ? 0 : now - lastCursorSampleMs;
      lastCursorSampleMs = now;
      cursorSampleCount++;
      cursorIntervalSumMs += interval;
      if (interval > 24) cursorSlowIntervals++;
      cursorProcessingSumUs += Math.max(0, processingUs);
      cursorMaxProcessingUs = Math.max(cursorMaxProcessingUs, processingUs);
      appendPerformance("CURSOR tMs=" + now + " display=" + displayId
          + " dx=" + String.format(java.util.Locale.US, "%.2f", dx)
          + " dy=" + String.format(java.util.Locale.US, "%.2f", dy)
          + " eventIntervalMs=" + interval + " processingUs=" + processingUs);
    }
  }

  public static String stopPerformanceMonitor() {
    synchronized (performanceLock) {
      performanceRecording = false;
      if (performanceExecutor != null) {
        performanceExecutor.shutdownNow();
        performanceExecutor = null;
      }
      long n = cursorSampleCount;
      appendPerformance("=== SUMMARY ===");
      appendPerformance("cursorSamples=" + n
          + " averageEventIntervalMs=" + (n > 1 ? (double) cursorIntervalSumMs / (n - 1) : 0)
          + " intervalsOver24ms=" + cursorSlowIntervals
          + " averageProcessingUs=" + (n > 0 ? (double) cursorProcessingSumUs / n : 0)
          + " maxProcessingUs=" + cursorMaxProcessingUs);
      appendPerformance("=== RECORDING STOP === time=" + new java.util.Date());
      return performanceBuffer.toString();
    }
  }

  private static void appendPerformance(String line) {
    synchronized (performanceLock) {
      if (performanceBuffer.length() > MAX_PERFORMANCE_LOG_CHARS) {
        performanceBuffer.delete(0, performanceBuffer.length() / 3);
        performanceBuffer.insert(0, "[older samples trimmed to limit memory usage]\n");
      }
      performanceBuffer.append(line).append('\n');
    }
  }

  public static final ServiceConnection userServiceConnection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
          State.log("user service connected");
          State.userService = IUserService.Stub.asInterface(binder);
          if (State.currentActivity.get() != null) {
            State.currentActivity.get().runOnUiThread(() -> State.resumeJob());
          }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
          State.log("user service disconnected");
          State.userService = null;
        }

        @Override
        public void onBindingDied(ComponentName componentName) {
          State.log("user service binding died");
          State.userService = null;
        }
      };

  public static Shizuku.UserServiceArgs userServiceArgs =
      new Shizuku.UserServiceArgs(
              new ComponentName(BuildConfig.APPLICATION_ID, UserService.class.getName()))
          .daemon(true)
          .tag("extend")
          .processNameSuffix("extend")
          .debuggable(false)
          .version(BuildConfig.VERSION_CODE);

  public static void startNewJob(Job job) {
    if (currentJob != null) {
      if (currentActivity.get() != null) {
        State.log("job already running: " + currentJob.getClass().getSimpleName());
      }
      return;
    }
    currentJob = job;
    try {
      State.log("starting job: " + job.getClass().getSimpleName());
      currentJob.start();
      State.log("job completed: " + job.getClass().getSimpleName());
      currentJob = null;
    } catch (YieldException e) {
      State.log("job yielded: " + job.getClass().getSimpleName() + ", " + e.getMessage());
    } catch (RuntimeException e) {
      State.log("job failed: " + job.getClass().getSimpleName());
      String stackTrace = Log.getStackTraceString(e);
      State.log("stacktrace: " + stackTrace);
      currentJob = null;
    }
    refreshUI();
  }

  public static void resumeJob() {
    if (currentJob == null) {
      refreshUI();
      return;
    }
    try {
      State.log("resuming job: " + currentJob.getClass().getSimpleName());
      currentJob.start();
      State.log("job completed: " + currentJob.getClass().getSimpleName());
      currentJob = null;
    } catch (YieldException e) {
      State.log("job yielded: " + currentJob.getClass().getSimpleName() + ", " + e.getMessage());
    } catch (RuntimeException e) {
      State.log("job resume failed: " + currentJob.getClass().getSimpleName());
      String stackTrace = Log.getStackTraceString(e);
      State.log("stacktrace: " + stackTrace);
      currentJob = null;
    }
    refreshUI();
  }

  public static void resumeJobLater(long delayMillis) {
    if (currentActivity.get() != null) {
      mainHandler.postDelayed(() -> resumeJob(), delayMillis);
    }
  }

  public static void log(String message) {
    logs.add(message);
    Log.i("Extend", message);
    logVersion.postValue(_logVersion.incrementAndGet());
  }

  public static void refreshUI() {
    Activity activity = currentActivity.get();
    if (activity != null) {
      activity.runOnUiThread(() -> _updateUiState(activity));
    }
  }

  private static void _updateUiState(Activity activity) {
    ExtendUiState state = new ExtendUiState();
    boolean started = io.github.jqssun.displayextend.shizuku.ShizukuUtils.hasShizukuStarted();
    boolean hasPerm = io.github.jqssun.displayextend.shizuku.ShizukuUtils.hasPermission();
    if (!started) {
      state.shizukuStatus = activity.getString(R.string.status_not_started);
      state.shizukuPermissionVisible = false;
    } else if (!hasPerm) {
      state.shizukuStatus =
          activity.getString(
              R.string.status_started_not_authorized_server,
              io.github.jqssun.displayextend.shizuku.ShizukuUtils.getServerUid());
      state.shizukuPermissionVisible = true;
    } else {
      state.shizukuStatus =
          activity.getString(
              R.string.status_authorized_server,
              io.github.jqssun.displayextend.shizuku.ShizukuUtils.getServerUid());
      state.shizukuPermissionVisible = false;
    }

    uiState.setValue(state);
  }

  public static void unbindUserService() {
    if (userService != null) {
      Shizuku.unbindUserService(State.userServiceArgs, userServiceConnection, true);
    }
  }

  public static MediaProjection getMediaProjection() {
    return mediaProjection;
  }

  public static void setMediaProjection(MediaProjection newMediaProjection) {
    if (newMediaProjection == null) {
      mediaProjection = null;
    } else {
      mediaProjection = newMediaProjection;
      mediaProjectionInUse = newMediaProjection;
    }
  }

  public static int getManagedVirtualDisplayId() {
    if (managedVirtualDisplay == null) return -1;
    return managedVirtualDisplay.getDisplay().getDisplayId();
  }

  public static void reconcileLastSingleAppDisplay(Context context) {
    if (context == null || !ShizukuUtils.hasPermission()) {
      return;
    }
    int resolvedDisplayId = 0;
    long bestLastActiveTime = Long.MIN_VALUE;
    int bestScore = Integer.MIN_VALUE;
    DisplayManager displayManager =
        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
    if (displayManager == null) {
      return;
    }
    for (Display display : displayManager.getDisplays()) {
      int displayId = display.getDisplayId();
      if (displayId == Display.DEFAULT_DISPLAY) {
        continue;
      }
      for (RunningTaskEntry task : getRunningTasksOnDisplay(context, displayId)) {
        if (task.visibilityScore > bestScore
            || (task.visibilityScore == bestScore && task.lastActiveTime > bestLastActiveTime)) {
          bestScore = task.visibilityScore;
          bestLastActiveTime = task.lastActiveTime;
          resolvedDisplayId = displayId;
        }
      }
    }

    if (lastSingleAppDisplay != resolvedDisplayId) {
      State.log(
          "reconciled selected app display from " + lastSingleAppDisplay + " to " + resolvedDisplayId);
      lastSingleAppDisplay = resolvedDisplayId;
    }
  }

  public static List<RunningTaskEntry> getRunningTasksOnDisplay(Context context, int displayId) {
    if (context == null || displayId == Display.DEFAULT_DISPLAY || !ShizukuUtils.hasPermission()) {
      return Collections.emptyList();
    }
    List<RunningTaskEntry> tasks = new ArrayList<>();
    try {
      List<ActivityTaskManager.RootTaskInfo> taskInfos =
          ServiceUtils.getActivityTaskManager().getAllRootTaskInfosOnDisplay(displayId);
      for (ActivityTaskManager.RootTaskInfo taskInfo : taskInfos) {
        if (!_isRunningTaskCandidate(taskInfo, context.getPackageName())) {
          continue;
        }
        String packageName = _taskPackageName(taskInfo);
        if (packageName == null) {
          continue;
        }
        tasks.add(
            new RunningTaskEntry(
                TaskInfoCompat.getTaskId(taskInfo),
                TaskInfoCompat.getDisplayId(taskInfo),
                packageName,
                TaskInfoCompat.getLastActiveTime(taskInfo),
                _taskVisibilityScore(taskInfo)));
      }
    } catch (Throwable e) {
      State.log("failed to inspect display " + displayId + " tasks: " + e.getMessage());
    }
    tasks.sort(
        (left, right) -> {
          int scoreCompare = Integer.compare(right.visibilityScore, left.visibilityScore);
          if (scoreCompare != 0) {
            return scoreCompare;
          }
          int timeCompare = Long.compare(right.lastActiveTime, left.lastActiveTime);
          if (timeCompare != 0) {
            return timeCompare;
          }
          return left.packageName.compareToIgnoreCase(right.packageName);
        });
    return tasks;
  }

  private static boolean _isRunningTaskCandidate(
      ActivityTaskManager.RootTaskInfo taskInfo, String ownPackageName) {
    if (taskInfo == null || !TaskInfoCompat.isRunning(taskInfo)) {
      return false;
    }
    if (TaskInfoCompat.getDisplayId(taskInfo) == Display.DEFAULT_DISPLAY) {
      return false;
    }
    if (TaskInfoCompat.getActivityType(taskInfo) != WindowConfiguration.ACTIVITY_TYPE_STANDARD) {
      return false;
    }
    if (!(TaskInfoCompat.isFocused(taskInfo)
        || TaskInfoCompat.isVisible(taskInfo)
        || TaskInfoCompat.isVisibleRequested(taskInfo))) {
      return false;
    }
    String packageName = _taskPackageName(taskInfo);
    return packageName != null && !ownPackageName.equals(packageName);
  }

  private static int _taskVisibilityScore(ActivityTaskManager.RootTaskInfo taskInfo) {
    int score = 0;
    if (TaskInfoCompat.isVisibleRequested(taskInfo)) {
      score += 1;
    }
    if (TaskInfoCompat.isVisible(taskInfo)) {
      score += 2;
    }
    if (TaskInfoCompat.isFocused(taskInfo)) {
      score += 4;
    }
    return score;
  }

  private static String _taskPackageName(ActivityTaskManager.RootTaskInfo taskInfo) {
    return TaskInfoCompat.getPackageName(taskInfo);
  }
}
