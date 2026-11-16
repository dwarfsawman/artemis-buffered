package com.limelight.binding.input.capture;

import com.limelight.nvstream.jni.MoonBridge;

/** Decodes SYN_REPORT snapshots. Coordinates stay in tablet/stream orientation. */
public final class DecoEvdevStream {
    public interface Sink {
        void pen(byte event, byte tool, byte buttons, float x, float y,
                 float pressure, short rotation, byte tilt);
        void mouse(float x, float y, int buttons);
        void scroll(int wheel);
    }

    private final Sink sink;
    private boolean touching, inRange;
    private byte tool = MoonBridge.LI_TOOL_TYPE_PEN;
    private float x, y;
    private int mouseButtons;

    public DecoEvdevStream(Sink sink) { this.sink = sink; }

    public void accept(String line) {
        if (line.equals("RESET")) { release(); return; }
        String[] fields = line.split(" ");
        if (fields.length == 10 && fields[0].equals("P")) {
            int[] v = values(fields);
            x = clamp(v[0] / 17797f); y = clamp(v[1] / 11109f);
            boolean down = v[5] != 0;
            boolean present = down || v[6] != 0 || v[7] != 0;
            byte nextTool = v[7] != 0 ? MoonBridge.LI_TOOL_TYPE_ERASER : MoonBridge.LI_TOOL_TYPE_PEN;
            if (inRange && tool != nextTool) releasePen();
            tool = nextTool;
            // A mapped side button switches firmware output from pen to absolute mouse.
            if (down && mouseButtons != 0) { sink.mouse(x, y, 0); mouseButtons = 0; }
            if (touching && !down) {
                send(MoonBridge.LI_TOUCH_EVENT_UP, (byte)0, 0, (short)0, (byte)0);
            }
            double tx = Math.toRadians(v[3]), ty = Math.toRadians(v[4]);
            // AOSP touch tilt calibration: both raw tilt axes are degrees, centered at zero.
            short rotation = (short)((Math.toDegrees(Math.atan2(-Math.sin(tx), Math.sin(ty))) + 360) % 360);
            byte tilt = (byte)Math.min(90, Math.round(Math.toDegrees(
                    Math.acos(Math.max(-1, Math.min(1, Math.cos(tx) * Math.cos(ty)))))));
            if (present) {
                byte event = down ? (touching ? MoonBridge.LI_TOUCH_EVENT_MOVE : MoonBridge.LI_TOUCH_EVENT_DOWN)
                                  : MoonBridge.LI_TOUCH_EVENT_HOVER;
                send(event, (byte)(v[8] & 3), down ? clamp(v[2] / 8191f) : 0, rotation, tilt);
            } else if (inRange) {
                send(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE, (byte)0, 0, (short)0, (byte)0);
            }
            touching = down; inRange = present;
        } else if (fields.length == 5 && fields[0].equals("M")) {
            int[] v = values(fields);
            if (v[3] != 0) {
                sink.scroll(v[3]);
                if ((v[2] & 7) == mouseButtons) return;
            }
            // Wheel-only reports carry stale mouse coordinates while drawing.
            if (touching && (v[2] & 7) == 0) {
                return;
            }
            releasePen();
            x = clamp(v[0] / 32767f); y = clamp(v[1] / 32767f);
            mouseButtons = v[2] & 7;
            sink.mouse(x, y, mouseButtons);
        } else {
            throw new IllegalArgumentException("Invalid Deco reader frame");
        }
    }

    private static int[] values(String[] fields) {
        int[] values = new int[fields.length - 1];
        for (int i = 1; i < fields.length; i++) values[i-1] = Integer.parseInt(fields[i]);
        return values;
    }

    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private void send(byte event, byte buttons, float pressure, short rotation, byte tilt) {
        sink.pen(event, tool, buttons, x, y, pressure, rotation, tilt);
    }
    private void releasePen() {
        if (touching) send(MoonBridge.LI_TOUCH_EVENT_CANCEL, (byte)0, 0, (short)0, (byte)0);
        if (inRange) send(MoonBridge.LI_TOUCH_EVENT_HOVER_LEAVE, (byte)0, 0, (short)0, (byte)0);
        touching = inRange = false;
    }
    public void release() {
        releasePen();
        if (mouseButtons != 0) sink.mouse(x, y, 0);
        mouseButtons = 0;
    }
}
