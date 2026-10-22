# 서버 구매 판단과 설명 계약

코드·테스트 소스·명세를 작성했다. 설치·실행·빌드·테스트는 수행하지 않았다. 기본 fixture 모드에서는 외부 호출이 없다. 선택적 Gemini 모드는 [Gemini 안내](GEMINI.md)를 따른다. 실제 호출은 아직 수행하지 않았다.

## 역할

Spring Boot의 PurchaseDecision이 판단을 결정한다. FastAPI는 서버가 보낸 판단을 설명하는 문장만 반환한다. 공개 응답의 verdict·금액·관측 일수·시각·만료·근거 ID는 Spring이 구성한다. 설명 공급자의 응답으로 이 값을 덮어쓰지 않는다.

규칙 버전은 price-rules-v1, 설명 계약은 explanation-v1, 통합 분석 버전은 fixture-v3다. 모두 분석 입력 캐시 키에 포함한다. 로그인·UID별 작업/캐시·60초/5회 한도는 기존 ANALYSIS-ACCESS.md를 따른다.

| 조건 | 판단 |
| --- | --- |
| 현재 가격 또는 최근 이력 만료 | INSUFFICIENT_DATA |
| 유효 현재가 없음·관측 7일 미만·유효 평균 없음 | INSUFFICIENT_DATA |
| 현재가 ≤ 평균가 × 0.98 | BUY |
| 현재가 ≥ 평균가 × 1.02 | WAIT |
| 나머지 | NEUTRAL |

최근 30일 중 실제 존재하는 일 대표값의 평균을 사용한다. 없는 날짜는 채우지 않는다. 임계값은 십진 곱셈 비교이며 정확히 -2%는 BUY, +2%는 WAIT다. 이 규칙은 과거 관측 대비 위치 설명이며 미래 하락 확률이나 수익 보장이 아니다. 기존 더미 판정 기준은 유지했다.

## 내부 설명 요청과 응답

기존 POST /internal/analyze 경로와 X-Internal-Key 인증을 유지하지만 본문 계약이 변경된다. Spring·FastAPI를 함께 업데이트하고 재시작한다. FastAPI OpenAPI가 새 요청/응답 스키마를 제공한다.

입력 예:

```json
{
  "productId": "sony-xm5-black",
  "observedDays": 30,
  "currentPrice": 384900,
  "averagePrice": 399400,
  "referenceTime": "2026-10-22T03:00:00Z",
  "verdict": "BUY",
  "reasonCode": "BELOW_AVERAGE",
  "ruleVersion": "price-rules-v1",
  "explanationVersion": "explanation-v1",
  "evidenceSnapshotId": "fixture:분석입력해시"
}
```

출력은 productId, ruleVersion, explanationVersion, evidenceSnapshotId, generatedAt, reasons, risks, model, synthetic뿐이다. verdict·금액·observedDays·analysisVersion을 반환하면 Spring이 거부한다. FastAPI는 전달받은 verdict에 대응하는 정해진 더미 문장을 선택하며 가격 비율을 계산하지 않는다.

Spring 검증은 상품·규칙/설명 버전·근거 ID 일치, 생성 시각이 기준 시각과 같은지, 설정된 설명 모델과 synthetic=true, 허용 필드만 있는지, 이유/위험 각 1~5개·문장별 1~500자를 확인한다. 설명에는 숫자를 넣지 않고 금액·일수는 서버의 구조화 필드와 가격 화면에서 제공한다. 숫자 문자 및 일부 보장·재고·할인 예정 문구는 거부한다.

이 검증은 구조와 제한된 표현 검사다. 모든 자연어 환각이나 의미적 모순을 판별하는 검증기가 아니다. 실제 Gemini 도입 시 제공 근거 제한·프롬프트·출력 평가를 추가해야 한다. 현재는 공급자 설정에 따라 fixture-no-gemini-call 또는 정확한 GEMINI_MODEL을 검증한다. Gemini 연결·모드 전환은 GEMINI.md를 따른다.

## 장애와 화면 표시

- FIXTURE_EXPLANATION: 검증된 더미 설명을 사용한다.
- SERVER_FALLBACK: 설명 서비스 중단·타임아웃·잘못된 응답·내부 키 미설정 등의 경우 서버 판단과 기본 설명을 반환한다. 설명 실패만으로 작업을 FAILED로 만들지 않는다.
- SERVER_RULE_ONLY: 판단 보류이면 설명 서비스를 호출하지 않고 서버가 보류 사유를 안내한다.

화면은 서버 규칙 버전과 설명 출처를 표시한다. 기본 설명을 Gemini 결과로 표시하지 않는다. model은 실제 사용한 fixture-no-gemini-call 또는 server-price-rules다. AI 없이도 가격 규칙 판단은 가능하다는 의미이며 분석 장애가 없음을 보장하는 것은 아니다. 입력·가격 조회·작업 큐 자체 오류는 기존 FAILED/HTTP 오류가 될 수 있다.

서버 기본 설명도 기존 15분 캐시에 포함한다. FastAPI를 복구해도 같은 입력을 즉시 재요청하면 기본 설명이 재사용될 수 있다. 정상 설명 복구 검증은 캐시 만료 후 재요청하거나 API를 재시작한다. API 재시작은 메모리 작업을 지우지만 사용자 요청 한도는 지우지 않는다.

## 사용자 직접 실행·검증

README/FIREBASE.md의 기존 순서로 에뮬레이터·FastAPI·API·프론트엔드를 실행한다. 분석은 로그인 계정으로 요청한다.

1. 소니 상세에서 서버 규칙 price-rules-v1과 BUY, 더미 설명 표시를 확인한다. 맥북의 짧은 이력은 판단 보류와 서버 사유여야 한다.
2. 유효 분석 캐시가 없는 상태에서 FastAPI를 종료하고 소니 분석을 요청한다. 완료 상태로 서버 판단과 ‘서버 기본 설명’ 안내가 나와야 한다. 실패 작업으로 표시하거나 Gemini 설명이라고 표시하면 안 된다.
3. FastAPI를 복구하고 캐시 만료 또는 API 재시작 후 다시 요청한다. 설명 출처가 FIXTURE_EXPLANATION으로 복구되어야 한다. 사용자 한도 구간이 남아 있으면 기다린다.
4. 만료 기준 시각 시나리오에서는 FastAPI가 꺼져 있어도 판단 보류·SERVER_RULE_ONLY여야 한다. 모든 가격이 품절·배송비 미확인이어도 판단 보류다.
5. 검증용 FastAPI 공급자를 상품 ID·버전·근거 ID·생성 시각 불일치, verdict 추가, 빈 문장, 숫자 또는 보장 문구 포함 응답으로 바꾸면 SERVER_FALLBACK이 되어야 한다. 서버 verdict·금액이 공급자 값으로 바뀌면 안 된다. 검증 후 원본을 복원한다.
6. 내부 요청의 버전 누락·알 수 없는 버전·시간대 없는 시각·추가 필드는 422여야 한다. 내부 키가 없으면 403이다. 실제 키·토큰은 응답에 노출하지 않는다.

테스트 소스에는 임계값 양쪽과 경계, 부족/만료, 공급자 판정 덮어쓰기·근거 불일치·숫자/보장 문구 차단과 기본 설명, FastAPI의 판단 재계산 금지·입력 검증을 추가했다. 실제 연동은 위 수동 절차로 확인한다.

```powershell
cd D:\github\codyssey_final\backend\api
mvn test
```

```powershell
cd D:\github\codyssey_final\backend\ai
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
```

```powershell
cd D:\github\codyssey_final\frontend
npm run build
```

테스트용 의존성 설치는 README를 따른다. 실행 결과는 아직 없다.
