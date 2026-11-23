package com.ansub.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

// Opens the app named in tvdetox://open/<package>, then closes. Only launchable apps (ones with a
// TV or phone launcher entry) can be opened this way.
public class OpenApp extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Uri uri = getIntent() == null ? null : getIntent().getData();
        String pkg = uri == null ? null : uri.getLastPathSegment();
        if (pkg != null && pkg.matches("[A-Za-z0-9_.]+")) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                try {
                    startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                } catch (Exception ignored) {}
            }
        }
        finish();
    }
}
