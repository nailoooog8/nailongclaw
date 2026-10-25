// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Read-only probe for the Android 16 AppFunctions channel
 * (android.app.appfunctions.AppFunctionManager).
 *
 * Researched 2026-10 (docs/AGENT_PERCEPTION_RESEARCH.md §2): the platform
 * class exists on API 36 and exposes executeAppFunction, but
 * EXECUTE_APP_FUNCTIONS is protected `internal|role` — granted only to
 * preinstalled apps holding the ASSISTANT role. A third-party app therefore
 * cannot execute (or even discover) other apps' functions today.
 *
 * Everything here is reflection on purpose: the class is @FlaggedApi, so a
 * compile-time reference would break on any SDK that hides it, and the probe
 * must never crash on a device where the feature is off. When Google opens
 * the permission (API 37 CINNAMON_BUN adds DISCOVER_APP_FUNCTIONS signs),
 * this probe's honest report flips and the execute path becomes worth
 * building — until then the UI-automation path stays the default.
 */
public final class AppFunctionsProbe {

    private static final String TAG = "AppFunctionsProbe";

    private static final String PLATFORM_MANAGER_CLASS = "android.app.appfunctions.AppFunctionManager";
    private static final String SERVICE_NAME = "app_function";
    private static final String EXECUTE_PERMISSION = "android.permission.EXECUTE_APP_FUNCTIONS";

    private AppFunctionsProbe() {
    }

    /** One-line human-readable availability report for the model and QA. */
    public static String probe(Context ctx) {
        if (Build.VERSION.SDK_INT < 36) {
            return "AppFunctions: unavailable — requires Android 16 (API 36), this device runs API "
                    + Build.VERSION.SDK_INT + ". Keep using the accessibility path for cross-app tasks.";
        }

        Object manager = null;
        try {
            manager = ctx.getSystemService(SERVICE_NAME);
        } catch (Throwable t) {
            XLog.w(TAG, "getSystemService(" + SERVICE_NAME + ") threw", t);
        }

        boolean classPresent = false;
        try {
            classPresent = Class.forName(PLATFORM_MANAGER_CLASS) != null;
        } catch (Throwable ignored) {
        }

        if (manager == null) {
            return "AppFunctions: platform class " + (classPresent ? "present but " : "")
                    + "system service '" + SERVICE_NAME + "' not returned on this build"
                    + (classPresent ? " (feature flag disabled or OEM build)." : ".")
                    + " Keep using the accessibility path for cross-app tasks.";
        }

        boolean granted = false;
        String protection = "unknown";
        try {
            granted = ctx.checkSelfPermission(EXECUTE_PERMISSION)
                    == PackageManager.PERMISSION_GRANTED;
            android.content.pm.PermissionInfo info =
                    ctx.getPackageManager().getPermissionInfo(EXECUTE_PERMISSION, 0);
            protection = describeProtection(info.protectionLevel);
        } catch (Throwable t) {
            XLog.w(TAG, "Permission lookup failed for " + EXECUTE_PERMISSION, t);
        }

        String methods = describeMethods(manager.getClass());

        if (granted) {
            return "AppFunctions: manager available AND " + EXECUTE_PERMISSION + " granted"
                    + " — discovery/execute is possible on this device (" + methods + ")."
                    + " This is unexpected for a third-party app; the execute path is not implemented yet.";
        }
        return "AppFunctions: manager available, but " + EXECUTE_PERMISSION + " is NOT granted"
                + " (protection: " + protection + "; on stock Android 16 it is internal|role —"
                + " assistant-role preinstalled apps only, a regular app cannot obtain it)."
                + " Cross-app tasks must stay on the accessibility/UI path.";
    }

    private static String describeProtection(int protectionLevel) {
        int base = protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE;
        StringBuilder sb = new StringBuilder();
        switch (base) {
            case android.content.pm.PermissionInfo.PROTECTION_NORMAL: sb.append("normal"); break;
            case android.content.pm.PermissionInfo.PROTECTION_DANGEROUS: sb.append("dangerous"); break;
            case android.content.pm.PermissionInfo.PROTECTION_SIGNATURE: sb.append("signature"); break;
            default: sb.append("base=").append(base); break;
        }
        if (protectionLevel != base) {
            sb.append("|flags=0x").append(Integer.toHexString(protectionLevel & ~base));
        }
        return sb.toString();
    }

    /** Which of the three known platform methods this manager instance exposes. */
    private static String describeMethods(Class<?> cls) {
        StringBuilder sb = new StringBuilder("methods:");
        for (String name : new String[]{"executeAppFunction", "isAppFunctionEnabled", "setAppFunctionEnabled"}) {
            boolean found = false;
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (m.getName().equals(name)) { found = true; break; }
            }
            if (found) {
                if (sb.charAt(sb.length() - 1) != ':') sb.append(",");
                sb.append(" ").append(name);
            }
        }
        return sb.toString();
    }
}
