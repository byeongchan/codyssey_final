# 외부 호출 및 저장 정책

2026-11-03 현재 경로 안내. 초기 개선 제안과 이전 자동 만료 재수집 설명은 [이력](archive/EXTERNAL-CALL-REDUCTION-HISTORY.md)에 보관합니다.

- 실제 경로는 Spring `LiveDataCache`와 공급자별 수집기입니다. fixture의 `CollectionJobs`·스냅샷 선택 경로를 운영 자료로 재사용하지 않습니다.
- 저장 자료를 우선 읽고 미저장 조건 최초 조회 또는 명시적 갱신 시 수집합니다. 오래된 저장 자료는 표시 시각과 함께 유지합니다. 만료만으로 자동 재수집한다고 설명하지 않습니다.
- 공유 작업·임대·진행 상태 조회와 짧은 중복 갱신 제한을 적용합니다. 실제 정책은 [명시적 갱신](MANUAL-PRICE-REFRESH.md)을 우선합니다.
- 기간 이력은 [PERIOD-HISTORY](PERIOD-HISTORY.md), 개인화 질문은 [PRICE-AGENT](PRICE-AGENT.md), 중고 집계 캐시는 [USED-AGENT](USED-AGENT.md)를 확인합니다.
- Render 정의에서 LIVE_REFRESH_ENABLED·알림 스케줄러는 비활성화합니다. 외부 호출 최소화 성능·p95·다중 서버 복구가 검증됐다는 의미는 아닙니다.
- 캐시 적중·병합·최초 조회·갱신·실패 시 외부 요청 수를 별도 측정해야 합니다. 측정 결과를 만들거나 기존 제안의 기대 수치를 실측으로 표시하지 않습니다.
