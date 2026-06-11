package com.example.routesimulator.simulation;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;

public final class MockLocationAccess {
    private MockLocationAccess() {
    }

    public static boolean isAllowed(Context context) {
        AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        if (appOps == null) {
            return false;
        }
        int mode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    context.getApplicationInfo().uid,
                    context.getPackageName()
            );
        } else {
            mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    context.getApplicationInfo().uid,
                    context.getPackageName()
            );
        }
        return mode == AppOpsManager.MODE_ALLOWED;
    }
}
