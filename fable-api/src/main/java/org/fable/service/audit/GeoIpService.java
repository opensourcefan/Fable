package org.fable.service.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class GeoIpService {

    /**
     * PRIVACY NOTE: GeoIP country-code lookup is disabled by default (app.geo-ip.enabled=false).
     * When enabled, each unique visitor IP is sent to ip-api.com over HTTPS.
     * Set GEO_IP_ENABLED=true in your environment only if you need country codes in the
     * audit log and accept the associated privacy trade-off.
     */

    // HTTPS endpoint — prevents interception of user IPs and falsified country codes (OWASP A02).
    private static final String GEO_API_URL = "https://ip-api.com/json/%s?fields=countryCode";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final boolean geoIpEnabled;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Bounded Caffeine cache with TTL: max 5 000 entries (sufficient for typical self-hosted
     * traffic) expiring after 24 hours.  Replaces the previous unbounded ConcurrentHashMap
     * that grew without eviction (memory-leak risk under sustained traffic).
     */
    private final Cache<String, String> cache;

    public GeoIpService(
            @Value("${app.geo-ip.enabled:false}") boolean geoIpEnabled,
            HttpClient httpClient) {
        this.geoIpEnabled = geoIpEnabled;
        this.httpClient = httpClient;
        this.cache = Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofHours(24))
                .build();
        if (!geoIpEnabled) {
            log.info("GeoIP country-code lookup is DISABLED (GEO_IP_ENABLED=false). " +
                    "Country codes will not be recorded in the audit log.");
        }
    }

    /**
     * Resolves the country code for the given IP address.
     * Returns {@code null} immediately when GeoIP is disabled (privacy default).
     */
    public String resolveCountryCode(String ip) {
        if (!geoIpEnabled) {
            return null;
        }
        if (ip == null || ip.isBlank() || isPrivateOrLocal(ip)) {
            return null;
        }
        return cache.get(ip, this::fetchCountryCode);
    }

    private String fetchCountryCode(String ip) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(GEO_API_URL, ip)))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                if (node.has("countryCode") && !node.get("countryCode").asText().isBlank()) {
                    return node.get("countryCode").asText();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Interrupted while resolving country code for IP");
        } catch (Exception e) {
            log.debug("Failed to resolve country code for IP");
        }
        return null;
    }

    private boolean isPrivateOrLocal(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }
}
