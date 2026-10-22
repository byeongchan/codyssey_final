package com.prizm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public interface SnapshotArchive {
    record Entry(String id, String contentDigest, JsonNode content) {}
    Map<String, List<Entry>> read(List<String> productIds);
}
