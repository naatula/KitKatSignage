package fi.naatula.kitkatsignage;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.Uri;

/**
 * The signage URL the device is pinned to, stored in SharedPreferences.
 *
 * The URL is entered once, on first launch (see {@link SetupActivity}), and
 * then kept for the lifetime of the installation: there is deliberately no
 * way to change it from inside the running signage view, because the device
 * is normally mounted out of reach with no input attached. Clearing the app's
 * data (or uninstalling it) is what brings the setup screen back.
 *
 * android:allowBackup="false" in the manifest keeps this out of cloud/ADB
 * backups, so a restore onto another device cannot silently carry the URL —
 * and any access token embedded in it — along.
 */
final class SignageConfig {

    private static final String PREFS_NAME = "signage";
    private static final String KEY_START_URL = "start_url";

    private SignageConfig() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** The configured URL, or null if the app has not been set up yet. */
    static String getStartUrl(Context context) {
        String url = prefs(context).getString(KEY_START_URL, null);

        // Treat a stored-but-unusable value the same as "not configured", so
        // a partially written preference file sends the user back to setup
        // instead of leaving a permanently black screen.
        return normalize(context, url) != null ? url : null;
    }

    static void setStartUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_START_URL, url).commit();
    }

    /**
     * Cleans up user input and accepts it only if it is a usable https URL.
     *
     * A bare host ("example.com/signage") gets an https:// prefix, since that
     * is how people type an address. In a release build http:// is rejected
     * rather than upgraded: a cleartext page never triggers
     * onReceivedSslError, so the certificate check in MainActivity would
     * simply never run for it. A debuggable build accepts it, so the app can
     * be tested against a LAN dev stack that has no certificate at all.
     *
     * @return the normalized URL, or null if the input cannot be used.
     */
    static String normalize(Context context, String input) {
        if (input == null) {
            return null;
        }

        String trimmed = input.trim();

        if (trimmed.length() == 0) {
            return null;
        }

        if (!trimmed.contains("://")) {
            trimmed = "https://" + trimmed;
        }

        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();

        if (!"https".equalsIgnoreCase(scheme)
                && !(isDebuggable(context) && "http".equalsIgnoreCase(scheme))) {
            return null;
        }

        String host = uri.getHost();

        if (host == null || host.length() == 0 || host.contains(" ")) {
            return null;
        }

        return trimmed;
    }

    /**
     * Whether this is a debuggable build, which relaxes the https-only rule
     * both here and in MainActivity's navigation check. A release build never
     * accepts cleartext.
     */
    static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** The host the signage view is allowed to stay within. */
    static String hostOf(String url) {
        return url == null ? null : Uri.parse(url).getHost();
    }
}
