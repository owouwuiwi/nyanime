package nyanime.qa;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.SparseArray;
import java.util.List;
import java.io.File;
import java.io.FileOutputStream;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;

/** Separate self-targeted UI probe: no app internals, source names or private database access. */
public final class PlayerInputDeviceProbe extends Instrumentation {
    private Bundle arguments;
    private UiAutomation automation;
    private MediaController controller;
    private String targetPackage;
    private int width, height;
    private int videoTop, videoHeight;
    private long pointerDown;
    private float pointerX, pointerY;
    private final JSONObject report = new JSONObject();

    @Override public void onCreate(Bundle arguments) {
        this.arguments = arguments == null ? new Bundle() : arguments;
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            automation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            AccessibilityServiceInfo info = automation.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            automation.setServiceInfo(info);
            String target = arguments.getString("applicationId");
            targetPackage = target;
            if (target == null) throw new IllegalArgumentException("applicationId is required");
            MediaSessionManager manager = (MediaSessionManager)getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            String sessionTag = arguments.getString("sessionTag");
            int matchingSessions = 0;
            for (MediaController item : manager.getActiveSessions(null)) {
                if (target.equals(item.getPackageName()) && (sessionTag == null || sessionTag.equals(item.getTag()))) {
                    controller = item;
                    matchingSessions++;
                }
            }
            if (matchingSessions > 1) throw new IllegalStateException("Multiple media sessions; pass sessionTag explicitly");
            if (controller == null) throw new IllegalStateException("Open a video first");
            report.put("mediaSessionTag", controller.getTag());
            Bitmap screenshot = automation.takeScreenshot();
            width = screenshot.getWidth(); height = screenshot.getHeight(); screenshot.recycle();
            videoTop = Integer.parseInt(arguments.getString("videoTop", "0"));
            videoHeight = Integer.parseInt(arguments.getString("videoHeight", Integer.toString(height)));
            if (width <= height && !"tap".equals(arguments.getString("mode"))) throw new IllegalStateException("Start matrix with fullscreen landscape video");
            if (controlsVisible()) tap();
            SystemClock.sleep(250);
            tap();
            long deadline = SystemClock.uptimeMillis() + 1500;
            while (!controlsVisible() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(60);
            boolean visible = controlsVisible();
            report.put("tapShowsControls", visible);
            if (!visible) throw new AssertionError("Tap did not show player controls");
            if ("diagonalScrub".equals(arguments.getString("mode"))) {
                report.put("diagonalScrub", diagonalScrub());
            } else if ("doubleTap".equals(arguments.getString("mode"))) {
                report.put("doubleTap", doubleTapFeedback());
            } else if ("accessibility".equals(arguments.getString("mode"))) {
                report.put("accessibilitySeek", accessibilitySeek());
            } else if (!"tap".equals(arguments.getString("mode", "matrix"))) {
                JSONArray cases = new JSONArray();
                report.put("cases", cases);
                boolean timelineCancel = "timelineCancel".equals(arguments.getString("mode"));
                cases.put(exercise(Boolean.parseBoolean(arguments.getString("initialPaused", "false")), timelineCancel));
                if (!"shared".equals(arguments.getString("mode")) && !timelineCancel) {
                    cases.put(exercise(true, false));
                    cases.put(exercise(false, true));
                    report.put("explicitPauseWins", explicitPause());
                    report.put("minimalGestureKeepsPosition", minimalGesture());
                }
                if (controlsVisible()) tap();
                tap();
                report.put("controlsStillRespond", controlsVisible());
                if (!controlsVisible()) throw new AssertionError("Controls stopped responding after consecutive gestures");
                report.put("cases", cases);
            }
            report.put("result", "PASS"); code = Activity.RESULT_OK;
        } catch (Throwable error) {
            try { report.put("result", "FAIL").put("error", error.getClass().getSimpleName()).put("reason", error.getMessage()); }
            catch (Exception ignored) { }
        } finally {
            if (pointerDown != 0) input(MotionEvent.ACTION_CANCEL, pointerX, pointerY);
            if (controller != null) controller.getTransportControls().pause();
            if (automation != null) automation.dropShellPermissionIdentity();
            result.putString("stream", report.toString() + "\n");
            finish(code, result);
        }
    }

    private JSONObject exercise(boolean paused, boolean cancel) throws Exception {
        if (paused) controller.getTransportControls().pause(); else controller.getTransportControls().play();
        waitState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING);
        boolean timeline = "timelineCancel".equals(arguments.getString("mode"));
        if (timeline ? !controlsVisible() : controlsVisible()) tap();
        SystemClock.sleep(300);
        long before = state().getPosition();
        long startedAt = System.currentTimeMillis();
        float x = width * .30f, y = height * .45f;
        if (timeline) {
            AccessibilityNodeInfo bar = seekBar(targetRoot());
            if (bar == null) throw new IllegalStateException("Show the expanded timeline first");
            Rect bounds = new Rect(); bar.getBoundsInScreen(bounds);
            AccessibilityNodeInfo.RangeInfo range = bar.getRangeInfo();
            // Keep the preview far from the current position, so a wrong commit cannot pass by coincidence.
            boolean pastMiddle = range != null && range.getCurrent() > (range.getMin() + range.getMax()) / 2;
            x = bounds.left + bounds.width() * (pastMiddle ? .08f : .60f);
            y = bounds.centerY();
        }
        pointerDown = SystemClock.uptimeMillis(); input(MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 16; i++) {
            SystemClock.sleep(70);
            input(MotionEvent.ACTION_MOVE, x + width * .15f * i / 16, y);
        }
        SystemClock.sleep(180);
        PlaybackState first = state();
        boolean readout = contains(targetRoot(), "Vai a ", true) ||
            contains(targetRoot(), "Seek to ", true);
        SystemClock.sleep(Integer.parseInt(arguments.getString("holdMillis", "350")));
        PlaybackState second = state();
        long heldUntil = System.currentTimeMillis();
        boolean held = first.getState() == PlaybackState.STATE_PAUSED && second.getState() == PlaybackState.STATE_PAUSED &&
            Math.abs(first.getPosition() - second.getPosition()) <= 50;
        input(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = 0;
        waitState(paused ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_PLAYING);
        SystemClock.sleep(450);
        long after = state().getPosition();
        long settled = SystemClock.uptimeMillis() + 7000;
        while (!cancel && after <= before + 5000 && SystemClock.uptimeMillis() < settled) {
            SystemClock.sleep(120); after = state().getPosition();
        }
        boolean position = cancel ? Math.abs(after - before) < 3000 : after > before + 5000;
        JSONObject item = new JSONObject().put("startedPaused", paused).put("cancelled", cancel)
            .put("beforeMs", before).put("held1Ms", first.getPosition()).put("held2Ms", second.getPosition())
            .put("startedAt", startedAt).put("heldUntil", heldUntil)
            .put("afterMs", after).put("nativeHold", held).put("readoutVisible", readout).put("positionCorrect", position);
        if (!held || !readout || !position) throw new AssertionError("Scrub case failed: " + item.toString());
        return item;
    }

    private PlaybackState state() {
        PlaybackState state = controller.getPlaybackState();
        if (state == null) throw new IllegalStateException("Playback state missing");
        return state;
    }
    private JSONObject diagonalScrub() throws Exception {
        controller.getTransportControls().pause(); waitState(PlaybackState.STATE_PAUSED);
        if (controlsVisible()) tap();
        long before = state().getPosition();
        int horizontalDirection = Integer.parseInt(arguments.getString("horizontalDirection", "1"));
        int verticalDirection = Integer.parseInt(arguments.getString("verticalDirection", "1"));
        if (Math.abs(horizontalDirection) != 1 || Math.abs(verticalDirection) != 1)
            throw new IllegalArgumentException("Directions must be 1 or -1");
        float x = width * .50f, y = height * (verticalDirection > 0 ? .23f : .75f);
        float deltaX = width * .08f * horizontalDirection;
        float deltaY = height * .55f * verticalDirection;
        pointerDown = SystemClock.uptimeMillis(); input(MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 8; i++) {
            SystemClock.sleep(25); input(MotionEvent.ACTION_MOVE, x + deltaX * i / 8, y);
        }
        SystemClock.sleep(200);
        boolean previewBefore = contains(targetRoot(), "Vai a ", true) || contains(targetRoot(), "Seek to ", true);
        for (int i = 1; i <= 12; i++) {
            SystemClock.sleep(25); input(MotionEvent.ACTION_MOVE, x + deltaX, y + deltaY * i / 12);
        }
        SystemClock.sleep(180);
        boolean previewAfter = contains(targetRoot(), "Vai a ", true) || contains(targetRoot(), "Seek to ", true);
        PlaybackState held = state();
        Bitmap screenshot = automation.takeScreenshot();
        File file = new File(getTargetContext().getCacheDir(), "diagonal-scrub.png");
        boolean sameViewport = screenshot.getWidth() == width && screenshot.getHeight() == height;
        try (FileOutputStream output = new FileOutputStream(file)) { screenshot.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { screenshot.recycle(); }
        input(MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = 0;
        waitState(PlaybackState.STATE_PAUSED); SystemClock.sleep(500);
        boolean heldPosition = held.getState() == PlaybackState.STATE_PAUSED && Math.abs(held.getPosition() - before) < 100;
        JSONObject item = new JSONObject().put("horizontalDirection", horizontalDirection).put("verticalDirection", verticalDirection)
            .put("previewBeforeDrift", previewBefore).put("previewAfterDrift", previewAfter)
            .put("nativePositionHeld", heldPosition).put("sameViewport", sameViewport).put("beforeMs", before)
            .put("afterMs", state().getPosition()).put("screenshot", file.getAbsolutePath());
        report.put("diagonalScrub", item);
        if (!previewBefore || !previewAfter || !heldPosition || !sameViewport)
            throw new AssertionError("Vertical movement stole the horizontal seek: " + item);
        return item;
    }
    private JSONArray doubleTapFeedback() throws Exception {
        controller.getTransportControls().pause(); waitState(PlaybackState.STATE_PAUSED);
        if (controlsVisible()) tap();
        JSONArray cases = new JSONArray();
        File folder = new File(getTargetContext().getCacheDir(), "double-tap");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IllegalStateException("No screenshot directory");
        for (int pattern = 0; pattern < 4; pattern++) {
            boolean forward = pattern % 2 == 0;
            long before = state().getPosition();
            float x = width * (forward ? .83f : .23f), y = videoTop + videoHeight * .42f;
            touchAt(x, y); SystemClock.sleep(65); touchAt(x, y);
            if (pattern >= 2) {
                SystemClock.sleep(180);
                touchAt(width * (forward ? .23f : .83f), y);
            }
            long start = SystemClock.uptimeMillis();
            JSONArray frames = new JSONArray();
            int[] offsets = {0, 200, 600, 760, 840, 1000, 1400};
            Bitmap[] images = new Bitmap[offsets.length];
            try {
                // Encode after sampling: PNG compression must not delay the dismissal frames.
                for (int i = 0; i < offsets.length; i++) {
                    long wait = start + offsets[i] - SystemClock.uptimeMillis();
                    if (wait > 0) SystemClock.sleep(wait);
                    images[i] = automation.takeScreenshot();
                    if (images[i] == null) throw new IllegalStateException("Screenshot unavailable");
                    frames.put(new JSONObject().put("requestedMs", offsets[i])
                        .put("capturedMs", SystemClock.uptimeMillis() - start)
                        .put("file", "case-" + pattern + "-" + offsets[i] + ".png"));
                }
                for (int i = 0; i < images.length; i++) {
                    try (FileOutputStream output = new FileOutputStream(new File(folder, frames.getJSONObject(i).getString("file")))) {
                        if (!images[i].compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG encoding failed");
                    }
                }
            } finally {
                for (Bitmap image : images) if (image != null) image.recycle();
            }
            waitState(PlaybackState.STATE_PAUSED);
            cases.put(new JSONObject().put("initiallyForward", forward).put("switchDirection", pattern >= 2)
                .put("beforeMs", before).put("afterMs", state().getPosition()).put("pausedAfter", true)
                .put("frames", frames));
        }
        report.put("screenshotDirectory", folder.getAbsolutePath());
        report.put("visualVerdictRequired", true);
        return cases;
    }
    private void touchAt(float x, float y) {
        pointerDown = SystemClock.uptimeMillis(); input(MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(40); input(MotionEvent.ACTION_UP, x, y); pointerDown = 0;
    }
    private JSONObject accessibilitySeek() throws Exception {
        controller.getTransportControls().pause(); waitState(PlaybackState.STATE_PAUSED);
        // Start this independent accessibility case after the previous gesture's native callbacks settle.
        SystemClock.sleep(500); waitState(PlaybackState.STATE_PAUSED);
        AccessibilityNodeInfo bar = seekBar(targetRoot());
        if (bar == null || bar.getRangeInfo() == null) throw new IllegalStateException("Show the expanded timeline first");
        AccessibilityNodeInfo.RangeInfo range = bar.getRangeInfo();
        float fraction = range.getCurrent() < (range.getMin() + range.getMax()) / 2 ? .65f : .20f;
        fraction = Float.parseFloat(arguments.getString("seekFraction", Float.toString(fraction)));
        if (!Float.isFinite(fraction) || fraction < 0 || fraction > 1)
            throw new IllegalArgumentException("seekFraction must be within 0..1");
        Bundle values = new Bundle();
        values.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
            range.getMin() + (range.getMax() - range.getMin()) * fraction);
        long duration = controller.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (duration <= 0) throw new IllegalStateException("Playback duration missing");
        long expected = (long)(duration * fraction);
        boolean accepted = bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), values);
        long deadline = SystemClock.uptimeMillis() + 9000;
        long pausedSince = 0;
        boolean resumed = false;
        JSONArray samples = new JSONArray();
        // Position acknowledgement may precede the native seek/buffering state.
        // Observe restoration instead of treating that transient state as playback.
        // Never accept a resumed frame or an unresolved buffering state as a pause.
        while (SystemClock.uptimeMillis() < deadline) {
            PlaybackState observed = state();
            long now = SystemClock.uptimeMillis();
            samples.put(new JSONObject().put("uptimeMs", now).put("state", observed.getState())
                .put("positionMs", observed.getPosition()));
            resumed |= observed.getState() == PlaybackState.STATE_PLAYING;
            boolean pausedAtTarget = observed.getState() == PlaybackState.STATE_PAUSED &&
                Math.abs(observed.getPosition() - expected) <= 3000;
            if (!pausedAtTarget) pausedSince = 0;
            else if (pausedSince == 0) pausedSince = now;
            else if (now - pausedSince >= 600) break;
            SystemClock.sleep(120);
        }
        JSONObject item = new JSONObject().put("accepted", accepted).put("expectedMs", expected)
            .put("afterMs", state().getPosition()).put("remainedPaused", !resumed &&
                state().getState() == PlaybackState.STATE_PAUSED).put("settledAtTarget", pausedSince != 0 &&
                SystemClock.uptimeMillis() - pausedSince >= 600).put("samples", samples);
        if (!accepted || !item.getBoolean("remainedPaused") || !item.getBoolean("settledAtTarget"))
            throw new AssertionError("Accessibility seek failed: " + item.toString());
        return item;
    }
    private boolean explicitPause() throws Exception {
        controller.getTransportControls().play(); waitState(PlaybackState.STATE_PLAYING);
        if (controlsVisible()) tap();
        long before = state().getPosition();
        float x = width * .30f, y = height * .45f;
        pointerDown = SystemClock.uptimeMillis(); input(MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 8; i++) {
            SystemClock.sleep(60); input(MotionEvent.ACTION_MOVE, x + width * .15f * i / 8, y);
        }
        controller.getTransportControls().pause(); SystemClock.sleep(400);
        input(MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = 0; SystemClock.sleep(450);
        if (state().getState() != PlaybackState.STATE_PAUSED || Math.abs(state().getPosition() - before) > 3000)
            throw new AssertionError("Explicit pause was overridden or a cancelled target was committed");
        return true;
    }
    private boolean minimalGesture() {
        controller.getTransportControls().play(); waitState(PlaybackState.STATE_PLAYING);
        if (controlsVisible()) tap();
        long before = state().getPosition();
        float x = width * .30f, y = height * .45f;
        pointerDown = SystemClock.uptimeMillis(); input(MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(40); input(MotionEvent.ACTION_MOVE, x + 50, y);
        SystemClock.sleep(40); input(MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = 0;
        waitState(PlaybackState.STATE_PLAYING); SystemClock.sleep(300);
        if (Math.abs(state().getPosition() - before) > 3000)
            throw new AssertionError("A minimal gesture inherited an old target");
        return true;
    }
    private void waitState(int expected) {
        long deadline = SystemClock.uptimeMillis() + 9000;
        while (state().getState() != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(80);
        if (state().getState() != expected) throw new AssertionError("Playback state did not restore: " + state().getState());
    }
    private AccessibilityNodeInfo targetRoot() {
        if (android.os.Build.VERSION.SDK_INT >= 33) automation.clearCache();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            SparseArray<List<AccessibilityWindowInfo>> displays = automation.getWindowsOnAllDisplays();
            List<AccessibilityWindowInfo> windows = displays.get(0);
            if (windows != null) for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && root.getPackageName() != null && targetPackage.contentEquals(root.getPackageName())) return root;
            }
        }
        AccessibilityNodeInfo root = automation.getRootInActiveWindow();
        if (root != null) root.refresh();
        return root;
    }
    private boolean controlsVisible() {
        AccessibilityNodeInfo root = targetRoot();
        return control(root, "Pause") || control(root, "Play") || control(root, "Pausa") || control(root, "Riproduci");
    }
    private boolean control(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        CharSequence description = node.getContentDescription();
        if (node.isVisibleToUser() && bounds.centerY() >= videoTop && bounds.centerY() < videoTop + videoHeight &&
            description != null && description.toString().equals(label)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (control(node.getChild(i), label)) return true;
        return false;
    }
    private boolean contains(AccessibilityNodeInfo node, String text, boolean prefix) {
        if (node == null) return false;
        for (CharSequence label : new CharSequence[]{node.getText(), node.getContentDescription()}) {
            if (label != null && (prefix ? label.toString().startsWith(text) : label.toString().equals(text))) return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) if (contains(node.getChild(i), text, prefix)) return true;
        return false;
    }
    private AccessibilityNodeInfo seekBar(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence name = node.getClassName();
        if (node.isVisibleToUser() && name != null && name.toString().endsWith("SeekBar")) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo bar = seekBar(node.getChild(i));
            if (bar != null) return bar;
        }
        return null;
    }
    private void tap() {
        pointerDown = SystemClock.uptimeMillis();
        input(MotionEvent.ACTION_DOWN, width * .32f, videoTop + videoHeight * .28f); SystemClock.sleep(50);
        input(MotionEvent.ACTION_UP, pointerX, pointerY); pointerDown = 0;
        // A single-tap callback waits for the platform double-tap window before it toggles controls.
        SystemClock.sleep(ViewConfiguration.getDoubleTapTimeout() + 220);
    }
    private void input(int action, float x, float y) {
        pointerX = x; pointerY = y;
        MotionEvent event = MotionEvent.obtain(pointerDown, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        automation.injectInputEvent(event, true); event.recycle();
    }
}
