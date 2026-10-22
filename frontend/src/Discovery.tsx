import { useEffect, useState } from 'react'
import { request, type Listing, type Single } from './api'
import { Icon } from './Icons'

type Tag = { label: string; count: number }
type ReviewData = { productId: string; status: 'AVAILABLE' | 'INSUFFICIENT_DATA' | 'EMPTY' | 'UNSUPPORTED' | 'STALE'; sampleCount: number; averageRating: number | null; positiveCount: number; neutralCount: number; negativeCount: number; pros: Tag[]; cons: Tag[]; reviews: { id: string; rating: number; text: string }[]; updatedAt: string; stale: boolean; method: string }
type Deal = { productId: string; title: string; categoryId: string; currentPrice: number; averagePrice: number; belowAveragePercent: number; observedDays: number; coverageStart: string; coverageEnd: string; observedAt: string }
const panel = 'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
const money = (value: number) => `${Math.round(value).toLocaleString('ko-KR')}원`
const dateTime = (value: string) => new Date(value).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
const messages: Record<ReviewData['status'], string> = {
  AVAILABLE: '합성 리뷰의 수동 태그를 집계한 예시입니다. 실제 리뷰나 AI 분석 결과가 아닙니다.',
  INSUFFICIENT_DATA: '표본이 3개 미만이므로 장단점 요약을 보류합니다.',
  EMPTY: '리뷰 지원 상품이지만 아직 예시 표본이 없습니다.',
  UNSUPPORTED: '이 상품은 현재 리뷰 예시를 지원하지 않습니다.',
  STALE: '갱신 필요 · 집계 시각이 24시간을 초과하여 장단점 요약을 보류합니다.',
}

export function Reviews({ productId }: { productId: string }) {
  const [data, setData] = useState<ReviewData | null>(null)
  const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setData(null); setError('')
    void request<Single<ReviewData>>(`/products/${encodeURIComponent(productId)}/reviews`, abort.signal)
      .then(result => { if (!abort.signal.aborted) setData(result.data) })
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '리뷰 조회 실패') })
    return () => abort.abort()
  }, [productId, retry])
  return <article className={panel}>
    <h2 className="flex items-center gap-2 font-black"><Icon name="star" size={18}/>리뷰 요약 · 합성 예시</h2>
    {error ? <p role="alert" className="mt-3 text-sm text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">다시 시도</button></p> : !data ? <p role="status" className="mt-3 text-sm">리뷰 예시 불러오는 중…</p> : <>
      <p className="mt-3 text-sm leading-6 text-[var(--muted)]">{messages[data.status]}</p>
      {data.sampleCount > 0 && <>
        <div className="mt-4 rounded-2xl bg-[var(--surface-2)] p-4"><p className="text-sm font-bold">{data.stale ? '이전 집계 ' : ''}평균 {data.averageRating?.toFixed(2)} / 5 · 표본 {data.sampleCount}개</p><p className="mt-2 text-xs leading-6 text-[var(--muted)]">긍정(4~5점) {data.positiveCount}개 · 중립(3점) {data.neutralCount}개 · 부정(1~2점) {data.negativeCount}개</p></div>
        {data.status === 'AVAILABLE' && <div className="mt-4 grid gap-4 sm:grid-cols-2">{[{ title: '장점 예시', tags: data.pros }, { title: '아쉬운 점 예시', tags: data.cons }].map(group => <div key={group.title}><h3 className="text-sm font-bold">{group.title}</h3><ul className="mt-2 space-y-2 text-xs text-[var(--muted)]">{group.tags.length ? group.tags.map(tag => <li key={tag.label}>{tag.label} · {tag.count}/{data.sampleCount}개 표본</li>) : <li>집계된 태그 없음</li>}</ul></div>)}</div>}
        <details className="mt-5 text-xs"><summary className="cursor-pointer font-bold">합성 리뷰 원문 {data.sampleCount}개 보기</summary><ul className="mt-3 space-y-3">{data.reviews.map(review => <li key={review.id} className="rounded-xl border border-[var(--line)] p-3 leading-6"><p className="font-bold">예시 별점 {review.rating}/5</p><p>{review.text}</p></li>)}</ul></details>
      </>}
      <p className="mt-4 text-[10px] text-[var(--subtle)]">예시 집계 {dateTime(data.updatedAt)} · Gemini·실제 리뷰 수집 없음</p>
    </>}
  </article>
}

export function Deals({ onProduct }: { onProduct: (id: string) => void }) {
  const [items, setItems] = useState<Deal[]>([])
  const [cursor, setCursor] = useState('0')
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [referenceTime, setReferenceTime] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setLoading(true); setError('')
    void request<Listing<Deal>>(`/deals?${new URLSearchParams({ cursor, limit: '20' })}`, abort.signal)
      .then(page => { if (!abort.signal.aborted) { setItems(previous => cursor === '0' ? page.items : [...previous.filter(item => !page.items.some(row => row.productId === item.productId)), ...page.items]); setNextCursor(page.nextCursor); setReferenceTime(page.meta.referenceTime) } })
      .catch(error => { if (!abort.signal.aborted) setError(error instanceof Error ? error.message : '특가 조회 실패') })
      .finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [cursor, retry])
  return <section className={panel}>
    <p className="flex items-center gap-2 text-sm font-extrabold text-[var(--mint)]"><Icon name="trend" size={17}/>PRICE PICK · DEMO</p>
    <h1 className="mt-2 text-2xl font-black">평균보다 낮은 가격</h1>
    <p className="mt-3 text-sm leading-6 text-[var(--muted)]">최근 30일 범위에서 7일 이상 관측되고, 유효한 확정 비교가가 평균보다 2% 이상 낮은 상품입니다. 실제 할인 행사나 미래 최저가를 뜻하지 않습니다.</p>
    {referenceTime && <p className="mt-2 text-xs text-[var(--muted)]">더미 기준 {dateTime(referenceTime)} · 평균 대비 차이 큰 순</p>}
    <div className="mt-5 grid gap-4 sm:grid-cols-2">{items.map(item => <button key={item.productId} onClick={() => onProduct(item.productId)} className="rounded-2xl border border-[var(--line)] bg-[var(--surface-2)] p-5 text-left transition hover:border-[var(--primary)]">
      <span className="inline-block rounded-lg bg-[var(--mint-soft)] px-2 py-1 text-xs font-black text-[var(--mint)]">관측 평균 대비 {item.belowAveragePercent.toFixed(2)}% 낮음 · 더미</span>
      <h2 className="mt-3 text-sm font-bold">{item.title}</h2><p className="mt-3 text-xl font-black">{money(item.currentPrice)}</p><p className="mt-2 text-xs text-[var(--muted)]">관측 {item.observedDays}일 평균 {money(item.averagePrice)}</p><p className="mt-2 text-xs text-[var(--muted)]">{item.coverageStart} ~ {item.coverageEnd}</p><p className="mt-2 text-[10px] text-[var(--subtle)]">현재가 관측 {dateTime(item.observedAt)}</p>
    </button>)}</div>
    {loading ? <p role="status" className="mt-4 text-sm">선정 상품 불러오는 중…</p> : !error && !items.length ? <p className="mt-4 text-sm text-[var(--muted)]">현재 조건을 충족하는 상품이 없습니다. 품절·가격 만료·이력 부족 상품은 제외합니다.</p> : null}
    {error && <p role="alert" className="mt-4 text-sm text-rose-500">{error}<button onClick={() => setRetry(value => value + 1)} className="ml-2 underline">다시 시도</button></p>}
    {!loading && !error && nextCursor && <button onClick={() => setCursor(nextCursor)} className="mt-4 text-sm font-bold text-[var(--primary)]">더 보기</button>}
  </section>
}
