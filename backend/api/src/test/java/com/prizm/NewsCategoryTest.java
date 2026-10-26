package com.prizm;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsCategoryTest {
    @Test void storedCategoryIsPreferredOverProductModel() {
        var topic=NewsCategory.resolve("디지털 > 태블릿","삼성 갤럭시 S25",List.of("digital"));
        assertEquals("태블릿",topic.category());assertEquals("STORED_CATEGORY",topic.basis());
        assertFalse(topic.category().contains("S25"));
    }
    @Test void productTextResolvesCategoryWithoutSendingModelAsQuery() {
        assertEquals("스마트폰",NewsCategory.resolve("","삼성전자 갤럭시S25 256GB",List.of()).category());
        assertEquals("노트북",NewsCategory.resolve("","애플 맥북 에어 M4",List.of()).category());
        assertEquals("이어폰",NewsCategory.resolve("","삼성 갤럭시 버즈",List.of()).category());
    }
    @Test void discoveryFallbackMustBeUnambiguousAndUnknownIsNotSearchedByTitle() {
        assertEquals("가전제품",NewsCategory.resolve("","알 수 없는 모델 123",List.of("appliances")).category());
        assertNull(NewsCategory.resolve("","알 수 없는 모델 123",List.of()));
        assertNull(NewsCategory.resolve("","알 수 없는 모델 123",List.of("appliances","digital")));
    }
    @Test void latinAliasesDoNotMatchInsideUnrelatedWords() {
        assertNull(NewsCategory.resolve("","program accessory",List.of()));
    }
    @Test void gpuModelAndFoodCanChooseDifferentImpactProfiles() {
        assertEquals("그래픽카드",NewsCategory.resolve("","RTX 5070",List.of()).category());
        assertEquals("커피",NewsCategory.resolve("","원두 커피",List.of()).category());
        assertEquals("쌀",NewsCategory.resolve("","백미 10kg",List.of()).category());
    }
}
