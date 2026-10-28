package survivors.android;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.webkit.WebView;

/**
 * Forwards the controller to the page's `survivorsAndroid` (android.js), which turns it into the
 * game's keys and cursor. The activity hands every gamepad event here before the WebView sees it,
 * so the mapping is the same whether or not this WebView exposes the Gamepad API.
 */
final class PadInput {
    private final WebView web;
    private float lx, ly, rx, ry;
    private int hatX, hatY;

    PadInput(WebView web) {
        this.web = web;
    }

    static boolean isPad(InputDevice dev) {
        if (dev == null) return false;
        int s = dev.getSources();
        return (s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** The page's name for a controller key, or null for a key that isn't the controller's. */
    static String name(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            default: return null;
        }
    }

    /** True when the event was the controller's and has been sent to the page. */
    boolean key(KeyEvent e) {
        String n = name(e.getKeyCode());
        if (n == null) return false;
        // The D-pad keys also come from keyboards; only a controller's go to the pad mapping.
        int k = e.getKeyCode();
        boolean dpad = k >= KeyEvent.KEYCODE_DPAD_UP && k <= KeyEvent.KEYCODE_DPAD_RIGHT;
        if (dpad && !isPad(e.getDevice())) return false;
        if (e.getRepeatCount() > 0) return true;
        if (e.getAction() == KeyEvent.ACTION_DOWN) send(n, true);
        else if (e.getAction() == KeyEvent.ACTION_UP) send(n, false);
        return true;
    }

    /** Sticks, and a D-pad that reports as a hat axis instead of keys. */
    boolean motion(MotionEvent e) {
        if (!isPad(e.getDevice()) || e.getAction() != MotionEvent.ACTION_MOVE) return false;
        float nlx = axis(e, MotionEvent.AXIS_X), nly = axis(e, MotionEvent.AXIS_Y);
        float nrx = axis(e, MotionEvent.AXIS_Z), nry = axis(e, MotionEvent.AXIS_RZ);
        if (nlx != lx || nly != ly || nrx != rx || nry != ry) {
            lx = nlx; ly = nly; rx = nrx; ry = nry;
            js("survivorsAndroid.axes(" + lx + "," + ly + "," + rx + "," + ry + ")");
        }
        int hx = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_X));
        int hy = Math.round(e.getAxisValue(MotionEvent.AXIS_HAT_Y));
        if (hx != hatX) {
            if (hatX < 0) send("LEFT", false);
            if (hatX > 0) send("RIGHT", false);
            if (hx < 0) send("LEFT", true);
            if (hx > 0) send("RIGHT", true);
            hatX = hx;
        }
        if (hy != hatY) {
            if (hatY < 0) send("UP", false);
            if (hatY > 0) send("DOWN", false);
            if (hy < 0) send("UP", true);
            if (hy > 0) send("DOWN", true);
            hatY = hy;
        }
        return true;
    }

    /** A stick axis with the device's own flat zone taken out. */
    private static float axis(MotionEvent e, int axis) {
        InputDevice.MotionRange range = e.getDevice().getMotionRange(axis, e.getSource());
        float v = e.getAxisValue(axis);
        if (range != null && Math.abs(v) <= range.getFlat()) return 0f;
        return Math.round(v * 1000f) / 1000f;
    }

    private void send(String name, boolean down) {
        js("survivorsAndroid.pad('" + name + "'," + down + ")");
    }

    private void js(String code) {
        web.evaluateJavascript("window.survivorsAndroid&&" + code, null);
    }
}
