package com.limelight;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class KeyboardAccessibilityServiceTest {
    private KeyboardAccessibilityService service;
    private AccessibilityServiceInfo appliedInfo;

    private void connect() {
        service = spy(new KeyboardAccessibilityService());
        doReturn(mock(InputManager.class)).when(service).getSystemService(InputManager.class);
        doAnswer(invocation -> {
            appliedInfo = invocation.getArgument(0);
            return null;
        }).when(service).setServiceInfo(any(AccessibilityServiceInfo.class));
        service.onServiceConnected();
    }

    private InputDevice device(int sources) {
        InputDevice device = mock(InputDevice.class);
        when(device.getSources()).thenReturn(sources);
        return device;
    }

    @After public void cleanup() {
        Game.instance = null;
        if (service != null) service.onDestroy();
    }

    @Test public void routesOnlyDuringForegroundStreamAndKeepsKeyboardFiltering() {
        Game game = mock(Game.class);
        Game.instance = game;
        when(game.canReceiveAccessibilityPointerEvents()).thenReturn(true);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{34});
            devices.when(() -> InputDevice.getDevice(34)).thenReturn(tablet);
            connect();
            assertEquals(InputDevice.SOURCE_MOUSE, appliedInfo.getMotionEventSources());
            assertTrue((appliedInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);
            MotionEvent event = mock(MotionEvent.class);
            service.onMotionEvent(event);
            verify(game).dispatchAccessibilityPointerEvent(event);

            when(game.canReceiveAccessibilityPointerEvents()).thenReturn(false);
            KeyboardAccessibilityService.refreshPointerInput();
            assertEquals(0, appliedInfo.getMotionEventSources());
            service.onMotionEvent(event);
            verify(game, times(1)).dispatchAccessibilityPointerEvent(event);
            assertTrue((appliedInfo.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0);
        }
    }

    @Test public void screenPenDoesNotEnableInterceptionAndDisconnectReleasesIt() {
        Game game = mock(Game.class);
        Game.instance = game;
        when(game.canReceiveAccessibilityPointerEvents()).thenReturn(true);
        InputDevice screenPen = device(InputDevice.SOURCE_TOUCHSCREEN | InputDevice.SOURCE_STYLUS);
        InputDevice tablet = device(InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS);
        try (MockedStatic<InputDevice> devices = mockStatic(InputDevice.class)) {
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[]{1});
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(screenPen);
            connect();
            assertEquals(0, appliedInfo.getMotionEventSources());
            devices.when(() -> InputDevice.getDevice(1)).thenReturn(tablet);
            service.onInputDeviceChanged(1);
            assertEquals(InputDevice.SOURCE_MOUSE, appliedInfo.getMotionEventSources());
            devices.when(InputDevice::getDeviceIds).thenReturn(new int[0]);
            service.onInputDeviceRemoved(1);
            assertEquals(0, appliedInfo.getMotionEventSources());
        }
    }

    @Test public void windowsKeyStillReachesTheConnectedGame() {
        Game game = mock(Game.class);
        game.connected = true;
        Game.instance = game;
        connect();
        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT);
        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_META_LEFT);
        assertTrue(service.onKeyEvent(down));
        assertTrue(service.onKeyEvent(up));
        verify(game).handleKeyDown(down);
        verify(game).handleKeyUp(up);
    }
}
