package com.limelight.binding.input;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExternalStylusSupportTest {
    private static final int TABLET = InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS;

    @Test
    public void identifiesDrawingTabletWithoutTreatingScreenPenAsOne() {
        assertTrue(ExternalStylusSupport.isDrawingTablet(TABLET | InputDevice.SOURCE_KEYBOARD));
        assertFalse(ExternalStylusSupport.isDrawingTablet(InputDevice.SOURCE_MOUSE));
        assertFalse(ExternalStylusSupport.isDrawingTablet(TABLET | InputDevice.SOURCE_TOUCHSCREEN));
    }

    @Test
    public void recoversSuppressedDirectionAndAppliesAllDisplayRotations() {
        MotionEvent event = mock(MotionEvent.class);
        MotionEvent raw = mock(MotionEvent.class);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
        when(raw.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0)).thenReturn((float) Math.PI / 4);
        int[] rotations = {Surface.ROTATION_0, Surface.ROTATION_90,
                Surface.ROTATION_180, Surface.ROTATION_270};
        float[] expected = {45, -45, -135, 135};
        try (MockedStatic<MotionEvent> factory = mockStatic(MotionEvent.class)) {
            factory.when(() -> MotionEvent.obtainNoHistory(event)).thenReturn(raw);
            for (int i = 0; i < rotations.length; i++) {
                assertEquals(expected[i], Math.toDegrees(ExternalStylusSupport.getOrientation(
                        event, 0, TABLET, rotations[i])), 0.001);
            }
        }
        verify(raw, times(4)).setSource(InputDevice.SOURCE_JOYSTICK);
        verify(raw, times(4)).recycle();
        verify(event, never()).setSource(anyInt());
        verify(event, never()).recycle();
    }

    @Test
    public void keepsWorkingDirectionAndDirectScreenPensUnchanged() {
        MotionEvent event = mock(MotionEvent.class);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
        when(event.getOrientation(0)).thenReturn(0.7f);
        try (MockedStatic<MotionEvent> factory = mockStatic(MotionEvent.class)) {
            assertEquals(0.7f, ExternalStylusSupport.getOrientation(event, 0, TABLET,
                    Surface.ROTATION_90), 0);
            when(event.getOrientation(0)).thenReturn(0f);
            assertEquals(0f, ExternalStylusSupport.getOrientation(event, 0,
                    InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN,
                    Surface.ROTATION_90), 0);
            when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
            assertEquals(0f, ExternalStylusSupport.getOrientation(event, 0, TABLET,
                    Surface.ROTATION_90), 0);
            factory.verifyNoInteractions();
        }
    }

    @Test
    public void earlyInputPreservesRealZeroDirectionAcrossDisplayRotations() {
        MotionEvent event = mock(MotionEvent.class);
        MotionEvent raw = mock(MotionEvent.class);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
        int[] rotations = {Surface.ROTATION_0, Surface.ROTATION_90,
                Surface.ROTATION_180, Surface.ROTATION_270};
        float[] expected = {0, -90, 180, 90};
        try (MockedStatic<MotionEvent> factory = mockStatic(MotionEvent.class)) {
            factory.when(() -> MotionEvent.obtainNoHistory(event)).thenReturn(raw);
            for (int i = 0; i < rotations.length; i++) {
                double degrees = Math.toDegrees(ExternalStylusSupport.getOrientationBeforeReinjection(
                        event, 0, TABLET, rotations[i]));
                assertEquals(expected[i], degrees < -179.99 ? degrees + 360 : degrees, 0.001);
            }
            // The same zero after reinjection must not invent a rotated direction.
            assertEquals(0f, ExternalStylusSupport.getOrientation(
                    event, 0, TABLET, Surface.ROTATION_270), 0);
        }
        verify(raw, times(5)).recycle();
        verify(event, never()).setSource(anyInt());
    }

    @Test
    public void earlyInputRecoversNonzeroDirectionAndRetainsAlreadyWorkingValues() {
        MotionEvent event = mock(MotionEvent.class);
        MotionEvent raw = mock(MotionEvent.class);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_ERASER);
        when(raw.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0)).thenReturn(-0.7f);
        try (MockedStatic<MotionEvent> factory = mockStatic(MotionEvent.class)) {
            factory.when(() -> MotionEvent.obtainNoHistory(event)).thenReturn(raw);
            assertEquals((float) Math.PI / 2 - 0.7f,
                    ExternalStylusSupport.getOrientationBeforeReinjection(
                            event, 0, TABLET, Surface.ROTATION_270), 0.0001f);
            when(event.getOrientation(0)).thenReturn(0.9f);
            assertEquals(0.9f, ExternalStylusSupport.getOrientationBeforeReinjection(
                    event, 0, TABLET, Surface.ROTATION_270), 0);
            factory.verify(() -> MotionEvent.obtainNoHistory(event), times(1));
        }
    }

    @Test
    public void recyclesTemporaryEventIfAxisReadFails() {
        MotionEvent event = mock(MotionEvent.class);
        MotionEvent raw = mock(MotionEvent.class);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_ERASER);
        when(raw.getAxisValue(MotionEvent.AXIS_ORIENTATION, 0)).thenThrow(new IllegalArgumentException());
        try (MockedStatic<MotionEvent> factory = mockStatic(MotionEvent.class)) {
            factory.when(() -> MotionEvent.obtainNoHistory(event)).thenReturn(raw);
            assertThrows(IllegalArgumentException.class, () -> ExternalStylusSupport.getOrientation(
                    event, 0, TABLET, Surface.ROTATION_0));
        }
        verify(raw).recycle();
    }
}
