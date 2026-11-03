package com.prizm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Initial review signals only; never deletes an observation based on its numeric value. */
final class UsedRobustStatistics {
  static double q(double[] sorted, double p) {
    double i = (sorted.length - 1) * p;
    int lo = (int) i, hi = (int) Math.ceil(i);
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
  }

  static ObjectNode signal(ObjectMapper mapper, long candidate, List<Long> reference) {
    var result = mapper.createObjectNode();
    result.put("sampleCount", reference.size());
    result.put("state", "NOT_ENOUGH_REFERENCES");
    result.put("flagged", false);
    if (candidate <= 0) {
      result.put("state", "CANDIDATE_NOT_COMPARABLE");
      return result;
    }
    if (reference.size() < 10 || reference.stream().anyMatch(value -> value <= 0)) return result;
    double[] logs = reference.stream().mapToDouble(Math::log).sorted().toArray();
    double median = q(logs, .5);
    double[] distances = Arrays.stream(logs).map(v -> Math.abs(v - median)).sorted().toArray();
    double mad = q(distances, .5), value = Math.log(candidate);
    result.put("method", "LOG_MAD");
    if (mad > 0) {
      double score = .67448975 * (value - median) / mad;
      result.put("state", "CALCULATED");
      result.put("modifiedZ", score);
      result.put("flagged", Math.abs(score) > 3.5);
    } else {
      double lower = q(logs, .25), upper = q(logs, .75), iqr = upper - lower;
      result.put("method", "LOG_IQR");
      if (iqr <= 0) {
        result.put("state", "ZERO_SPREAD");
        return result;
      }
      result.put("state", "CALCULATED");
      result.put("flagged", value < lower - 1.5 * iqr || value > upper + 1.5 * iqr);
    }
    result.put("direction", value < median ? "LOW" : "HIGH");
    return result;
  }
}
