package org.fable.config.security.oidc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OidcDiscoveryServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void servesStaleCachedDocumentWhenRefreshFailsAndRespectsInvalidate() throws Exception {
        AtomicBoolean shouldFail = new AtomicBoolean(false);
        OidcDiscoveryService.DiscoveryDocument initialDoc = new OidcDiscoveryService.DiscoveryDocument(
                "https://idp.example.com",
                "https://idp.example.com/auth",
                "https://idp.example.com/token",
                URI.create("https://idp.example.com/jwks"),
                "https://idp.example.com/userinfo",
                "https://idp.example.com/logout",
                List.of("openid"),
                List.of("code"),
                List.of("S256"),
                false
        );

        OidcDiscoveryService service = new OidcDiscoveryService() {
            @Override
            DiscoveryDocument fetchDiscoveryDocument(String issuerUri) {
                if (shouldFail.get()) {
                    throw new IllegalStateException("IdP unreachable");
                }
                return initialDoc;
            }
        };

        // Prime cache
        assertThat(service.discover("https://idp.example.com/")).isSameAs(initialDoc);

        // Expire cached entry via reflection to simulate TTL elapsed during an outage
        Field cacheField = OidcDiscoveryService.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ConcurrentMap<String, Object> cache = (ConcurrentMap<String, Object>) cacheField.get(service);
        Object cachedEntry = cache.get("https://idp.example.com");
        Class<?> cachedClass = cachedEntry.getClass();
        var ctor = cachedClass.getDeclaredConstructor(OidcDiscoveryService.DiscoveryDocument.class, Instant.class);
        ctor.setAccessible(true);
        cache.put("https://idp.example.com", ctor.newInstance(initialDoc, Instant.now().minusSeconds(7_200)));

        // IdP outage: stale-while-revalidate must return the cached document instead of throwing
        shouldFail.set(true);
        assertThat(service.discover("https://idp.example.com")).isSameAs(initialDoc);

        // Explicit invalidate must remove cache so next call attempts live fetch and fails
        service.invalidate("https://idp.example.com");
        assertThatThrownBy(() -> service.discover("https://idp.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IdP unreachable");
    }
}

