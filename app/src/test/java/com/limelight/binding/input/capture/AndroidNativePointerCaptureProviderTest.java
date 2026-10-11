package com.limelight.binding.input.capture;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.PointerIcon;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.mockito.Mockito.*;

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
}
