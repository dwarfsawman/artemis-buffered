package com.limelight.binding.input.capture;

import com.limelight.nvstream.jni.MoonBridge;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class DecoEvdevStreamTest {
    private static final class Pen {
        byte event, tool, buttons, tilt;
        float x, y, pressure;
        short rotation;
    }
    private static final class Recording implements DecoEvdevStream.Sink {
        final List<Pen> pens = new ArrayList<>();
        int mouseCalls, mouseButtons, wheel;
        float mouseX, mouseY;
        @Override public void pen(byte event, byte tool, byte buttons, float x, float y,
                                  float pressure, short rotation, byte tilt) {
            Pen p = new Pen(); p.event = event; p.tool = tool; p.buttons = buttons;
            p.x = x; p.y = y; p.pressure = pressure; p.rotation = rotation; p.tilt = tilt; pens.add(p);
        }
        @Override public void mouse(float x, float y, int buttons) {
            mouseCalls++; mouseX = x; mouseY = y; mouseButtons = buttons;
        }
        @Override public void scroll(int amount) { wheel += amount; }
    }

    @Test public void fullTabletPressureAndTiltReachHostWithoutAndroidTransforms() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("P 17797 11109 8191 30 0 1 1 0 3");
        Pen p = r.pens.get(0);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, p.event);
        assertEquals(1, p.x, 0); assertEquals(1, p.y, 0); assertEquals(1, p.pressure, 0);
        assertEquals(270, p.rotation); assertEquals(30, p.tilt); assertEquals(3, p.buttons);
        stream.accept("P 0 0 4096 0 -30 1 1 0 0");
        p = r.pens.get(1);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_MOVE, p.event);
        assertEquals(0, p.x, 0); assertEquals(0, p.y, 0);
        assertEquals(4096 / 8191f, p.pressure, 0); assertEquals(180, p.rotation);
    }

    @Test public void liftSendsUpAndLeaveBeforeFirmwareMouseHover() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("P 1000 2000 6000 0 0 1 1 0 0");
        stream.accept("P 1000 2000 0 0 0 0 0 0 0");
        stream.accept("M 32767 32767 2 0");
        assertEquals(3, r.pens.size());
        assertEquals(MoonBridge.LI_TOUCH_EVENT_UP, r.pens.get(1).event);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE, r.pens.get(2).event);
        assertEquals(1, r.mouseCalls); assertEquals(2, r.mouseButtons);
        assertEquals(1, r.mouseX, 0); assertEquals(1, r.mouseY, 0);
        stream.release(); assertEquals(0, r.mouseButtons);
    }

    @Test public void wheelAndStaleMouseReleaseDoNotInterruptDrawing() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("P 1000 2000 6000 0 0 1 1 0 0");
        stream.accept("M 32000 32000 0 1");
        stream.accept("M 32000 32000 0 0");
        assertEquals(1, r.pens.size()); assertEquals(0, r.mouseCalls); assertEquals(1, r.wheel);
        stream.accept("P 1001 2001 6000 0 0 1 1 0 0");
        assertEquals(MoonBridge.LI_TOUCH_EVENT_MOVE, r.pens.get(1).event);
    }

    @Test public void focusLossOrDroppedEventsCancelPenAndReleaseButtonsIdempotently() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("P 17797 11109 8191 0 0 1 0 1 0");
        assertEquals(MoonBridge.LI_TOOL_TYPE_ERASER, r.pens.get(0).tool);
        stream.accept("RESET"); stream.release();
        assertEquals(3, r.pens.size());
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL, r.pens.get(1).event);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE, r.pens.get(2).event);
        stream.accept("M 0 0 4 0"); stream.accept("RESET");
        assertEquals(0, r.mouseButtons); assertEquals(2, r.mouseCalls);
    }

    @Test public void mouseSideButtonToTipReleasesMouseBeforeDrawing() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("M 32767 32767 2 0");
        stream.accept("P 17797 11109 8191 0 0 1 1 0 0");
        assertEquals(0, r.mouseButtons); assertEquals(2, r.mouseCalls);
        assertEquals(MoonBridge.LI_TOUCH_EVENT_DOWN, r.pens.get(0).event);
    }

    @Test public void invalidFrameFailsAndReleaseStillClearsExistingStroke() {
        Recording r = new Recording(); DecoEvdevStream stream = new DecoEvdevStream(r);
        stream.accept("P 0 0 8000 0 0 1 1 0 0");
        assertThrows(IllegalArgumentException.class, () -> stream.accept("P bad"));
        stream.release();
        assertEquals(MoonBridge.LI_TOUCH_EVENT_CANCEL, r.pens.get(1).event);
    }
}
