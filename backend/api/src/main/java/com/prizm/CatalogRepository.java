package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Firestore 실연동 시 같은 계약의 저장소 구현으로 교체한다. */
public interface CatalogRepository {
    List<JsonNode> products();
    List<JsonNode> categories();
    List<JsonNode> offers(String productId);
    List<JsonNode> prices(String productId);
    String referenceTime();
    String dataVersion();
}
