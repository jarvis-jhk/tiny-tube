package org.tinytube.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The whole networking layer: {@link HttpURLConnection} and nothing else.
 *
 * <p>HttpURLConnection sends {@code Accept-Encoding: gzip} on its own and transparently
 * inflates the response, which is why nothing here touches GZIPInputStream -- InnerTube
 * browse responses are ~330 KB raw but only ~25 KB on the wire.
 */
final class Net {
    private Net() {}

    static final int CONNECT_TIMEOUT = 15000;
    static final int READ_TIMEOUT = 25000;

    /** Desktop UA. Used for RSS and thumbnails, where no client identity is implied. */
    static final String UA_WEB =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/134.0.0.0 Safari/537.36";

    static byte[] get(String url, String userAgent, String... headerPairs) throws IOException {
        return exchange(url, null, userAgent, headerPairs);
    }

    static byte[] post(String url, byte[] body, String userAgent, String... headerPairs)
            throws IOException {
        return exchange(url, body, userAgent, headerPairs);
    }

    static String getText(String url, String userAgent, String... headerPairs) throws IOException {
        return new String(get(url, userAgent, headerPairs), "UTF-8");
    }

    private static byte[] exchange(String url, byte[] body, String userAgent, String... headerPairs)
            throws IOException {
        HttpURLConnection c = open(url, userAgent, headerPairs);
        boolean drained = false;
        try {
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " for " + shortUrl(url));
            }
            byte[] out = drain(c.getInputStream());
            drained = true;
            return out;
        } finally {
            // A body read to EOF puts the socket back in the keep-alive pool, and the next
            // request to that host skips the TCP and TLS handshakes -- ~100-150 ms on the phone,
            // e.g. between the identity warm-up and the player call. disconnect() would close
            // it, so it is only used on the error path.
            if (!drained) c.disconnect();
        }
    }

    static HttpURLConnection open(String url, String userAgent, String... headerPairs)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT);
        c.setReadTimeout(READ_TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", userAgent);
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            c.setRequestProperty(headerPairs[i], headerPairs[i + 1]);
        }
        return c;
    }

    static byte[] drain(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
            byte[] buf = new byte[1 << 15];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (IOException ignored) { }
        }
    }

    /** Strips query strings so googlevideo URLs don't end up in logs or exception text. */
    static String shortUrl(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
