package com.prizm;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.HashMap;

@Component
@EnableScheduling
@ConditionalOnProperty(name="prizm.snapshot-alerts.enabled", havingValue="true", matchIfMissing=true)
public class SnapshotAlertWorker {
    private final SnapshotAlertEvents events;
    private final SnapshotCatalogRepository catalog;
    private final AlertStore alerts;
    private final PriceObservations prices;
    public SnapshotAlertWorker(SnapshotAlertEvents events, SnapshotCatalogRepository catalog, AlertStore alerts, PriceObservations prices) {
        this.events = events; this.catalog = catalog; this.alerts = alerts; this.prices = prices;
    }
    @Scheduled(fixedDelay=5000, initialDelay=10000)
    public void check() {
        // Default file mode must remain usable without Firebase emulators.
        if (!"snapshot".equals(catalog.mode())) return;
        SnapshotAlertEvents.Claim claim = null;
        try {
            claim = events.claim();
            if (claim == null) return;
            catalog.refresh();
            var observations = new HashMap<String, PriceObservations.Observation>();
            try (var ignored = catalog.scope()) {
                for (String id : claim.productIds()) observations.put(id, prices.current(id));
            }
            var page = alerts.scanEnabled(claim.cursor(), 10);
            for (var owner : page.items()) {
                var observation = observations.get(owner.id());
                if (observation != null) alerts.evaluate(owner.uid(), owner.id(), observation);
            }
            events.checkpoint(claim, page.nextCursor());
        } catch (RuntimeException ex) {
            if (claim != null) {
                try { events.retry(claim); } catch (RuntimeException ignored) { /* Lease expiry recovers the page. */ }
            }
            LoggerFactory.getLogger(SnapshotAlertWorker.class).warn("저장 스냅샷 알림 평가를 재시도합니다. 에뮬레이터 상태를 확인하세요.");
        }
    }
}
