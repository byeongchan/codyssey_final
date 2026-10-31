import { useEffect, useRef, useState, type FormEvent } from 'react'
import { ApiRequestError, request } from './api'
import { useSession } from './session'
import { Icon } from './Icons'
import Loading from './Loading'

type Provider = 'joongna' | 'karrot'
type ApiProduct = {
  title?: string; price?: number | null; description?: string; status?: string | null; regionName?: string
  categoryName?: string; deliveryOptions?: string; sellerName?: string; mannerScore?: number | null
  createdAt?: string; bumpedAt?: string; imageCount?: number; descriptionTruncated?: boolean
}
type DetailApi = { source?: string; fetchedAt?: string; receivedAt?: string; availableFields: string[]; missingFields: string[] }
type ProductGroup = {
  kind?: 'MODEL' | 'CATEGORY'; name?: string; count: number; analyzedCount: number; excludedCount: number
  low?: number; average?: number; high?: number; median?: number; smallSample: boolean; basis: string; unknownStatusCount?: number
  dominantRanges: { from: number; to: number; count: number; share: number }[]
}
type RegionCounts = {
  total: number; known: number; unknown: number; knownPercent: number; unknownPercent: number; distinctRegions: number
  regions: { name: string; count: number; percent: number }[]; otherKnownCount: number
}
type RegionSummary = RegionCounts & { platforms: (RegionCounts & { provider: Provider })[]; basis: string; limitation: string }
type DetailReport = {
  purchaseJudgment?: { summary: string; scope: string; confirmedEvidence: string[]; reviewRequired: string[]; basis: string; priceReference?: { basis: string; name: string; count: number; listedPrice: number; low?: number; average?: number; high?: number; centralLow?: number; centralHigh?: number; smallSample: boolean } }
  comparisonJudgment?: string
  finalJudgment: string; analysisAt: string; scope: string; lowestCandidateMessage: string
  counts: { observed: number; deduplicated: number; duplicateObservations: number; detailsAvailable: number; conditionMatched: number; validPriceObserved: number; statisticsUsed: number; recommended: number; independenceVerified: boolean }
  selectedFacts: { intent: string; priceMeaning: string; validPrice: boolean; listedPrice: number | null; askingPrice: number | null; status: string; observedAt: string; tradeMethod: string; fields: Record<string, { value: string; quote: string; basis: string }>; reasons: string[]; priceQuote: string; costs: { shipping: number | null; fee: number | null; total: number | null; minimumKnown?: number; unconfirmed: string[] } }
  comparison: { observedFrom?: string; observedTo?: string; count: number; basis: string; median?: number; q25?: number; q75?: number; differencePercent?: number; knownSellerCount: number; sellerUnknownListings: number; maxKnownSellerSharePercent: number; platformCounts: Record<string, number>; reviewSignalCount: number; medianWithoutSignals?: number }
  quality: { level: string; basis: string }; outlier: { state: string; flagged: boolean; method?: string; modifiedZ?: number; direction?: string }
  sources: { provider: string; productId: string; title: string; url?: string; quote: string; region: string; observedAt: string }[]
  exclusionReasons: { reason: string; count: number }[]; checks: string[]
  lowestAskingCandidate?: { title: string; askingPrice: number; basis: string; url?: string } | null
  lowestTotalCandidate?: { title: string; total: number; method: string }
}
export type UsedAgentContext = {
  searches: { provider: Provider; id: string }[]
  candidates: { provider: Provider; productId: string }[]
}
type Answer = {
  answer: string; headline: string; decision: string; source: string; aiUnavailable: boolean
  evidenceVersion: string; fetchedAt: string | null; generatedAt: string
  candidateCount: number; sameTitleCount: number; comparisonBasis: string
  low?: number; median?: number; high?: number
  mode?: string; average?: number; analyzedCount?: number; excludedCount?: number; lowPriceExcludedCount?: number
  centralLow?: number; centralHigh?: number; unknownStatusCount?: number
  regionSummary?: RegionSummary
  productGroups?: { groups: ProductGroup[]; total: number; unclassifiedCount: number; groupCount: number; omittedGroupCount: number; basis: string }
  selectedSearchGroup?: ProductGroup
  currentPrice?: number; referenceCount?: number; pricePosition?: string; priceEvaluation?: string; checks?: string[]; searchAnalysis?: Answer
  detailReport?: DetailReport
  product?: ApiProduct; detailApi?: DetailApi
  distribution?: { from: number; to: number; count: number; share: number; dominant: boolean }[]
  excludedListings?: { provider: Provider; productId: string; title: string; price: number | null; reason: string }[]
  limitations: string[]; questions: string[]; sellerNotes: { quote: string }[]
  searchScopes: { provider: Provider; query?: string; regionName?: string; fetchedAt?: string }[]
}
type Message = { role: 'user'; content: string } | { role: 'assistant'; content: string; answer: Answer }
const money = (value: number) => `${value.toLocaleString('ko-KR')}원`
const suggestions = ['이 매물에서 확인할 것은?', '검색 가격 기준으로 적절한 가격이야?', '판매자 설명을 정리해 줘']
const searchSuggestions = ['모델·상품 종류별 가격대를 분석해 줘', '검색 결과 가격대를 분석해 줘', '어느 지역의 매물이 많아?', '지역 정보가 없는 매물은 얼마나 돼?', '어떤 가격이 분석에서 제외됐어?']
const observedTime = (value: string) => value ? new Date(value).toLocaleString('ko-KR') : '미확인'
const labels: Record<string, string> = { SALE: '판매', GIVEAWAY: '무료 나눔', BUY_REQUEST: '구매 희망', EXCHANGE: '교환', RENTAL: '대여', SERVICE: '서비스', UNKNOWN: '미확인', ASKING_PRICE: '상품 제시가', NEGOTIABLE: '협상 가능한 제시가', UNDETERMINED: '금액 미정', TEMPORARY: '임시 가격', PARTIAL_AMOUNT: '부분 금액', STARTING_PRICE: '옵션·시작 가격', CONFLICT: '가격 충돌', NOT_PAID_SALE: '일반 유료 판매가 적용 대상 아님', on_sale: '판매 중', reserved: '예약 중', sold: '판매 완료', INSUFFICIENT: '확인 자료 제한', LOW: '낮음', NORMAL: '보통' }

function DetailEvidence({ data, product, api }: { data: DetailReport; product?: ApiProduct; api?: DetailApi }) {
  const facts = data.selectedFacts, comparison = data.comparison, costs = facts.costs
  return <details><summary>구매 분석 근거 보기</summary><div className="mt-3 space-y-4">
    <section><h4 className="font-bold">1. 최종 판단</h4><p>{data.finalJudgment}</p>
      {data.purchaseJudgment?.priceReference && <div className="my-2 rounded-xl border border-[var(--line)] p-3"><p className="font-bold">{data.purchaseJudgment.priceReference.name || data.purchaseJudgment.priceReference.basis} · 검색 참고 {data.purchaseJudgment.priceReference.count}개</p>
        <p>선택 상품 {money(data.purchaseJudgment.priceReference.listedPrice)}</p>
        <p>{typeof data.purchaseJudgment.priceReference.low === 'number' && <>최저 {money(data.purchaseJudgment.priceReference.low)} </>}{typeof data.purchaseJudgment.priceReference.average === 'number' && <>· 평균 {money(data.purchaseJudgment.priceReference.average)} </>}{typeof data.purchaseJudgment.priceReference.high === 'number' && <>· 최고 {money(data.purchaseJudgment.priceReference.high)}</>}</p>
        {typeof data.purchaseJudgment.priceReference.centralLow === 'number' && typeof data.purchaseJudgment.priceReference.centralHigh === 'number' && <p>중앙 50% 가격대 {money(data.purchaseJudgment.priceReference.centralLow)} ~ {money(data.purchaseJudgment.priceReference.centralHigh)}</p>}
        <p className="text-[var(--muted)]">{data.purchaseJudgment.priceReference.smallSample ? '소수 관측 자료입니다. 가격 적절성을 확정하지 않고 참고해 주세요.' : '옵션·상태·구성 차이가 포함된 검색 가격입니다. 구매 판단의 참고로 활용해 주세요.'}</p></div>}
      {data.purchaseJudgment && <div className="mt-2 space-y-2"><div><h5 className="font-bold">판단에 사용한 확인된 근거</h5><ul className="list-disc pl-4">{data.purchaseJudgment.confirmedEvidence.map((item, i) => <li key={i}>{item}</li>)}</ul></div>
        {data.purchaseJudgment.reviewRequired.length > 0 && <div><h5 className="font-bold">미확인 사항 · 추가 검토 필요</h5><ul className="list-disc pl-4">{data.purchaseJudgment.reviewRequired.map((item, i) => <li key={i}>{item}</li>)}</ul></div>}
        <p className="text-[var(--muted)]">{data.purchaseJudgment.basis}</p></div>}
      <details className="mt-2"><summary>동일 조건 가격 비교의 범위</summary><p>위 상품 정보 분석과 별도로, 같은 옵션·상태의 가격 비교가 가능한지 확인한 결과입니다.</p>{data.counts.statisticsUsed > 0 ? <p>동일 조건 가격 표본 {data.counts.statisticsUsed}개</p> : <p>현재 상담에 연결된 자료에서 동일 조건 가격 표본을 확보하지 못했습니다. 등록된 상품 정보가 잘못되었다는 의미는 아닙니다.</p>}{data.lowestAskingCandidate && <p>{data.lowestAskingCandidate.basis}: {data.lowestAskingCandidate.title} · {money(data.lowestAskingCandidate.askingPrice)}</p>}{data.lowestTotalCandidate && <p>동일 거래 방식 총비용 기준 후보: {data.lowestTotalCandidate.title} · {money(data.lowestTotalCandidate.total)} ({data.lowestTotalCandidate.method})</p>}</details></section>
    <section><h4 className="font-bold">2. 상세 API에서 제공한 상품 정보</h4>
      {product?.title && <p className="font-bold">{product.title}</p>}
      {typeof product?.price === 'number' && <p>등록 가격: {money(product.price)}</p>}
      {product?.status && <p>판매 상태: {labels[product.status] ?? product.status}</p>}
      {product?.categoryName && <p>카테고리: {product.categoryName}</p>}
      {product?.regionName && <p>매물 지역: {product.regionName}</p>}
      {product?.deliveryOptions && <p>거래 방식: {product.deliveryOptions}</p>}
      {product?.sellerName && <p>판매자 표시 이름: {product.sellerName}</p>}
      {typeof product?.mannerScore === 'number' && <p>플랫폼 매너 지표: {product.mannerScore} · 거래 안전 보증이 아닙니다.</p>}
      {typeof product?.imageCount === 'number' && <p>제공 이미지 {product.imageCount}개 · AI 사진 판독은 수행하지 않았습니다.</p>}
      {product?.createdAt && <p>게시: {observedTime(product.createdAt)}</p>}
      {product?.bumpedAt && <p>갱신/끌어올림: {observedTime(product.bumpedAt)}</p>}
      {api?.fetchedAt && <p>원본 확인: {observedTime(api.fetchedAt)}</p>}
      {api?.receivedAt && <p>API 수신: {observedTime(api.receivedAt)}</p>}
      {product?.description && <details><summary>AI가 참고한 상품 설명</summary><p className="max-h-64 overflow-y-auto whitespace-pre-wrap break-words">{product.description}</p></details>}
      {product?.descriptionTruncated && <p>API에서 전달한 상품 설명은 길이 제한으로 일부만 포함되어 있습니다.</p>}
      {!product?.description && <p className="text-[var(--muted)]">제공된 API 자료에 상품 설명이 없어 나머지 등록 정보를 참고했습니다.</p>}
      <details className="mt-2"><summary>정밀 비교의 가격·비용 판정</summary>
        <p>가격 의미: {labels[facts.priceMeaning] ?? facts.priceMeaning} · 등록가격의 제공 여부와는 별도 판정입니다.</p>
        {facts.priceQuote && <blockquote>금액 근거: {facts.priceQuote}</blockquote>}
        <p>필수 총비용: {typeof costs.total === 'number' ? money(costs.total) : '확정되지 않음'}</p>
        {costs.unconfirmed.length > 0 && <p>추가 비용 확인 항목: {costs.unconfirmed.join(', ')}</p>}
        {facts.reasons.map((reason, index) => <p key={index}>• {reason}</p>)}
      </details>
    </section>
    <section><h4 className="font-bold">3. 비교 근거</h4>{data.counts.statisticsUsed > 0 ? <><p>{comparison.basis}</p><p>관측 {data.counts.observed}개 → ID 중복 정리 {data.counts.deduplicated}개 → 명시 조건 일치 {data.counts.conditionMatched}개 → 실제 통계 {data.counts.statisticsUsed}개</p><p>별도 단계: 본문 확인 {data.counts.detailsAvailable}개 · 유효 가격 {data.counts.validPriceObserved}개 · 검토 통과 후보 {data.counts.recommended}개</p><p>공개 판매자 계정 식별값 {comparison.knownSellerCount}개 · 판매자 미확인 매물 {comparison.sellerUnknownListings}개</p><p>참조 플랫폼: {Object.entries(comparison.platformCounts).map(([provider, count]) => `${provider === 'karrot' ? '당근' : '중고나라'} ${count}개`).join(' · ')}</p><p>확인된 판매자 기준 최대 비중: {comparison.maxKnownSellerSharePercent.toLocaleString('ko-KR', { maximumFractionDigits: 1 })}% · 미확인 판매자가 있으면 실제 편중을 확정하지 않습니다.</p>{comparison.observedFrom && <p>가격·상태 관측 범위: {observedTime(comparison.observedFrom)} ~ {observedTime(comparison.observedTo ?? '')}</p>}<p>분석 품질: {labels[data.quality.level] ?? data.quality.level}</p><p className="text-[var(--muted)]">{data.quality.basis}</p>{typeof comparison.median === 'number' ? <p>참고 제시가 중앙값 {money(comparison.median)} · 중간 50% {money(comparison.q25!)} ~ {money(comparison.q75!)}</p> : <p>같은 조건의 통계 표본이 5개 미만이므로 중앙값·사분위 범위는 계산하지 않았습니다. 확인된 원문 정보는 위에서 확인할 수 있습니다.</p>}
      {data.exclusionReasons.map(reason => <p key={reason.reason}>{reason.reason}: {reason.count}개</p>)}<p>집계한 제외 사유는 매물별 우선 사유 1개로 중복 합산하지 않았습니다.</p>
    </> : <p>현재 연결된 검색 후보 {data.counts.deduplicated}개 중 동일 조건으로 가격을 비교할 표본은 확보하지 못했습니다. 검색 전체 가격대와 모델·상품 종류별 참고 가격은 별도로 확인할 수 있습니다.</p>}</section>
    <section><h4 className="font-bold">4. 저렴하거나 비싼 정도</h4>{typeof comparison.differencePercent === 'number' ? <p>동일 기준 비교 제시가 중앙값보다 {Math.abs(comparison.differencePercent).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}% {comparison.differencePercent >= 0 ? '낮습니다' : '높습니다'}. 상품가 기준이며 총비용 차이율이 아닙니다.</p> : <p>동일 조건 표본 수·분석 품질이 충분하지 않거나 미확정 조건이 있어 가격 차이율은 제시하지 않았습니다.</p>}<p>{data.outlier.flagged ? '통계상 추가 검토 신호가 있습니다. 사기·가격 오류로 확정하거나 숫자만으로 통계에서 삭제하지 않았습니다.' : data.outlier.state === 'CALCULATED' ? '현재 기준의 통계 신호는 관측되지 않았습니다. 거래 안전을 보장하지는 않습니다.' : '표본 수나 산포가 충분하지 않아 통계적 이상 신호는 판단하지 않았습니다.'}</p>{typeof comparison.medianWithoutSignals === 'number' && <p>신호를 참고로 제외했을 때의 중앙값: {money(comparison.medianWithoutSignals)}. 기본 중앙값은 해당 가격을 포함합니다.</p>}</section>
    <section><h4 className="font-bold">5. 실제 사용한 근거 자료</h4>{data.sources.map(source => <div key={`${source.provider}:${source.productId}`} className="mt-2"><p>{source.url ? <a className="underline" href={source.url} target="_blank" rel="noopener noreferrer">{source.title}</a> : source.title} · {source.provider === 'karrot' ? '당근' : '중고나라'} / {source.productId}</p>{source.quote && <blockquote className="text-[var(--muted)]">{source.quote}</blockquote>}<p>지역 {source.region || '미확인'} · 확인 {observedTime(source.observedAt)}</p></div>)}</section>
    <section><h4 className="font-bold">6. 구매 전 확인 사항</h4><ul>{data.checks.slice(0, 5).map((check, index) => <li key={index}>{check}</li>)}</ul></section>
    <section><h4 className="font-bold">7. 한계와 확인 시점</h4><p>게시된 판매 희망가 기준입니다. 상품 진위·성능·최종 체결가·거래 안전을 검증한 결과가 아닙니다.</p><p>{data.scope}</p><p>선택 매물 확인: {observedTime(facts.observedAt)} · 분석 생성: {observedTime(data.analysisAt)}</p><p>최저 후보 강조에는 6시간, 현재 판매 제시가 통계에는 24시간 이내 확인을 사용하며 최신 확인도 현재 거래를 보장하지 않습니다.</p></section>
  </div></details>
}

function RegionDistribution({ data }: { data: RegionSummary }) {
  return <div className="space-y-2 border-t border-[var(--line)] pt-3 text-[10px] leading-5">
    <h4 className="text-xs font-bold">지역 정보 분석</h4>
    <p>전체 {data.total}개 · 지역 제공 {data.known}개 ({data.knownPercent}%) · 미제공 {data.unknown}개 ({data.unknownPercent}%)</p>
    <div className="overflow-x-auto"><table className="w-full text-left"><caption className="sr-only">플랫폼별 매물 지역 정보 제공 현황</caption><thead><tr className="text-[var(--muted)]"><th scope="col" className="py-1">플랫폼</th><th scope="col">전체</th><th scope="col">지역 제공</th><th scope="col">지역 미제공</th></tr></thead><tbody>{data.platforms.map(platform => <tr key={platform.provider} className="border-t border-[var(--line)]"><th scope="row" className="py-2 pr-2">{platform.provider === 'joongna' ? '중고나라' : '당근'}</th><td>{platform.total}개</td><td>{platform.known}개<br/><span className="text-[var(--muted)]">{platform.knownPercent}%</span></td><td>{platform.unknown}개<br/><span className="text-[var(--muted)]">{platform.unknownPercent}%</span></td></tr>)}</tbody></table></div>
    <p className="text-[var(--muted)]">가격 분석에서 제외한 매물도 지역 집계에는 포함합니다. 비율은 전체 또는 각 플랫폼의 전체 건수 기준입니다.</p>
    <details><summary className="cursor-pointer font-bold">플랫폼별 지역 분포 보기</summary>
      {data.platforms.map(platform => <div key={platform.provider} className="mt-3"><p className="font-bold">{platform.provider === 'joongna' ? '중고나라' : '당근'} · 지역명 {platform.distinctRegions}종</p>
        {platform.regions.length > 0 ? <ul className="mt-1 space-y-1">{platform.regions.map(region => <li key={region.name} className="flex justify-between gap-2"><span>{region.name}</span><span className="shrink-0">{region.count}개 · {region.percent}%</span></li>)}</ul> : <p className="text-[var(--muted)]">{platform.total ? '제공된 지역명이 없습니다.' : '현재 분석 대상 매물이 없습니다.'}</p>}
        {platform.otherKnownCount > 0 && <p className="mt-1">그 외 지역 제공 매물 {platform.otherKnownCount}개 · 상위 20개 지역명 표시</p>}
      </div>)}
      <p className="mt-3 text-[var(--muted)]">{data.limitation}</p>
    </details>
  </div>
}

function ProductGroupCard({ group }: { group: ProductGroup }) {
  return <div className="rounded-xl border border-[var(--line)] p-3 text-[10px] leading-5">
    <p className="text-xs font-bold">{group.name} <span className="font-normal text-[var(--muted)]">{group.kind === 'MODEL' ? '모델별' : '상품 종류별'}</span></p>
    <p>조회 {group.count}개 · 가격 분석 {group.analyzedCount}개 · 제외 {group.excludedCount}개</p>
    {!!group.unknownStatusCount && <p className="text-[var(--muted)]">판매 상태 미제공 {group.unknownStatusCount}개 포함</p>}
    {typeof group.low === 'number' ? <><p>최저 {money(group.low)} · 평균 {money(group.average!)} · 최고 {money(group.high!)}</p>
      {typeof group.median === 'number' && <p>중앙값 {money(group.median)}</p>}
      {!group.smallSample && group.dominantRanges?.length > 0 && <p className="font-bold">매물이 많은 구간{group.dominantRanges.length > 1 ? ' (동률)' : ''}: {group.dominantRanges.map(range => `${money(range.from)} ~ ${money(range.to)} 미만 · ${range.count}개`).join(' / ')}</p>}
      {group.smallSample && <p className="text-[var(--muted)]">소수 표본의 관측 가격입니다. 안정적인 가격 형성대로 판단하기에는 자료가 적습니다.</p>}</> : <p>이 그룹에서 가격 분석에 사용할 수 있는 매물이 없습니다.</p>}
    <p className="mt-1 text-[var(--muted)]">{group.basis}</p>
  </div>
}

function Distribution({ data }: { data: Answer }) {
  const maxCount = Math.max(1, ...(data.distribution ?? []).map(bin => bin.count))
  return <div className="used-agent-distribution">
    <p className="text-xs font-black">{data.headline}</p>
    <dl>{([['최저가', data.low], ['평균가', data.average], ['최고가', data.high]] as const).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{typeof value === 'number' ? money(value) : '—'}</dd></div>)}</dl>
    <p className="text-[10px] text-[var(--muted)]">분석 {data.analyzedCount ?? 0}개 · 제외 {data.excludedCount ?? 0}개{data.lowPriceExcludedCount ? ` (저가 의심 ${data.lowPriceExcludedCount}개)` : ''}</p>
    {!!data.unknownStatusCount && <p className="text-[10px] text-[var(--muted)]">판매 상태 미확인 {data.unknownStatusCount}개 포함</p>}
    {typeof data.centralLow === 'number' && <p className="text-xs font-bold">중앙 50% 가격대 {money(data.centralLow)} ~ {money(data.centralHigh!)}</p>}
    <div role="list" aria-label="검색 매물 가격 분포">{data.distribution?.map(bin => <div key={bin.from} role="listitem" className="used-agent-bin"><span>{money(bin.from)} ~ {money(bin.to)} 미만</span><div className="used-agent-bar" aria-hidden="true"><span className={bin.dominant ? 'is-dominant' : ''} style={{ width: `${bin.count / maxCount * 100}%` }}/></div><span>{bin.count}개 · {bin.share}%</span></div>)}</div>
    {!!data.distribution?.length && <p className="text-[10px] text-[var(--muted)]">강조된 막대는 매물이 가장 많은 구간입니다. 여러 구간이 동률일 수 있습니다.</p>}
    {data.regionSummary && <RegionDistribution data={data.regionSummary}/>}
    {data.productGroups && <div className="mt-3 space-y-2 border-t border-[var(--line)] pt-3 text-[10px] leading-5">
      <h4 className="text-xs font-bold">모델·상품 종류별 가격대</h4>
      <p>{data.productGroups.groupCount}개 그룹 · 분류 불명확 {data.productGroups.unclassifiedCount}개</p>
      <p className="text-[var(--muted)]">용량·색상·구성품으로 나누지 않은 참고 가격입니다. 모델명이 없으면 상품 종류로 분류합니다.</p>
      <div className="max-h-96 space-y-2 overflow-y-auto">{data.productGroups.groups.map(group => <ProductGroupCard key={`${group.kind}:${group.name}`} group={group}/>)}</div>
      {data.productGroups.omittedGroupCount > 0 && <p>매물 수가 많은 상위 40개 그룹 표시 · 그 외 {data.productGroups.omittedGroupCount}개 그룹</p>}
      <p className="text-[var(--muted)]">{data.productGroups.basis}</p>
    </div>}
    <details className="text-[10px] leading-5"><summary className="cursor-pointer font-bold">제외 기준과 분석 한계</summary><ul className="mt-2 space-y-1">{data.limitations.map((text, i) => <li key={i}>• {text}</li>)}</ul>
      {(data.excludedCount ?? 0) > (data.excludedListings?.length ?? 0) && <p className="mt-2">제외 매물은 최대 40개까지 표시합니다.</p>}
      {(data.excludedListings?.length ?? 0) > 0 && <ul className="mt-3 max-h-48 space-y-2 overflow-y-auto" aria-label="분석 제외 매물">{data.excludedListings?.map(item => <li key={`${item.provider}:${item.productId}`}><strong>{item.title || <>{item.provider === 'karrot' ? '당근' : '중고나라'} · {item.productId}</>}</strong><br/>{item.price === null ? '가격 미확인' : money(item.price)} · {item.reason}</li>)}</ul>}
    </details>
  </div>
}

function Conversation({ provider, productId, title, context, listing, initialVersion, onReset }: { provider?: Provider; productId?: string; title: string; context: UsedAgentContext; listing: boolean; initialVersion?: string; onReset?: () => void }) {
  const [messages, setMessages] = useState<Message[]>([]), [text, setText] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [changed, setChanged] = useState(false)
  const version = useRef<string | null>(initialVersion ?? null), active = useRef<AbortController | null>(null)
  const lock = useRef(false)
  const bottom = useRef<HTMLDivElement>(null)
  useEffect(() => () => active.current?.abort(), [])
  useEffect(() => { bottom.current?.scrollIntoView({ block: 'nearest' }) }, [messages, busy])
  function reset() { active.current?.abort(); lock.current = false; version.current = null; setMessages([]); setError(''); setChanged(false); setBusy(false); onReset?.() }
  async function send(question: string) {
    const message = question.trim(); if (!message || lock.current || changed) return
    lock.current = true
    const abort = new AbortController(); active.current = abort; setBusy(true); setError('')
    try {
      const history = messages.slice(-6).map(row => ({ role: row.role, content: row.content.slice(0, 1000) }))
      const response = await request<{ data: Answer }>(listing ? '/me/used/agent/search/messages' : `/me/used/agent/${provider}/${productId}/messages`, abort.signal, 'POST', {
        message, ...context, history, ...(version.current ? { evidenceVersion: version.current } : {}),
      })
      if (abort.signal.aborted) return
      if (response.data.aiUnavailable) throw new Error('답변 생성을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      version.current = response.data.evidenceVersion
      setMessages(previous => [...previous, { role: 'user', content: message }, { role: 'assistant', content: response.data.answer, answer: response.data }].slice(-20) as Message[])
      setText('')
    } catch (e) {
      if (!abort.signal.aborted) {
        if (e instanceof ApiRequestError && e.status === 409) setChanged(true)
        setText(message)
        setError(e instanceof Error ? e.message : '상담 요청에 실패했습니다.')
      }
    } finally { if (active.current === abort) { lock.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  function submit(event: FormEvent) { event.preventDefault(); void send(text) }
  return <>
    <div className={`chat-scroll ${listing ? 'h-[300px] sm:h-[340px]' : 'h-[400px] sm:h-[480px]'} space-y-4 overflow-y-auto overscroll-contain p-5`} role="log" aria-label="중고 매물 상담 대화" aria-live="polite">
      <div className="rounded-2xl bg-[var(--surface-2)] p-4 text-xs leading-6"><p className="font-black">{title}</p><p className="mt-2 text-[var(--muted)]">{listing ? '검색된 매물의 가격 분포와 이상 저가 제외 결과를 질문해 주세요. 다른 모델·상태가 섞인 검색 결과이므로 적정 시세로 단정하지 않습니다.' : '상세 설명에서 확인할 내용과 앞서 검색한 가격대 안에서 이 매물의 가격 위치를 함께 살펴봅니다.'}</p></div>
      {messages.map((message, i) => message.role === 'user'
        ? <div key={i} className="flex justify-end"><p className="max-w-[95%] whitespace-pre-wrap break-words rounded-2xl rounded-br-md bg-[var(--primary)] px-4 py-3 text-xs leading-6 text-white">{message.content}</p></div>
        : <div key={i} className="flex justify-start"><article className="used-agent-reply w-full max-w-[95%] rounded-2xl rounded-bl-md bg-[var(--surface-2)] px-4 py-3 text-xs leading-6"><strong>{message.answer.headline}</strong><p>{message.content}</p>
          {!listing && !message.answer.detailReport && message.answer.priceEvaluation && <div className="rounded-xl border border-[var(--line)] p-3"><h4 className="font-bold">검색 가격 기준 검토</h4><p className="mt-1">{message.answer.priceEvaluation}</p></div>}
          {!listing && !message.answer.detailReport && !!message.answer.checks?.length && <div><h4 className="font-bold">이 매물에서 확인할 사항</h4><ul>{message.answer.checks.map((check, n) => <li key={n}>{check}</li>)}</ul></div>}
          {message.answer.sellerNotes.length > 0 && <div className="used-agent-quotes"><h4>판매자 설명에서 확인한 내용</h4>{message.answer.sellerNotes.map((note, n) => <blockquote key={n}>{note.quote}</blockquote>)}</div>}
          {!listing && message.answer.selectedSearchGroup?.name && <div className="space-y-2"><h4 className="font-bold">같은 모델·종류의 검색 가격 참고</h4><ProductGroupCard group={message.answer.selectedSearchGroup}/><p className="text-[10px] text-[var(--muted)]">선택한 게시글은 비교 집계에서 제외했습니다. 동일 상태·옵션의 가격 비교와 구분합니다.</p></div>}
          {message.answer.detailReport ? <DetailEvidence data={message.answer.detailReport} product={message.answer.product} api={message.answer.detailApi}/> : <details><summary>가격 비교 근거와 한계</summary><div className="used-agent-evidence">
            {listing ? <Distribution data={message.answer}/> : <><p>조회 후보 {message.answer.candidateCount}개 · 같은 제목 후보 {message.answer.sameTitleCount}개</p>
            {typeof message.answer.median === 'number' && <><p>선택 매물 {money(message.answer.currentPrice!)} · 참고 표본 {message.answer.referenceCount}개</p><dl><div><dt>참고 최저가</dt><dd>{money(message.answer.low!)}</dd></div><div><dt>참고 평균가</dt><dd>{money(message.answer.average!)}</dd></div><div><dt>참고 중앙값</dt><dd>{money(message.answer.median)}</dd></div><div><dt>참고 최고가</dt><dd>{money(message.answer.high!)}</dd></div></dl></>}
            {message.answer.searchAnalysis && <details><summary>앞서 검색한 전체 가격 분포</summary><Distribution data={message.answer.searchAnalysis}/></details>}</>}
            <p>{message.answer.comparisonBasis}</p>
            {message.answer.searchScopes.map((scope, n) => <p key={n}>{scope.provider === 'karrot' ? '당근' : '중고나라'}{scope.query ? ` · ${scope.query}` : ''}{scope.regionName ? ` · 검색 기준 ${scope.regionName}` : ''}{scope.fetchedAt ? ` · ${new Date(scope.fetchedAt).toLocaleString('ko-KR')}` : ''}</p>)}
            <ul>{message.answer.limitations.map((limit, n) => <li key={n}>{limit}</li>)}</ul>
            {!listing && <p>선택 매물 조회: {message.answer.fetchedAt ? new Date(message.answer.fetchedAt).toLocaleString('ko-KR') : '시각 미제공'}</p>}
          </div></details>}
          {!listing && message.answer.detailReport && message.answer.searchAnalysis && <details><summary>검색 결과 가격대·지역 분포 보기</summary><p className="mt-2 text-[var(--muted)]">앞서 검색한 전체 매물의 참고 정보입니다. 동일 모델·조건의 시세와 구분해 확인하세요.</p><Distribution data={message.answer.searchAnalysis}/></details>}
          {!message.answer.detailReport && message.answer.questions.length > 0 && <details><summary>판매자에게 확인할 질문</summary><ul>{message.answer.questions.map((question, n) => <li key={n}>{question}</li>)}</ul></details>}
        </article></div>)}
      {busy && <Loading showLabel label="잠시만 기다려주세요. 답변을 생성하는 중입니다."/>}
      {error && <p role="alert" className="text-xs text-rose-500">{error}</p>}
      <div ref={bottom}/>
    </div>
    <div className="border-t border-[var(--line)] p-4">
      <div className="mb-3 flex gap-2 overflow-x-auto">{(listing ? searchSuggestions : suggestions).map(question => <button key={question} type="button" disabled={busy || changed} onClick={() => void send(question)} className="shrink-0 rounded-full border border-[var(--line)] px-3 py-1.5 text-[10px] font-bold text-[var(--muted)] disabled:opacity-40">{question}</button>)}</div>
      <form onSubmit={submit} className="flex items-center gap-2 rounded-2xl bg-[var(--surface-2)] p-2">
        <input aria-label="중고 매물 구매 질문" maxLength={1000} value={text} disabled={busy || changed} onChange={event => setText(event.target.value)} placeholder="매물 가격이나 상태를 물어보세요" className="min-w-0 flex-1 bg-transparent px-2 text-xs outline-none"/>
        <button aria-label="메시지 보내기" disabled={busy || changed || !text.trim()} type="submit" className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[var(--primary)] text-white disabled:opacity-40"><Icon name="send" size={15}/></button>
      </form>
      <p className="mt-2 text-center text-[9px] text-[var(--subtle)]">조회한 매물 기준 안내입니다. 판매자 설명과 실제 상태를 함께 확인해 주세요.</p>
      {(messages.length > 0 || changed) && <div className="mt-2 text-right"><button className="text-[10px] text-[var(--muted)] underline disabled:opacity-40" type="button" disabled={busy} onClick={reset}>대화 초기화</button></div>}
    </div>
  </>
}

export default function UsedAgentChat({ provider, productId, title, context, evidenceKey, onLogin, listing = false, disabled = false }: {
  provider?: Provider; productId?: string; title: string; context: UsedAgentContext; evidenceKey: string; onLogin: () => void; listing?: boolean; disabled?: boolean
}) {
  const { user, ready } = useSession()
  const [summary, setSummary] = useState<Answer | null>(null), [summaryError, setSummaryError] = useState('')
  const [summaryBusy, setSummaryBusy] = useState(false), [revision, setRevision] = useState(0)
  useEffect(() => {
    setSummary(null); setSummaryError(''); setSummaryBusy(false)
    if (!ready || !user || disabled || !context.searches.length) return
    const abort = new AbortController(); setSummaryBusy(true)
    void request<{ data: Answer }>('/me/used/agent/search/summary', abort.signal, 'POST', { ...context, message: '검색 결과 요약', history: [] })
      .then(result => { if (!abort.signal.aborted) setSummary(result.data) })
      .catch(error => { if (!abort.signal.aborted) setSummaryError(error instanceof Error ? error.message : '가격 분포를 확인하지 못했습니다.') })
      .finally(() => { if (!abort.signal.aborted) setSummaryBusy(false) })
    return () => abort.abort()
  }, [listing, ready, user?.uid, evidenceKey, disabled, revision])
  return <section className="overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow)]" aria-label="중고 AI 구매 비서">
    <div className="ai-header flex items-center justify-between gap-3 p-5 text-white"><div className="flex items-center gap-3"><span className="grid h-10 w-10 shrink-0 place-items-center rounded-2xl border border-white bg-white"><img src={`${import.meta.env.BASE_URL}brand/ai-assistant-joopjoop.png`} alt="" aria-hidden="true" width={36} height={36} className="h-9 w-9 shrink-0 object-contain -scale-x-100"/></span><div><h2 className="text-sm font-black">줍줍각 AI 구매 비서</h2><p className="mt-1 text-[10px] text-white/65">조회한 중고 매물 기준 구매 상담</p></div></div><span className="shrink-0 rounded-full border border-white/15 px-2 py-1 text-[9px] font-bold">BETA</span></div>
    {listing && ready && user && <div className="border-b border-[var(--line)] p-4">{summaryBusy ? <Loading label="가격 분포를 확인하고 있습니다"/> : summary ? <Distribution data={summary}/> : <p className="text-xs text-[var(--muted)]">{disabled ? '검색이 완료되면 가격 분포를 확인합니다.' : '상품을 검색하면 가격 분포를 분석합니다.'}</p>}{summaryError && <p role="alert" className="mt-2 text-xs text-rose-500">{summaryError}<button className="ml-2 underline" type="button" onClick={() => setRevision(value => value + 1)}>다시 확인</button></p>}</div>}
    {!listing && ready && user && context.searches.length > 0 && <div className="border-b border-[var(--line)] p-4 text-xs">{summaryBusy ? <Loading label="검색 가격대와 지역 정보를 확인하고 있습니다"/> : summary ? <details><summary className="cursor-pointer font-bold">검색 결과 참고 · 가격대와 지역 분포</summary><p className="my-2 text-[var(--muted)]">다른 모델·상태가 섞일 수 있는 검색 전체 집계입니다. 동일 조건 가격 비교와 구분합니다.</p><Distribution data={summary}/></details> : null}{summaryError && <p role="alert" className="text-rose-500">{summaryError}<button type="button" className="ml-2 underline" onClick={() => setRevision(value => value + 1)}>다시 확인</button></p>}</div>}
    {ready && user ? listing && (!summary || disabled) ? <p className="p-5 text-xs text-[var(--muted)]">검색 결과 분석이 준비되면 질문할 수 있습니다.</p> : <Conversation key={`${user.uid}:${provider}:${productId}:${evidenceKey}:${listing ? summary?.evidenceVersion : ''}`} provider={provider} productId={productId} title={title} context={context} listing={listing} initialVersion={listing ? summary?.evidenceVersion : undefined} onReset={listing ? () => setRevision(value => value + 1) : undefined}/>
      : <div className="p-6 text-center"><span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-[var(--mint-soft)] text-[var(--mint)]"><Icon name="user" size={23}/></span><p className="mt-4 text-sm font-black">로그인하고 AI 구매 비서와 대화하세요</p><p className="mt-2 text-xs leading-6 text-[var(--muted)]">{listing ? '검색된 매물의 가격 분포와 이상 저가 제외 결과를 확인할 수 있어요.' : '선택한 중고 매물의 가격과 구매 전 확인 사항을 상담할 수 있어요.'}</p><button className="mt-5 w-full rounded-xl bg-[var(--primary)] py-3 text-xs font-bold text-white disabled:opacity-40" type="button" disabled={!ready} onClick={onLogin}>로그인하기</button></div>}
  </section>
}
