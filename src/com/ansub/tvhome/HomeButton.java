package com.ansub.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

// Opt-in (Settings > Accessibility > Home button): makes Home open this home screen on TVs where the
// TV's own home screen has to stay on, like Google TV and Chromecast. Nothing gets turned off, so
// switching it off puts Home straight back to the TV's own.
//
// Google TV handles the Home key before accessibility services see it, so this doesn't watch keys.
// It watches for Google TV's main screen (not its other pages) to open, after Home or a restart,
// and opens this one over it.
public class HomeButton extends AccessibilityService {
    private static final ComponentName GOOGLE_TV_HOME = new ComponentName(
            "com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
    // Debounce duplicate events until the activity resumes.
    private static long lastOpened = -500;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || !GOOGLE_TV_HOME.getPackageName().equals(String.valueOf(event.getPackageName()))
                || !GOOGLE_TV_HOME.getClassName().equals(String.valueOf(event.getClassName())) || !setupDone()) return;
        SharedPreferences prefs = prefs(this);
        if (stayingOnGoogleTv(prefs)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastOpened < 500) return;

        // Give an unfinished launch time to resume. Successful launches don't delay the next Home.
        long pending = prefs.getLong("pending", -1);
        if (pending >= 0 && now >= pending && now - pending < 3000) return;
        int failures = prefs.getInt("failures", 0);
        if (pending >= 0) failures++;
        if (failures >= 3) {
            prefs.edit().remove("pending").putInt("failures", failures).commit();
            return;
        }
        // Save before launching: a crash can take the service down with the activity.
        if (!prefs.edit().putLong("pending", now).putInt("failures", failures).commit()) return;
        lastOpened = now;
        try {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {}

    // The remote's Settings key normally opens Google TV's quick-settings panel, which is part of
    // Google TV's home screen. With that turned off the key does nothing, so open TV settings instead.
    // Only when that panel can't handle it: with Google TV's home screen on, the key is left alone.
    // Remotes differ in what that key sends: KEYCODE_SETTINGS, or KEYCODE_NOTIFICATION (TCL's
    // RC833 Google TV remote sends the notification key for its gear button).
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (key == KeyEvent.KEYCODE_SETTINGS || key == KeyEvent.KEYCODE_NOTIFICATION) {
            if (enabled(GOOGLE_TV_HOME.getPackageName())) return false;
            if (released(event)) {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                } catch (Exception ignored) {}
            }
            return true;
        }
        // TCL remotes send their own codes for the app buttons, and TCL's partner app turns them into
        // app launches. With that app turned off the buttons do nothing, so open the app here.
        // KEYCODE_BUTTON_3 is also a gamepad button: only take it from the remote, never a controller.
        String app = null;
        if (key == TCL_NETFLIX_KEY) app = "com.netflix.ninja";
        else if (key == TCL_VIDEO_KEY) app = "org.jellyfin.androidtv";
        else if (key == TCL_CHANNEL_KEY) app = "com.crunchyroll.crunchyroid";
        else if (key == KeyEvent.KEYCODE_BUTTON_3 && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)
                && !event.isFromSource(InputDevice.SOURCE_JOYSTICK)) app = "com.google.android.youtube.tv";
        if (app == null || enabled(TCL_APP_KEYS)) return false;
        Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(app);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(app);
        if (launch == null) return false;
        if (released(event)) {
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
            } catch (Exception ignored) {}
        }
        return true;
    }

    // TCL's app-button keys (RC833 remote), and the TCL app that normally handles these keys.
    // The video-icon button and TCL Channel button are remapped here to Jellyfin and Crunchyroll.
    private static final int TCL_NETFLIX_KEY = 4062;
    private static final int TCL_VIDEO_KEY = 4057;
    private static final int TCL_CHANNEL_KEY = 4020;
    private static final String TCL_APP_KEYS = "com.tcl.partnercustomizer";

    private static boolean released(KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled();
    }

    private boolean enabled(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (Exception e) {
            return false;
        }
    }

    // During the TV's first setup, Home is left alone.
    private boolean setupDone() {
        return Settings.Secure.getInt(getContentResolver(), "user_setup_complete", 1) == 1;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home_button", 0);
    }

    // Back on this home screen: Home comes here again.
    static void onHomeResumed(Context c) {
        prefs(c).edit().remove("google_tv_boot").remove("google_tv_boot_count").commit();
    }

    // This home screen opened and didn't crash. Also resets a stopped redirect loop.
    static void onHomeOk(Context c) {
        prefs(c).edit().remove("pending").remove("failures").commit();
        lastOpened = -500;
    }

    private static int bootCount(Context c) {
        return Build.VERSION.SDK_INT >= 24
                ? Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1) : -1;
    }

    private static long bootTime() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    private boolean stayingOnGoogleTv(SharedPreferences prefs) {
        if (!prefs.contains("google_tv_boot")) return false;
        int boot = bootCount(this);
        int saved = prefs.getInt("google_tv_boot_count", -1);
        if (boot >= 0 && saved >= 0) return boot == saved;
        return Math.abs(bootTime() - prefs.getLong("google_tv_boot", 0)) < 5000;
    }

    // Opens Google TV's own home screen and keeps it there. False if this TV doesn't have it.
    static boolean openGoogleTv(Context c) {
        SharedPreferences prefs = prefs(c);
        // Keep the choice through process death and updates, but not a TV restart.
        if (!prefs.edit().putLong("google_tv_boot", bootTime())
                .putInt("google_tv_boot_count", bootCount(c)).commit()) return false;
        try {
            c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(GOOGLE_TV_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            prefs.edit().remove("google_tv_boot").remove("google_tv_boot_count").commit();
            return false;
        }
    }
}
