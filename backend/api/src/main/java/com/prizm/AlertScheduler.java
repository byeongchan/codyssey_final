package com.prizm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name="prizm.alerts.scheduler-enabled", havingValue="true")
public class AlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);
    private final AlertStore store;
    private final PriceObservations prices;
    @org.springframework.beans.factory.annotation.Value("${prizm.provider:fixture}") private String provider = "fixture";
    private String cursor = "";
    public AlertScheduler(AlertStore store, PriceObservations prices) { this.store = store; this.prices = prices; }
    @Scheduled(fixedDelay=60000, initialDelay=10000) public void check() {
        if (!"fixture".equals(provider)) return;
        try {
            var page = store.scanEnabled(cursor); boolean failed = false;
            for (var owner : page.items()) {
                try { store.evaluate(owner.uid(), owner.id(), prices.current(owner.id())); }
                catch (RuntimeException ex) { failed = true; }
            }
            cursor = page.nextCursor();
            if (failed) log.warn("일부 로컬 가격 알림을 점검하지 못했습니다. 에뮬레이터 상태를 확인하세요.");
        } catch (RuntimeException ex) { log.warn("로컬 가격 알림 점검에 실패했습니다. 에뮬레이터 설정과 인덱스를 확인하세요."); }
    }
}
