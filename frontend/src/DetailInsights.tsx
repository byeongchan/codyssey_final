import { useEffect, useId, useState } from 'react'

import { collectedRequest, CollectionPending } from './collectedRequest'
import { Icon } from './Icons'


type Point = { date: string; lowestPrice: number | null }
type History = { productId: string; currency: 'KRW'; weekly: Point[]; monthly: Point[] }
type HistoryResponse = { productId: string; priceHistory: History }
const panel = 'min-w-0 rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
const money = (value: number) => `${Math.round(value).toLocaleString('ko-KR')}원`

function RefreshIcon() {
  return <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5"/><path d="M4 17v-5h5"/><path d="M6.1 7a7 7 0 0 1 11.6-1L20 9M4 15l2.3 3A7 7 0 0 0 17.9 17"/></svg>
}

function PriceChart({ history, monthly }: { history: History; monthly: boolean }) {
  const fillId = useId()
  const points = monthly ? history.monthly : history.weekly
  const values = points.flatMap(point => point.lowestPrice === null ? [] : [point.lowestPrice])
  if (!values.length) return <div className="grid min-h-52 place-items-center rounded-2xl bg-[var(--surface-2)] p-6 text-center text-sm text-[var(--muted)]">이 기간에 제공된 가격 이력이 없습니다.</div>
  const low = Math.min(...values), high = Math.max(...values)
  const padding = Math.max((high - low) * .12, high * .02, 1)
  const bottom = Math.max(0, low - padding), top = high + padding
  const time = (date: string) => Date.parse(monthly ? `${date}-01T00:00:00Z` : `${date}T00:00:00Z`)
  const first = time(points[0].date), last = time(points[points.length - 1].date)
  const x = (point: Point) => first === last ? 294 : 76 + (time(point.date) - first) / (last - first) * 436
  const y = (value: number) => 160 - (value - bottom) / (top - bottom) * 140
  const segments: Point[][] = []
  let segment: Point[] = []
  for (const point of points) {
    if (point.lowestPrice === null) { if (segment.length) segments.push(segment); segment = [] }
    else segment.push(point)
  }
  if (segment.length) segments.push(segment)
  return <>
    <div className="mb-5 grid grid-cols-2 gap-3">
      <div className="rounded-2xl bg-[var(--mint-soft)] p-3"><p className="text-[10px] font-bold text-[var(--muted)]">선택 기간 최저</p><p className="mt-1 text-sm font-black text-[var(--mint)]">{money(low)}</p></div>
      <div className="rounded-2xl bg-[var(--surface-2)] p-3"><p className="text-[10px] font-bold text-[var(--muted)]">선택 기간 최고</p><p className="mt-1 text-sm font-black">{money(high)}</p></div>
    </div>
    <svg viewBox="0 0 530 192" role="img" aria-label={`${monthly ? '월별' : '주별'} 최저가 흐름`} className="w-full">
      <desc>다나와가 제공한 기간별 최저가입니다. 비어 있는 가격은 연결하지 않으며 현재 판매 가격과 다를 수 있습니다.</desc>
      <defs><linearGradient id={fillId} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="var(--mint)" stopOpacity=".25"/><stop offset="100%" stopColor="var(--mint)" stopOpacity="0"/></linearGradient></defs>
      {[0, .5, 1].map(ratio => <g key={ratio}><line x1="76" x2="512" y1={20 + ratio * 140} y2={20 + ratio * 140} stroke="var(--line)" strokeDasharray="4 5"/><text x="68" y={24 + ratio * 140} textAnchor="end" fill="var(--subtle)" fontSize="10">{money(top - ratio * (top - bottom))}</text></g>)}
      {segments.map((part, index) => {
        const coords = part.map(point => `${x(point)},${y(point.lowestPrice!)}`).join(' ')
        return <g key={index}>{part.length > 1 && <><polygon points={`${x(part[0])},160 ${coords} ${x(part[part.length - 1])},160`} fill={`url(#${fillId})`}/><polyline points={coords} fill="none" stroke="var(--mint)" strokeWidth="3" strokeLinejoin="round" strokeLinecap="round"/></>}{part.map(point => <circle key={point.date} cx={x(point)} cy={y(point.lowestPrice!)} r="3.5" fill="var(--surface)" stroke="var(--mint)" strokeWidth="2"><title>{point.date} · {money(point.lowestPrice!)}</title></circle>)}</g>
      })}
      <text x="76" y="184" fill="var(--subtle)" fontSize="11">{points[0].date}</text><text x="512" y="184" textAnchor="end" fill="var(--subtle)" fontSize="11">{points[points.length - 1].date}</text>
    </svg>
    <details className="mt-3 text-xs text-[var(--muted)]"><summary className="cursor-pointer">기간별 가격 보기</summary><ul className="mt-3 grid gap-2">{points.map(point => <li key={point.date} className="flex justify-between gap-3"><span>{point.date}</span><span>{point.lowestPrice === null ? '자료 없음' : money(point.lowestPrice)}</span></li>)}</ul></details>
  </>
}

export default function DetailInsights({ productId }: { productId: string }) {

  const [history, setHistory] = useState<History | null>(null)
  const [monthly, setMonthly] = useState(false)
  const [priceBusy, setPriceBusy] = useState(false)
  const [priceError, setPriceError] = useState('')
  const [priceNotice, setPriceNotice] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setPriceBusy(true); setPriceError(''); setPriceNotice('')
    void collectedRequest<HistoryResponse>('/danawa/price_history', abort.signal, { productId }).then(response => {
      if (abort.signal.aborted) return
      const value = response.data.priceHistory
      if (!value || value.productId !== productId || value.currency !== 'KRW' || !Array.isArray(value.weekly) || !Array.isArray(value.monthly)) throw new Error('가격 이력 응답을 확인할 수 없습니다.')
      setHistory(value)
      if (response.meta.collectionState === 'COLLECTING') setPriceNotice('가격 이력을 갱신 중입니다.')
    }).catch(error => {
      if (!abort.signal.aborted) setPriceError(error instanceof CollectionPending ? '가격 이력을 수집 중입니다. 잠시 후 다시 조회해 주세요.' : error instanceof Error ? error.message : '가격 흐름을 불러오지 못했습니다.')
    }).finally(() => { if (!abort.signal.aborted) setPriceBusy(false) })
    return () => abort.abort()
  }, [productId, revision])
  return <div className="grid items-start gap-5 md:grid-cols-2">
    <article className={panel}>
      <div className="mb-5 flex items-start justify-between gap-3"><div><h2 className="flex items-center gap-2 text-base font-black"><span className="text-[var(--mint)]"><Icon name="trend" size={19}/></span>가격 흐름</h2><p className="mt-1 text-xs text-[var(--muted)]">과거 기간별 최저가를 비교해 보세요</p></div><button type="button" disabled={priceBusy} onClick={() => setRevision(value => value + 1)} aria-label="가격 흐름 새로고침" title="가격 흐름 새로고침" className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[var(--primary)] hover:bg-[var(--surface-2)] disabled:opacity-40"><RefreshIcon/></button></div>
      <div className="mb-5 flex rounded-xl bg-[var(--surface-2)] p-1">{[false, true].map(value => <button key={String(value)} type="button" aria-pressed={monthly === value} onClick={() => setMonthly(value)} className={`flex-1 rounded-lg px-2 py-2 text-xs font-bold transition ${monthly === value ? 'bg-[var(--surface)] text-[var(--primary)] shadow-sm' : 'text-[var(--muted)]'}`}>{value ? '24개월 · 월별' : '3개월 · 주별'}</button>)}</div>
      {priceBusy && <p role="status" className="py-4 text-sm text-[var(--muted)]">가격 흐름을 불러오는 중…</p>}
      {priceError && <p role="alert" className="mb-4 rounded-xl bg-[var(--surface-2)] p-3 text-xs leading-6 text-[var(--muted)]">{priceError}</p>}
      {history && <PriceChart history={history} monthly={monthly}/>}
      {priceNotice && <p role="status" className="mt-3 text-xs text-[var(--muted)]">{priceNotice}</p>}
      <p className="mt-4 text-[10px] leading-5 text-[var(--subtle)]">다나와 제공 주간·월간 최저가로 현재 판매 가격과 다를 수 있습니다. 등록 기간에 따라 표시되는 이력이 짧을 수 있습니다.</p>
    </article>
    <article className={panel}>
      <h2 className="flex items-center gap-2 text-base font-black"><span className="text-[var(--mint)]"><Icon name="sparkles" size={19}/></span>리뷰 AI 요약</h2>
      <div className="mt-5 rounded-2xl bg-[var(--surface-2)] p-5">
        <p className="text-sm leading-7 text-[var(--muted)]">현재 Reef 다나와 API는 리뷰 본문을 제공하지 않습니다. 직접 확인해주세요.</p>
        <a href={`https://prod.danawa.com/info/?pcode=${productId}`} target="_blank" rel="noopener noreferrer" className="mt-4 inline-flex items-center gap-2 text-xs font-bold text-[var(--primary)]">다나와에서 리뷰 확인<Icon name="external" size={14}/></a>
      </div>
    </article>
  </div>
}
