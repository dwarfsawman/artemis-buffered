package com.limelight.binding.input;

import android.graphics.PointF;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

/** Compatibility fixes for drawing tablets that Android exposes as mouse/stylus devices. */
public final class ExternalStylusSupport {
    private static long lastDiagnosticTime;
    private ExternalStylusSupport() {}

    public static boolean isDrawingTablet(InputDevice device) {
        if (device == null) return false;
        // Capture removes MOUSE/STYLUS from the Deco's disabled TouchInputMapper.
        // Its HID identity survives, including when it connects during capture.
        return isDrawingTablet(device.getSources())
                || isDecoMini7w(device);
    }

    private static boolean isDecoMini7w(InputDevice device) {
        return device != null && device.getVendorId() == 0x28bd && device.getProductId() == 0x0949;
    }

    /** Android merges report-9's rotary node with the separate REL_X/Y mouse node.
     * Its SOURCE_MOUSE events come from CursorInputMapper, not the pen mapper. */
    public static boolean isDecoRelativeMouse(InputDevice device) {
        if (!isDecoMini7w(device)) return false;
        String name = device.getName();
        int sources = device.getSources();
        return ("Deco mini7W V2".equals(name) || "Deco mini7W V2 Mouse".equals(name))
                && (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                && (sources & InputDevice.SOURCE_STYLUS) != InputDevice.SOURCE_STYLUS;
    }

    /** Full tablet area -> full stream, using the untouched accessibility event. */
    public static PointF getNormalizedTabletPosition(MotionEvent event, int pointerIndex) {
        InputDevice device = event.getDevice();
        if (!isDecoMini7w(device) || !isDrawingTablet(device.getSources())
                || (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_STYLUS
                    && event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_ERASER)) {
            return null;
        }
        InputDevice.MotionRange x = device.getMotionRange(MotionEvent.AXIS_X, event.getSource());
        InputDevice.MotionRange y = device.getMotionRange(MotionEvent.AXIS_Y, event.getSource());
        if (x == null || y == null || x.getRange() <= 0 || y.getRange() <= 0) return null;
        float normalizedX = (event.getX(pointerIndex) - x.getMin()) / x.getRange();
        float normalizedY = (event.getY(pointerIndex) - y.getMin()) / y.getRange();
        // Bluetooth's pen descriptor advertises 32767 on both axes, but the
        // firmware sends report-7 coordinates capped at 0x4585 / 0x2b65.
        // Android scales both axes equally from that square descriptor. USB's
        // correct, unequal ranges must not receive the Bluetooth correction.
        if (Math.abs(x.getRange() - y.getRange()) < 1f) {
            normalizedX *= 32767f / 0x4585;
            normalizedY *= 32767f / 0x2b65;
        }
        PointF normalized = new PointF(Math.max(0f, Math.min(1f, normalizedX)),
                Math.max(0f, Math.min(1f, normalizedY)));
        if (Log.isLoggable("ArtemisDeco", Log.DEBUG)
                && SystemClock.uptimeMillis() - lastDiagnosticTime >= 250) {
            lastDiagnosticTime = SystemClock.uptimeMillis();
            Log.d("ArtemisDeco", "tablet position=" + normalized.x + "," + normalized.y
                    + " pressure=" + event.getPressure(pointerIndex));
        }
        return normalized;
    }

    public static boolean isDrawingTablet(int sources) {
        return (sources & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
                && (sources & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                && (sources & InputDevice.SOURCE_TOUCHSCREEN) != InputDevice.SOURCE_TOUCHSCREEN;
    }

    public static float getOrientation(MotionEvent event, int pointerIndex,
                                       int deviceSources, int displayRotation) {
        float orientation = event.getOrientation(pointerIndex);
        int toolType = event.getToolType(pointerIndex);
        if (Build.VERSION.SDK_INT < 35 || orientation != 0
                || !isDrawingTablet(deviceSources)
                || (toolType != MotionEvent.TOOL_TYPE_STYLUS
                    && toolType != MotionEvent.TOOL_TYPE_ERASER)) {
            return orientation;
        }

        // Android 15+ transformOrientation() returns zero without SUPPORTS_ORIENTATION.
        // The POINTER stylus path omits that flag. This recovery works only while
        // coordinates still retain direction, before accessibility reinjection clears it.
        // Change only a temporary copy: the original source, position and history must
        // remain intact for the rest of the input handlers.
        MotionEvent rawEvent = MotionEvent.obtainNoHistory(event);
        try {
            rawEvent.setSource(InputDevice.SOURCE_JOYSTICK);
            orientation = rawEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex);
            if (Log.isLoggable("ArtemisDeco", Log.DEBUG)
                    && SystemClock.uptimeMillis() - lastDiagnosticTime >= 250) {
                lastDiagnosticTime = SystemClock.uptimeMillis();
                Log.d("ArtemisDeco", "device=" + event.getDeviceId()
                        + " source=" + Integer.toHexString(event.getSource())
                        + " flags=" + Integer.toHexString(event.getFlags())
                        + " normal=" + event.getOrientation(pointerIndex)
                        + " raw=" + orientation
                        + " tilt=" + event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
                        + " displayRotation=" + displayRotation);
            }
        } finally {
            rawEvent.recycle();
        }

        // Some OEM input pipelines already cleared the stored direction. Rotating
        // that zero would invent a direction; keep the original value in that case.
        if (orientation == 0) return event.getOrientation(pointerIndex);

        return rotateStoredOrientation(orientation, displayRotation);
    }

    /** Read intact coordinates from AccessibilityService.onMotionEvent(), before reinjection. */
    public static float getOrientationBeforeReinjection(MotionEvent event, int pointerIndex,
                                                        int deviceSources, int displayRotation) {
        float orientation = event.getOrientation(pointerIndex);
        int toolType = event.getToolType(pointerIndex);
        if (Build.VERSION.SDK_INT < 35 || orientation != 0 || !isDrawingTablet(deviceSources)
                || (toolType != MotionEvent.TOOL_TYPE_STYLUS
                    && toolType != MotionEvent.TOOL_TYPE_ERASER)) {
            return orientation;
        }
        MotionEvent rawEvent = MotionEvent.obtainNoHistory(event);
        try {
            rawEvent.setSource(InputDevice.SOURCE_JOYSTICK);
            // Zero is a valid direction here: this callback precedes the destructive
            // conversion, unlike an ordinary Activity callback after reinjection.
            return rotateStoredOrientation(
                    rawEvent.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex), displayRotation);
        } finally {
            rawEvent.recycle();
        }
    }

    private static float rotateStoredOrientation(float orientation, int displayRotation) {
        // Reading the stored axis bypasses the display transform. Restore that
        // rotation without changing the original event's source or coordinates.
        switch (displayRotation) {
            case Surface.ROTATION_90:
                orientation -= (float) (Math.PI / 2);
                break;
            case Surface.ROTATION_180:
                orientation += (float) Math.PI;
                break;
            case Surface.ROTATION_270:
                orientation += (float) (Math.PI / 2);
                break;
            default:
                break;
        }
        return (float) Math.atan2(Math.sin(orientation), Math.cos(orientation));
    }
}
