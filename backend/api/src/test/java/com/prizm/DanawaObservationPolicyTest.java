package com.prizm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class DanawaObservationPolicyTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Instant now = Instant.parse("2026-10-22T03:00:00Z");
    ObjectNode response(String time) {
        var result = mapper.createObjectNode().put("source", "reef-danawa").put("synthetic", false).put("action", "offers")
            .put("productId", "17788451").put("page", 1).put("hasMore", false).put("receivedAt", now.toString());
        if (time == null) result.putNull("fetchedAt"); else result.put("fetchedAt", time);
        result.putArray("offers").add(mapper.createObjectNode().put("rowId", "0").put("offerKey", "a".repeat(64)).put("availability", "UNKNOWN")
            .put("payment", "card").put("price", 10000).put("shippingCost", 3000).put("reportedTotal", 13000).put("quotedTotal", 13000).put("totalMismatch", false));
        return result;
    }
    @Test void koreanDayCanonicalIdentityAndReplayDoNotInflateSamples() {
        var result = response("2026-10-21T15:00:00Z");
        var prepared = DanawaObservationPolicy.prepare(mapper, result, now);
        assertEquals("2026-10-22", prepared.day()); assertEquals(13000L, prepared.minimum()); assertEquals(1, prepared.samples().size());
        result.put("receivedAt", now.plusSeconds(1).toString());
        var replay = DanawaObservationPolicy.prepare(mapper, result, now.plusSeconds(1));
        assertEquals(prepared.id(), replay.id()); assertEquals(prepared.digest(), replay.digest());
        result.put("page", 2); var nextPage = DanawaObservationPolicy.prepare(mapper, result, now.plusSeconds(1));
        assertNotEquals(prepared.id(), nextPage.id()); assertEquals(prepared.samples(), nextPage.samples());
        assertEquals(false, prepared.content().get("stockVerified")); assertEquals(false, prepared.content().get("sellerOptionVerified"));
    }
    @Test void missingObservationTimeCashAndUnknownShippingDoNotCreateHistorySamples() {
        var missingTime = DanawaObservationPolicy.prepare(mapper, response(null), now);
        assertNull(missingTime.day()); assertNull(missingTime.observedAt()); assertTrue(missingTime.samples().isEmpty());
        var cash = response(now.toString()); ((ObjectNode)cash.path("offers").get(0)).put("payment", "cash");
        assertTrue(DanawaObservationPolicy.prepare(mapper, cash, now).samples().isEmpty());
        var unknown = response(now.toString()); var row = (ObjectNode)unknown.path("offers").get(0); row.putNull("shippingCost"); row.putNull("quotedTotal");
        assertTrue(DanawaObservationPolicy.prepare(mapper, unknown, now).samples().isEmpty());
    }
    @Test void tamperedTotalsSyntheticAndFutureDataAreRejected() {
        var bad = response(now.toString()); ((ObjectNode)bad.path("offers").get(0)).put("quotedTotal", 1);
        assertThrows(ResponseStatusException.class, () -> DanawaObservationPolicy.prepare(mapper, bad, now));
        assertThrows(ResponseStatusException.class, () -> DanawaObservationPolicy.prepare(mapper, response(now.plusSeconds(1).toString()), now));
        assertThrows(ResponseStatusException.class, () -> DanawaObservationPolicy.prepare(mapper, response(now.toString()).put("synthetic", true), now));
    }
}
