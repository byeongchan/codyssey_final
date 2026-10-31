package com.prizm;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UsedAnalysisCacheTest {
    private final ObjectMapper mapper=new ObjectMapper();
    @Test void displayOrderAndRepeatedIdsDoNotChangeTheSearchIdentityButRefreshDoes() {
        var scopes=mapper.createArrayNode(); scopes.addObject().put("id","search").put("contentVersion","old-content");
        JsonNode a=mapper.createObjectNode().put("provider","karrot").put("productId","1");
        JsonNode b=mapper.createObjectNode().put("provider","joongna").put("productId","2");
        var original=UsedAnalysisCache.searchInput(scopes,List.of(a,b));
        assertEquals(original,UsedAnalysisCache.searchInput(scopes,List.of(b,a,a)));
        assertNotEquals(original,UsedAnalysisCache.searchInput(scopes,List.of(a)));
        ((com.fasterxml.jackson.databind.node.ObjectNode)scopes.get(0)).put("contentVersion","refreshed-content");
        assertNotEquals(original,UsedAnalysisCache.searchInput(scopes,List.of(a,b)));
    }
    @Test void earliestSourceExpiryWinsAndInvalidOrMissingTimesCannotBeRenewed() {
        var scopes=mapper.createArrayNode();
        Instant older=Instant.now().minusSeconds(7200),newer=Instant.now().minusSeconds(3600);
        scopes.addObject().put("fetchedAt",older.toString()); scopes.addObject().put("fetchedAt",newer.toString());
        assertEquals(older.plusSeconds(86400),UsedAnalysisCache.expires(scopes));
        scopes.addObject().put("fetchedAt","invalid");
        assertEquals(Instant.EPOCH,UsedAnalysisCache.expires(scopes));
        assertEquals(Instant.EPOCH,UsedAnalysisCache.expires(mapper.createArrayNode()));
    }
    @Test void expiredEvidenceIsNotReadFromOrWrittenToFirestore() {
        var firebase=mock(FirebaseAccess.class); var keys=mock(LiveDataCache.class);
        when(keys.key(anyString(),any())).thenReturn("key");
        var result=UsedAnalysisCache.load(firebase,keys,mapper,"kind",Map.of(),Instant.EPOCH,()->mapper.createObjectNode().put("candidateCount",0));
        assertEquals(0,result.path("candidateCount").asInt());
        verifyNoInteractions(firebase);
    }
}
