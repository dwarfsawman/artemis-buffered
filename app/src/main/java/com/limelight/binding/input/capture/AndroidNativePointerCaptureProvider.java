package com.limelight.binding.input.capture;

import android.annotation.TargetApi;
import android.app.Activity;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.ExternalStylusSupport;


// We extend AndroidPointerIconCaptureProvider because we want to also get the
// pointer icon hiding behavior over our stream view just in case pointer capture
// is unavailable on this system (ex: DeX, ChromeOS)
@TargetApi(Build.VERSION_CODES.O)
public class AndroidNativePointerCaptureProvider extends AndroidPointerIconCaptureProvider implements InputManager.InputDeviceListener {
    private final InputManager inputManager;
    private final View targetView;
    private boolean drawingTabletPresent;
    private long lastMouseDiagnosticTime;
    private float uncapturedMouseGain = 1f;

    public AndroidNativePointerCaptureProvider(Activity activity, View targetView) {
        super(activity, targetView);
        this.inputManager = activity.getSystemService(InputManager.class);
        this.targetView = targetView;
    }

    public static boolean isCaptureProviderSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    // We only capture the pointer if we have a compatible InputDevice
    // present. This is a workaround for an Android 12 regression causing
    // incorrect mouse input when using the SPen.
    // https://github.com/moonlight-stream/moonlight-android/issues/1030
    private boolean hasCaptureCompatibleInputDevice() {
        boolean compatibleDevicePresent = false;
        drawingTabletPresent = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) {
                continue;
            }

            // Capture disables Android's POINTER TouchInputMapper, including external
            // pens such as the Deco. Even another connected mouse can trigger this,
            // so a drawing tablet vetoes capture for the entire device list.
            if (ExternalStylusSupport.isDrawingTablet(device)) {
                drawingTabletPresent = true;
                continue;
            }

            // Skip touchscreens when considering compatible capture devices.
            // Samsung devices on Android 12 will report a sec_touchpad device
            // with SOURCE_TOUCHSCREEN, SOURCE_KEYBOARD, and SOURCE_MOUSE.
            // Upon enabling pointer capture, that device will switch to
            // SOURCE_KEYBOARD and SOURCE_TOUCHPAD.
            // Only skip on non ChromeOS devices cause the ChromeOS pointer else
            // gets disabled removing relative mouse capabilities
            // on Chromebooks with touchscreens
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN) && !targetView.getContext().getPackageManager().hasSystemFeature("org.chromium.arc.device_management")) {
                continue;
            }

            if (device.supportsSource(InputDevice.SOURCE_MOUSE) ||
                    device.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
                    device.supportsSource(InputDevice.SOURCE_TOUCHPAD)) {
                compatibleDevicePresent = true;
            }
        }

        return compatibleDevicePresent && !drawingTabletPresent;
    }

    private void updatePointerCapture() {
        // Read, but never change, the system's mouse speed. Unlike capture,
        // SOURCE_MOUSE deltas already contain Android's pointer sensitivity.
        int speed = Settings.System.getInt(targetView.getContext().getContentResolver(), "pointer_speed", 0);
        if (Build.VERSION.SDK_INT >= 35) {
            // AOSP AccelerationCurve.cpp: low-speed gain = 3.19 * 0.64 * sensitivity / 10.
            // In particular, speed 7 turns two HID counts into ~8.166 screen pixels.
            int[] sensitivity = {1, 2, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 18, 20};
            uncapturedMouseGain = 3.19f * 0.64f * sensitivity[Math.max(0, Math.min(14, speed + 7))] / 10f;
        } else {
            uncapturedMouseGain = (float) Math.pow(2, speed / 4.0);
        }
        boolean shouldCapture = isCapturing && !isCursorVisible
                && targetView.hasWindowFocus() && hasCaptureCompatibleInputDevice();
        if (shouldCapture && !targetView.hasPointerCapture()) {
            targetView.requestPointerCapture();
        } else if (!shouldCapture && targetView.hasPointerCapture()) {
            targetView.releasePointerCapture();
        }
    }

    @Override
    public void showCursor() {
        super.showCursor();

        // It is important to unregister the listener *before* releasing pointer capture,
        // because releasing pointer capture can cause an onInputDeviceChanged() callback
        // for devices with a touchpad (like a DS4 controller).
        inputManager.unregisterInputDeviceListener(this);
        targetView.releasePointerCapture();
    }

    @Override
    public void hideCursor() {
        super.hideCursor();

        // Listen for device events to enable/disable capture
        inputManager.registerInputDeviceListener(this, null);

        // Capture now if we have a capture-capable device
        updatePointerCapture();
    }

    @Override
    public void onWindowFocusChanged(boolean focusActive) {
        // NB: We have to check cursor visibility here because Android pointer capture
        // doesn't support capturing the cursor while it's visible. Enabling pointer
        // capture implicitly hides the cursor.
        if (!focusActive || !isCapturing || isCursorVisible) {
            return;
        }

        // Recapture the pointer if focus was regained. On Android Q,
        // we have to delay a bit before requesting capture because otherwise
        // we'll hit the "requestPointerCapture called for a window that has no focus"
        // error and it will not actually capture the cursor.
        Handler h = new Handler();
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                updatePointerCapture();
            }
        }, 500);
    }

    @Override
    public boolean eventHasRelativeMouseAxes(MotionEvent event) {
        // SOURCE_MOUSE_RELATIVE is how SOURCE_MOUSE appears when our view has pointer capture.
        // SOURCE_TOUCHPAD will have relative axes populated iff our view has pointer capture.
        // See https://developer.android.com/reference/android/view/View#requestPointerCapture()
        // CursorInputMapper supplies AXIS_RELATIVE_X/Y even without capture.
        // Use those for the independent mouse, including zero-delta clicks/scrolls,
        // so Android's cursor hitting the display edge cannot pin the host cursor.
        // The tablet's absolute mouse reports (modified firmware's hovering pen)
        // must still follow the absolute path, just like its stylus reports.
        int eventSource = event.getSource();
        boolean relative = (eventSource == InputDevice.SOURCE_MOUSE_RELATIVE && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) ||
                (eventSource == InputDevice.SOURCE_TOUCHPAD && targetView.hasPointerCapture()) ||
                isUncapturedMouseEvent(event);
        if (drawingTabletPresent && Log.isLoggable("ArtemisMouse", Log.DEBUG)
                && SystemClock.uptimeMillis() - lastMouseDiagnosticTime >= 250) {
            lastMouseDiagnosticTime = SystemClock.uptimeMillis();
            Log.d("ArtemisMouse", "device=" + event.getDeviceId()
                    + " source=" + Integer.toHexString(eventSource)
                    + " tool=" + event.getToolType(0) + " relative=" + relative
                    + " capture=" + targetView.hasPointerCapture()
                    + " x=" + event.getX() + " y=" + event.getY()
                    + " gain=" + uncapturedMouseGain
                    + " rawDx=" + event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    + " rawDy=" + event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    + " dx=" + getRelativeAxisX(event) + " dy=" + getRelativeAxisY(event));
        }
        return relative;
    }

    private boolean isUncapturedMouseEvent(MotionEvent event) {
        return drawingTabletPresent && isCapturing && !isCursorVisible
                && event.getSource() == InputDevice.SOURCE_MOUSE
                && event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
                && event.getDevice() != null
                && (!ExternalStylusSupport.isDrawingTablet(event.getDevice())
                    || ExternalStylusSupport.isDecoRelativeMouse(event.getDevice()));
    }

    @Override
    public float getRelativeAxisX(MotionEvent event, int pointerIndex) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_X : MotionEvent.AXIS_RELATIVE_X;
        float x = event.getAxisValue(axis, pointerIndex);
        for (int i = 0; i < event.getHistorySize(); i++) {
            x += event.getHistoricalAxisValue(axis, pointerIndex, i);
        }
        // Round to HID counts, avoiding float quantization turning 1.9999 into 1.
        return isUncapturedMouseEvent(event) ? Math.round(x / uncapturedMouseGain) : x;
    }

    @Override
    public float getRelativeAxisY(MotionEvent event, int pointerIndex) {
        int axis = (event.getSource() == InputDevice.SOURCE_MOUSE_RELATIVE) ?
                MotionEvent.AXIS_Y : MotionEvent.AXIS_RELATIVE_Y;
        float y = event.getAxisValue(axis, pointerIndex);
        for (int i = 0; i < event.getHistorySize(); i++) {
            y += event.getHistoricalAxisValue(axis, pointerIndex, i);
        }
        return isUncapturedMouseEvent(event) ? Math.round(y / uncapturedMouseGain) : y;
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        // Check if we've added a capture-compatible device
        updatePointerCapture();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        // Check if the capture-compatible device was removed
        updatePointerCapture();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Re-evaluate the device sources after a change.
        // Note: This callback must be handled carefully because it can happen as a result of
        // calling requestPointerCapture(). This can cause trackpad devices to gain SOURCE_MOUSE_RELATIVE
        // and re-enter this callback.
        updatePointerCapture();
    }
}
