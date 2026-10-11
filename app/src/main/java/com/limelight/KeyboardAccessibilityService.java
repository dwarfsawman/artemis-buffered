package com.limelight;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityEvent;

import com.limelight.binding.input.ExternalStylusSupport;

import java.util.Arrays;
import java.util.List;

public class KeyboardAccessibilityService extends AccessibilityService
        implements InputManager.InputDeviceListener {

    private static KeyboardAccessibilityService instance;
    private InputManager inputManager;
    private AccessibilityServiceInfo currentInfo;
    private int pointerSources = -1;

    public static void refreshPointerInput() {
        if (instance != null) instance.updatePointerSources();
    }

    private void updatePointerSources() {
        if (Build.VERSION.SDK_INT < 35 || currentInfo == null) return;
        Game game = Game.instance;
        boolean tabletPresent = false;
        if (game != null && game.canReceiveAccessibilityPointerEvents()) {
            for (int id : InputDevice.getDeviceIds()) {
                InputDevice device = InputDevice.getDevice(id);
                if (device != null && ExternalStylusSupport.isDrawingTablet(device.getSources())) {
                    tabletPresent = true;
                    break;
                }
            }
        }
        // This API intercepts a source, not a device. Route all matching mouse
        // events through the foreground Game's normal UI dispatch while a tablet
        // is attached. Screen pens do not match SOURCE_MOUSE and retain their path.
        int requestedSources = tabletPresent ? InputDevice.SOURCE_MOUSE : 0;
        if (pointerSources != requestedSources) {
            currentInfo.setMotionEventSources(requestedSources);
            setServiceInfo(currentInfo);
            pointerSources = requestedSources;
            LimeLog.info("Accessibility pointer input: " + requestedSources);
        }
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        Game game = Game.instance;
        if (game != null && game.canReceiveAccessibilityPointerEvents()) {
            game.dispatchAccessibilityPointerEvent(event);
        } else {
            updatePointerSources();
        }
    }

    //不屏蔽的按键列表
    private final static List BLACKLIST_KEYS = Arrays.asList(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER
    );

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        int action = event.getAction();
        int keyCode = event.getKeyCode();
//        Toast.makeText(getApplicationContext(),"scancode:"+event.getScanCode()+",code:"+event.getKeyCode(),Toast.LENGTH_LONG).show();
        //主要解决系统自带快捷键在pc端无法使用问题 home键 scancode=172 code- 3
        if (Game.instance != null && Game.instance.connected && !BLACKLIST_KEYS.contains(keyCode)) {

            if (action == KeyEvent.ACTION_DOWN) {
                //fix 小米平板esc键按钮映射错误 KEYCODE_BACK=4
                if(event.getScanCode()==1){
                    Game.instance.handleKeyDown(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE));
                    return true;
                }
                Game.instance.handleKeyDown(event);
                return true;
            } else if (action == KeyEvent.ACTION_UP) {
                //fix 小米平板esc键按钮映射错误 KEYCODE_BACK=4
                if(event.getScanCode()==1){
                    Game.instance.handleKeyUp(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE));
                    return true;
                }
                Game.instance.handleKeyUp(event);
                return true;
            }
        }

        return super.onKeyEvent(event);
    }

    @Override
    public void onServiceConnected() {
        LimeLog.info("Keyboard service is connected");
        instance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.packageNames = new String[] { BuildConfig.APPLICATION_ID };
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        currentInfo = info;
        setServiceInfo(info);
        inputManager = getSystemService(InputManager.class);
        if (inputManager != null) {
            inputManager.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));
        }
        updatePointerSources();
    }

    @Override
    public void onDestroy() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(this);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override public void onInputDeviceAdded(int id) { updatePointerSources(); }
    @Override public void onInputDeviceRemoved(int id) { updatePointerSources(); }
    @Override public void onInputDeviceChanged(int id) { updatePointerSources(); }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
//        LimeLog.info("onAccessibilityEvent:"+accessibilityEvent.toString());
    }
    @Override
    public void onInterrupt() {

    }

}
