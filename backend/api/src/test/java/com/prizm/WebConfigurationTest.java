package com.prizm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WebConfigurationTest {
    @Test void acceptsExplicitDeploymentOriginsAndLocalDevelopment() {
        assertDoesNotThrow(() -> new WebConfiguration("https://example.vercel.app, http://127.0.0.1:5173"));
        assertDoesNotThrow(() -> new WebConfiguration(""));
    }
    @Test void rejectsBroadAndMalformedOrigins() {
        for (String origin : new String[]{"*", "https://*.vercel.app", "https://example.com/path", "https://user@example.com", "https://example.com?x=1", "https://example.com#fragment", "file:///tmp"}) {
            assertThrows(IllegalArgumentException.class, () -> new WebConfiguration(origin), origin);
        }
    }
}
