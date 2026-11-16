package com.limelight.binding.input;

import android.graphics.PointF;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ExternalStylusSupportTest {
    private static final int TABLET = InputDevice.SOURCE_MOUSE | InputDevice.SOURCE_STYLUS;

    private static InputDevice.MotionRange range(int axis, float max) {
        return ReflectionHelpers.callConstructor(InputDevice.MotionRange.class,
                ReflectionHelpers.ClassParameter.from(int.class, axis),
                ReflectionHelpers.ClassParameter.from(int.class, TABLET),
                ReflectionHelpers.ClassParameter.from(float.class, 0f),
                ReflectionHelpers.ClassParameter.from(float.class, max),
                ReflectionHelpers.ClassParameter.from(float.class, 0f),
                ReflectionHelpers.ClassParameter.from(float.class, 0f),
                ReflectionHelpers.ClassParameter.from(float.class, 0f));
    }

    @Test
    public void bluetoothDecoMapsItsRealPenRangeToFullStreamRatherThanHalfTheScreen() {
        InputDevice tablet = mock(InputDevice.class);
        when(tablet.getVendorId()).thenReturn(0x28bd);
        when(tablet.getProductId()).thenReturn(0x0949);
        when(tablet.getSources()).thenReturn(TABLET);
        InputDevice.MotionRange xRange = range(MotionEvent.AXIS_X, 3199.902f);
        InputDevice.MotionRange yRange = range(MotionEvent.AXIS_Y, 3199.902f);
        when(tablet.getMotionRange(MotionEvent.AXIS_X, TABLET)).thenReturn(xRange);
        when(tablet.getMotionRange(MotionEvent.AXIS_Y, TABLET)).thenReturn(yRange);
        MotionEvent event = mock(MotionEvent.class);
        when(event.getDevice()).thenReturn(tablet);
        when(event.getSource()).thenReturn(TABLET);
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
        // The measured 17797 x 11109 HID area after Android's square scaling.
        when(event.getX(0)).thenReturn(3199.902f * 17797f / 32767f);
        when(event.getY(0)).thenReturn(3199.902f * 11109f / 32767f);
        PointF corner = ExternalStylusSupport.getNormalizedTabletPosition(event, 0);
        assertEquals(1f, corner.x, 0.0001);
        assertEquals(1f, corner.y, 0.0001);
        when(event.getX(0)).thenReturn(3199.902f * 17797f / 32767f / 2);
        when(event.getY(0)).thenReturn(3199.902f * 11109f / 32767f / 2);
        PointF center = ExternalStylusSupport.getNormalizedTabletPosition(event, 0);
        assertEquals(0.5f, center.x, 0.0001);
        assertEquals(0.5f, center.y, 0.0001);
        when(event.getX(0)).thenReturn(-10f);
        when(event.getY(0)).thenReturn(10000f);
        PointF clamped = ExternalStylusSupport.getNormalizedTabletPosition(event, 0);
        assertEquals(0f, clamped.x, 0);
        assertEquals(1f, clamped.y, 0);
        // USB reports unequal ranges and must not receive the Bluetooth correction.
        when(tablet.getMotionRange(MotionEvent.AXIS_X, TABLET)).thenReturn(range(MotionEvent.AXIS_X, 3200f));
        when(tablet.getMotionRange(MotionEvent.AXIS_Y, TABLET)).thenReturn(range(MotionEvent.AXIS_Y, 2000f));
        when(event.getX(0)).thenReturn(1600f);
        when(event.getY(0)).thenReturn(1000f);
        assertEquals(0.5f, ExternalStylusSupport.getNormalizedTabletPosition(event, 0).x, 0);
        assertEquals(0.5f, ExternalStylusSupport.getNormalizedTabletPosition(event, 0).y, 0);
        // The firmware's mouse reports and screen pens retain their own mapping.
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_MOUSE);
        assertNull(ExternalStylusSupport.getNormalizedTabletPosition(event, 0));
        when(event.getToolType(0)).thenReturn(MotionEvent.TOOL_TYPE_STYLUS);
        when(tablet.getSources()).thenReturn(InputDevice.SOURCE_STYLUS | InputDevice.SOURCE_TOUCHSCREEN);
        assertNull(ExternalStylusSupport.getNormalizedTabletPosition(event, 0));
    }

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
