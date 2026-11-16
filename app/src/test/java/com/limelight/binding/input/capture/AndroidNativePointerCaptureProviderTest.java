package com.limelight.binding.input.capture;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AndroidNativePointerCaptureProviderTest {
    private final Activity activity = mock(Activity.class);
    private final View view = mock(View.class);
    private final InputManager manager = mock(InputManager.class);

    private AndroidNativePointerCaptureProvider provider() {
        when(activity.getSystemService(InputManager.class)).thenReturn(manager);
        when(activity.getPackageManager()).thenReturn(mock(PackageManager.class));
        when(view.getContext()).thenReturn(activity);
        when(view.getRootView()).thenReturn(view);
        when(activity.getContentResolver()).thenReturn(RuntimeEnvironment.getApplication().getContentResolver());
        when(view.hasWindowFocus()).thenReturn(true);
        return new AndroidNativePointerCaptureProvider(activity, view);
    }

    private InputDevice device(int sources) {
        InputDevice device = mock(InputDevice.class);
        when(device.getSources()).thenReturn(sources);
        when(device.supportsSource(anyInt())).thenAnswer(invocation -> {
            int source = invocation.getArgument(0);
            return (sources & source) == source;
        });
        return device;
    }

    @Test
    public void tabletVetoesCaptureEvenIfAnOrdinaryMouseIsListedFirst() {
        AndroidNativePointerCaptureProvider provider = provider();
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(tablet);
            provider.enableCapture();
            provider.onWindowFocusChanged(true);
            provider.onInputDeviceChanged(2);
            verify(view, never()).requestPointerCapture();
        }
    }

    @Test
    public void connectingTabletReleasesCaptureAndRemovingItRestoresMouseCapture() {
        AndroidNativePointerCaptureProvider provider = provider();
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            provider.enableCapture();
            verify(view).requestPointerCapture();
            when(view.hasPointerCapture()).thenReturn(true);
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(tablet);
            provider.onInputDeviceAdded(2);
            verify(view).releasePointerCapture();
            when(view.hasPointerCapture()).thenReturn(false);
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1});
            provider.onInputDeviceRemoved(2);
            verify(view, times(2)).requestPointerCapture();
            provider.showCursor();
            provider.onInputDeviceChanged(1);
            verify(view, times(2)).requestPointerCapture();
        }
    }

    @Test
    public void screenPenDoesNotDisableAnIndependentMouse() {
        AndroidNativePointerCaptureProvider provider = provider();
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE);
        InputDevice screenPen = device(InputDevice.SOURCE_TOUCHSCREEN | InputDevice.SOURCE_STYLUS);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(screenPen);
            provider.enableCapture();
            verify(view).requestPointerCapture();
        }
    }

    @Test
    public void decoConnectedDuringCaptureIsRecognizedEvenWithStylusSourcesDisabled() {
        AndroidNativePointerCaptureProvider provider = provider();
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE_RELATIVE);
        InputDevice disabledPen = device(InputDevice.SOURCE_KEYBOARD);
        when(disabledPen.getVendorId()).thenReturn(0x28bd);
        when(disabledPen.getProductId()).thenReturn(0x0949);
        when(view.hasPointerCapture()).thenReturn(true);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(disabledPen);
            provider.enableCapture();
            verify(view).releasePointerCapture();
            verify(view, never()).requestPointerCapture();
            // A second notification while Android restores its sources must not recapture.
            when(view.hasPointerCapture()).thenReturn(false);
            provider.onInputDeviceChanged(2);
            verify(view, never()).requestPointerCapture();
        }
    }

    @Test
    public void independentMouseUsesUnclippedDeltasWhileTabletRetainsAbsolutePenAndHover() {
        AndroidNativePointerCaptureProvider provider = provider();
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        MotionEvent event = mock(MotionEvent.class);
        when(event.getSource()).thenReturn(InputDevice.SOURCE_MOUSE);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
        when(event.getDevice()).thenReturn(mouse);
        // Android cursor is already at its right edge, but relative movement continues.
        when(event.getX()).thenReturn(3199f);
        when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, 0)).thenReturn(12f);
        when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, 0)).thenReturn(-3f);
        when(event.getHistorySize()).thenReturn(1);
        when(event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, 0, 0)).thenReturn(5f);
        when(event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, 0, 0)).thenReturn(-2f);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(tablet);
            provider.enableCapture();
            assertTrue(provider.eventHasRelativeMouseAxes(event));
            assertEquals(17f, provider.getRelativeAxisX(event), 0);
            assertEquals(-5f, provider.getRelativeAxisY(event), 0);
            // Stationary buttons/scrolls must not fall back to sending an absolute position.
            when(event.getHistorySize()).thenReturn(0);
            when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, 0)).thenReturn(0f);
            when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, 0)).thenReturn(0f);
            assertTrue(provider.eventHasRelativeMouseAxes(event));
            assertEquals(0f, provider.getRelativeAxisX(event), 0);
            when(event.getDevice()).thenReturn(tablet);
            assertFalse(provider.eventHasRelativeMouseAxes(event));
            when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
            assertFalse(provider.eventHasRelativeMouseAxes(event));
            when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_ERASER);
            assertFalse(provider.eventHasRelativeMouseAxes(event));
            when(event.getDevice()).thenReturn(mouse);
            when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
            provider.showCursor();
            assertFalse(provider.eventHasRelativeMouseAxes(event));
            provider.disableCapture();
            assertFalse(provider.eventHasRelativeMouseAxes(event));
        }
    }

    @Test
    @Config(sdk = 35)
    public void decoFirmwareRelativeModeCrossesScreenEdgeAndZeroDeltaClickStaysRelative() {
        AndroidNativePointerCaptureProvider provider = provider();
        Settings.System.putInt(activity.getContentResolver(), "pointer_speed", 7);
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_ROTARY_ENCODER);
        when(mouse.getVendorId()).thenReturn(0x28bd);
        when(mouse.getProductId()).thenReturn(0x0949);
        when(mouse.getName()).thenReturn("Deco mini7W V2");
        InputDevice pen = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        when(pen.getVendorId()).thenReturn(0x28bd);
        when(pen.getProductId()).thenReturn(0x0949);
        when(pen.getName()).thenReturn("Deco mini7W V2 Pen");
        MotionEvent event = mock(MotionEvent.class);
        when(event.getSource()).thenReturn(InputDevice.SOURCE_MOUSE);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
        when(event.getDevice()).thenReturn(mouse);
        when(event.getX()).thenReturn(3199f);
        when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, 0)).thenReturn(8.166992f);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(pen);
            provider.enableCapture();
            verify(view, never()).requestPointerCapture();
            assertTrue(provider.eventHasRelativeMouseAxes(event));
            assertEquals(2f, provider.getRelativeAxisX(event), 0);
            when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, 0)).thenReturn(0f);
            assertTrue(provider.eventHasRelativeMouseAxes(event));
            assertEquals(0f, provider.getRelativeAxisX(event), 0);
            when(event.getDevice()).thenReturn(pen);
            assertFalse(provider.eventHasRelativeMouseAxes(event));
        }
    }

    @Test
    @Config(sdk = 35)
    public void uncapturedMouseCorrectsAndroidSpeedWithoutChangingSystemSettingsOrCapturedInput() {
        AndroidNativePointerCaptureProvider provider = provider();
        Settings.System.putInt(activity.getContentResolver(), "pointer_speed", 7);
        InputDevice mouse = device(InputDevice.SOURCE_MOUSE);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        MotionEvent event = mock(MotionEvent.class);
        when(event.getSource()).thenReturn(InputDevice.SOURCE_MOUSE);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
        when(event.getDevice()).thenReturn(mouse);
        // Recorded Xiaomi Pad 8 sample: two counts became 8.166... pixels.
        when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_X, 0)).thenReturn(8.166992f);
        when(event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y, 0)).thenReturn(-8.166016f);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class);
             MockedStatic<PointerIcon> icons = mockStatic(PointerIcon.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1, 2});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(mouse);
            devices.when(() -> InputDevice.getDevice(2)).thenReturn(tablet);
            provider.enableCapture();
            assertEquals(2f, provider.getRelativeAxisX(event), 0);
            assertEquals(-2f, provider.getRelativeAxisY(event), 0);
            assertEquals(7, Settings.System.getInt(activity.getContentResolver(), "pointer_speed", 0));
            when(event.getSource()).thenReturn(InputDevice.SOURCE_MOUSE_RELATIVE);
            when(event.getAxisValue(MotionEvent.AXIS_X, 0)).thenReturn(2f);
            assertEquals(2f, provider.getRelativeAxisX(event), 0);
        }
    }
}
