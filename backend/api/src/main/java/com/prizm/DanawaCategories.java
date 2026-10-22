package com.prizm;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Discovery groups, not invented Danawa provider category codes. */
public final class DanawaCategories {
    public static final List<Map<String, String>> ALL = List.of(
        Map.of("id", "", "name", "전체 카테고리", "query", ""),
        Map.of("id", "pc", "name", "PC 부품 · 컴퓨터 주변기기", "query", "PC 부품"),
        Map.of("id", "digital", "name", "스마트폰 · 디지털 기기", "query", "스마트폰"),
        Map.of("id", "appliances", "name", "가전제품", "query", "가전제품"),
        Map.of("id", "fashion", "name", "패션 · 스포츠 상품", "query", "러닝화"),
        Map.of("id", "hobby", "name", "취미 · 레저 상품", "query", "캠핑용품"));
    private DanawaCategories() {}
    public static String validate(String id) {
        if (ALL.stream().noneMatch(row -> row.get("id").equals(id))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 카테고리입니다");
        return id;
    }
}
