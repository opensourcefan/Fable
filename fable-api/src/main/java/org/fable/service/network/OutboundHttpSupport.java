package org.fable.service.network;

import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Centralized outbound HTTP timeout constants and bounded download helpers
 * so no outbound network call can block indefinitely on a dropped/half-open socket.
 */
public final class OutboundHttpSupport {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);
    public static final Duration DOWNLOAD_READ_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration BINARY_DOWNLOAD_READ_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration VERSION_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration VERSION_READ_TIMEOUT = Duration.ofSeconds(10);

    public static final int JSOUP_TIMEOUT_MS = 15_000;
    public static final int DEFAULT_COVER_MAX_BYTES = 20 * 1024 * 1024; // 20 MB

    private OutboundHttpSupport() {
    }

    public static JdkClientHttpRequestFactory buildTimeoutRequestFactory(Duration connectTimeout, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public static JdkClientHttpRequestFactory buildTimeoutRequestFactory(HttpClient httpClient, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    public static InputStream openBoundedUrlStream(String url, Duration connectTimeout, Duration readTimeout) throws IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout((int) connectTimeout.toMillis());
        connection.setReadTimeout((int) readTimeout.toMillis());
        return connection.getInputStream();
    }

    public static byte[] downloadBytes(String url, Duration connectTimeout, Duration readTimeout, int maxBytes) throws IOException {
        try (InputStream in = openBoundedUrlStream(url, connectTimeout, readTimeout);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Downloaded payload exceeded max limit of " + maxBytes + " bytes for URL: " + url);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}

