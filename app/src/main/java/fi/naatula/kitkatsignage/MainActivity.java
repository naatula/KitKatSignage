package fi.naatula.kitkatsignage;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.net.http.SslCertificate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.security.auth.x500.X500Principal;

public class MainActivity extends Activity {

    private static final String TAG = "KitKatSignage";

    private static final long RETRY_DELAY_MS = 15000L;

    /**
     * The URL this device shows, and the only host it may navigate within.
     *
     * Both come from the one-time setup screen rather than being compiled in,
     * and are read once here: the signage view never re-reads them, so a
     * running sign cannot be re-pointed underneath itself.
     */
    private String startUrl;
    private String allowedHost;

    /** Read once in onCreate; see SignageConfig#isDebuggable. */
    private boolean allowCleartext;

    private WebView webView;
    private boolean retryScheduled = false;

    /**
     * The CAs that may have issued the server certificate for the configured
     * host.
     *
     * Loaded from res/raw/letsencrypt_ca.pem, which may contain more than one
     * PEM-encoded certificate concatenated together (e.g. all of Let's
     * Encrypt's currently-active intermediates for a given key type). A
     * server certificate is accepted if it verifies against ANY entry in
     * this list.
     *
     * Each entry must be a DIRECT issuer candidate for the server
     * certificate: WebView only hands us the leaf, so the signature check
     * below can only verify one link, never a full chain. Bundling a root
     * here instead of the intermediate(s) that actually sign your leaf will
     * make every load fail closed.
     *
     * An empty list means no CA could be loaded, in which case every TLS
     * error is treated as fatal.
     */
    private List<X509Certificate> pinnedCas;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startUrl = SignageConfig.getStartUrl(this);

        if (startUrl == null) {
            // Nothing configured yet (first launch, or the app's data was
            // cleared): ask for a URL before putting anything on screen.
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }

        allowedHost = SignageConfig.hostOf(startUrl);
        allowCleartext = SignageConfig.isDebuggable(this);

        enterImmersiveMode();

        pinnedCas = loadPinnedCas();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);

        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new SignageWebViewClient());
        webView.loadUrl(startUrl);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        // Deliberately disabled: the Back button cannot exit signage mode.
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private boolean isAllowedUrl(String url) {
        if (url == null) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        String host = uri.getHost();

        // HTTPS only in a release build. Allowing "http" there would let the
        // WebView navigate to a cleartext page, which never triggers
        // onReceivedSslError, so the pinning check below would simply never
        // run for that navigation. A debuggable build accepts it so the app
        // can be pointed at a LAN dev stack — including the page's own
        // reloads, which are navigations and so pass through this check.
        boolean validScheme = "https".equalsIgnoreCase(scheme)
                || (allowCleartext && "http".equalsIgnoreCase(scheme));

        return validScheme
                && host != null
                && allowedHost != null
                && allowedHost.equalsIgnoreCase(host);
    }

    // ---------------------------------------------------------------
    // Certificate pinning
    // ---------------------------------------------------------------

    private List<X509Certificate> loadPinnedCas() {
        InputStream in = null;

        try {
            in = getResources().openRawResource(R.raw.letsencrypt_ca);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");

            // generateCertificates() (plural) reads every PEM block
            // concatenated in the stream, not just the first one.
            Collection<? extends Certificate> parsed = cf.generateCertificates(in);

            List<X509Certificate> result = new ArrayList<>();

            for (Certificate c : parsed) {
                if (c instanceof X509Certificate) {
                    result.add((X509Certificate) c);
                }
            }

            return result;
        } catch (Throwable e) {
            Log.e(TAG, "Could not load pinned CAs; all TLS errors will be fatal", e);
            return Collections.emptyList();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // Nothing useful to do here.
                }
            }
        }
    }

    /**
     * Decides whether a WebView TLS error is acceptable.
     *
     * Only "unknown certificate authority" is forgiven, and only when the
     * presented certificate really was issued by one of our bundled CAs, is
     * currently valid, and actually belongs to the host we asked for.
     * Everything else (expiry, hostname mismatch, date errors, unknown error
     * codes) stays fatal.
     */
    private boolean isPinnedCertificate(SslError error) {
        if (pinnedCas.isEmpty() || error == null) {
            return false;
        }

        if (error.getPrimaryError() != SslError.SSL_UNTRUSTED) {
            return false;
        }

        if (error.getCertificate() == null) {
            return false;
        }

        X509Certificate server = extractCertificate(error.getCertificate());

        if (server == null) {
            return false;
        }

        try {
            String host = Uri.parse(error.getUrl()).getHost();

            if (host == null || allowedHost == null
                    || !allowedHost.equalsIgnoreCase(host)) {
                return false;
            }

            if (!certificateMatchesHost(server, host)) {
                return false;
            }

            server.checkValidity();
        } catch (Throwable e) {
            // Catches Throwable, not just Exception: this platform has shown
            // it can throw framework-level Errors (see extractCertificate)
            // rather than well-behaved Exceptions, and any such failure here
            // must still fail closed instead of crashing the process.
            Log.w(TAG, "Server certificate failed pinning check", e);
            return false;
        }

        for (X509Certificate ca : pinnedCas) {
            try {
                ca.checkValidity();

                // Throws unless the server certificate was signed by this CA.
                server.verify(ca.getPublicKey());

                return true;
            } catch (Throwable e) {
                // This CA didn't sign it (or isn't valid) — try the next one.
            }
        }

        Log.w(TAG, "Server certificate was not signed by any pinned CA");
        return false;
    }

    /**
     * Extracts the leaf X509Certificate from a WebView SslCertificate
     * without calling SslCertificate#getX509Certificate().
     *
     * That accessor is missing at runtime on at least one deployed device
     * (Philips rpa184, Android 4.4.4 firmware) despite being a standard
     * framework method — calling it throws NoSuchMethodError, which is a
     * LinkageError (an Error, not an Exception), so it is not caught by an
     * ordinary catch (Exception e) block and crashes the process.
     *
     * SslCertificate#saveState() is the mechanism Android itself uses to
     * pass an SslCertificate across process boundaries and has been stable
     * across platform versions. When WebView constructs the SslCertificate
     * from a real server certificate, saveState() packages the original DER
     * bytes into a Bundle under the "x509-certificate" key. Rebuilding the
     * certificate from those bytes sidesteps the broken accessor entirely.
     */
    private X509Certificate extractCertificate(SslCertificate sslCertificate) {
        try {
            Bundle bundle = SslCertificate.saveState(sslCertificate);

            if (bundle == null) {
                return null;
            }

            byte[] derBytes = bundle.getByteArray("x509-certificate");

            if (derBytes == null) {
                return null;
            }

            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(
                    new ByteArrayInputStream(derBytes));
        } catch (Throwable e) {
            Log.w(TAG, "Could not extract server certificate", e);
            return null;
        }
    }

    private boolean certificateMatchesHost(X509Certificate cert, String host)
            throws Exception {

        Collection<List<?>> sans = cert.getSubjectAlternativeNames();

        if (sans != null) {
            for (List<?> san : sans) {
                if (san == null || san.size() < 2) {
                    continue;
                }

                // Type 2 is dNSName.
                if (!Integer.valueOf(2).equals(san.get(0))) {
                    continue;
                }

                Object value = san.get(1);

                if (value instanceof String
                        && hostMatchesPattern(host, (String) value)) {
                    return true;
                }
            }

            // A certificate that has SANs must be matched on its SANs only.
            return false;
        }

        // Legacy certificate with no SAN extension: fall back to the CN.
        String cn = extractCommonName(cert.getSubjectX500Principal());

        return cn != null && hostMatchesPattern(host, cn);
    }

    private boolean hostMatchesPattern(String host, String pattern) {
        if (host == null || pattern == null) {
            return false;
        }

        if (host.equalsIgnoreCase(pattern)) {
            return true;
        }

        // Single leftmost wildcard only: *.example.com matches a.example.com
        // but not example.com and not a.b.example.com.
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1); // ".example.com"
            int dot = host.indexOf('.');

            return dot > 0
                    && host.substring(dot).equalsIgnoreCase(suffix);
        }

        return false;
    }

    private String extractCommonName(X500Principal principal) {
        if (principal == null) {
            return null;
        }

        String dn = principal.getName(X500Principal.RFC2253);

        for (String part : dn.split(",")) {
            String trimmed = part.trim();

            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return trimmed.substring(3).trim();
            }
        }

        return null;
    }

    // ---------------------------------------------------------------
    // Offline handling
    // ---------------------------------------------------------------

    private void showOfflinePage() {
        showMessagePage(
                getString(R.string.app_name),
                getString(R.string.offline_message)
        );
    }

    private void showCertificateErrorPage() {
        showMessagePage(
                getString(R.string.cert_error_title),
                getString(R.string.cert_error_message)
        );
    }

    private void showMessagePage(String heading, String subtext) {
        String html =
                "<!doctype html>"
                        + "<html>"
                        + "<head>"
                        + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                        + "<style>"
                        + "body {"
                        + "margin: 0;"
                        + "height: 100vh;"
                        + "display: flex;"
                        + "align-items: center;"
                        + "justify-content: center;"
                        + "background: #111111;"
                        + "color: #eeeeee;"
                        + "font-family: sans-serif;"
                        + "text-align: center;"
                        + "}"
                        + "h1 { font-size: 30px; font-weight: normal; }"
                        + "p { color: #aaaaaa; font-size: 18px; }"
                        + "</style>"
                        + "</head>"
                        + "<body>"
                        + "<div>"
                        + "<h1>" + heading + "</h1>"
                        + "<p>" + subtext + "</p>"
                        + "</div>"
                        + "</body>"
                        + "</html>";

        webView.loadDataWithBaseURL(
                startUrl,
                html,
                "text/html",
                "UTF-8",
                null
        );

        scheduleRetry();
    }

    private void scheduleRetry() {
        if (retryScheduled) {
            return;
        }

        retryScheduled = true;

        webView.postDelayed(new Runnable() {
            @Override
            public void run() {
                retryScheduled = false;
                webView.loadUrl(startUrl);
            }
        }, RETRY_DELAY_MS);
    }

    private class SignageWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return !isAllowedUrl(url);
        }

        @Override
        public void onReceivedError(
                WebView view,
                int errorCode,
                String description,
                String failingUrl
        ) {
            // This callback fires for ANY failed resource load on this API
            // level, not just the top-level page — including images, CSS,
            // and scripts. Only treat it as "the site is down" when it's the
            // page itself that failed to load; a single flaky image
            // shouldn't blank the whole screen and restart the retry timer.
            if (failingUrl != null && failingUrl.equals(startUrl)) {
                showOfflinePage();
            } else {
                Log.w(TAG, "Non-fatal resource load failure: "
                        + failingUrl + " (" + description + ")");
            }
        }

        @Override
        public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error
        ) {
            if (isPinnedCertificate(error)) {
                handler.proceed();
                return;
            }

            handler.cancel();

            // A cancelled handshake does not reliably reach onReceivedError on
            // this platform version, so surface it here instead of hanging on
            // a black screen.
            showCertificateErrorPage();
        }
    }
}
