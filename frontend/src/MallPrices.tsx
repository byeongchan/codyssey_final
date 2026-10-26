import { Icon } from './Icons'
import { useState } from 'react'
import { mallLogo } from './mallLogos'

export type Offer = { rowId: string; mallCode?: string | null; mall: string; title: string | null; group: string | null; payment: 'card' | 'cash' | 'UNKNOWN'; price: number | null; shippingCost: number | null; shippingText: string | null; quotedTotal: number | null; reportedTotal: number | null; totalMismatch: boolean; availability: 'UNKNOWN'; couponText: string | null; interestFreeText: string | null; url: string | null }

const money = (value: number) => `${value.toLocaleString('ko-KR')}원`

function MallLogo({ name, index }: { name: string; index: number }) {
  const [failed, setFailed] = useState(false)
  const src = mallLogo(name)
  if (!src || failed) return <div aria-hidden="true" className={`grid h-11 w-11 shrink-0 place-items-center rounded-xl text-xs font-black text-white ${index === 0 ? 'bg-rose-500' : index === 1 ? 'bg-red-500' : 'bg-emerald-500'}`}>{(name || '판매처').slice(0, 2)}</div>
  return <div aria-hidden="true" className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl border border-[var(--line)] bg-white p-1">
    <img src={src} alt="" className="block h-full w-full min-w-0 object-contain object-center" onError={() => setFailed(true)}/>
  </div>
}

export default function MallPrices({ offers }: { offers: Offer[] }) {
  const malls = new Map<string, Offer>()
  for (const offer of offers) {
    if (offer.price === null || !Number.isFinite(offer.price) || offer.price <= 0) continue
    const key = offer.mall.normalize('NFKC').replace(/\s+/g, '').toLowerCase() || offer.mallCode || `unknown:${offer.rowId}`
    const previous = malls.get(key)
    if (!previous || offer.price < previous.price!
      || (offer.price === previous.price && (offer.quotedTotal ?? Infinity) < (previous.quotedTotal ?? Infinity))) malls.set(key, offer)
  }
  const rows = [...malls.entries()].sort(([, a], [, b]) => a.price! - b.price!)
  const lowest = rows[0]?.[1].price
  return <>
    <div className="mb-5 flex items-center justify-between gap-3"><div><h2 className="text-base font-black">쇼핑몰별 최저가</h2><p className="mt-1 text-xs text-[var(--muted)]">조회한 페이지에서 쇼핑몰마다 상품가가 가장 낮은 한 건만 표시합니다.</p></div><span className="shrink-0 text-xs text-[var(--subtle)]">{rows.length}개 쇼핑몰</span></div>
    {!rows.length && <p className="text-sm text-[var(--muted)]">표시할 판매처 가격이 없습니다.</p>}
    <div className="divide-y divide-[var(--line)]">{rows.map(([key, row], index) => <div key={key} className="flex flex-wrap items-center gap-3 py-4 first:pt-0 last:pb-0 sm:flex-nowrap sm:gap-4">
      <MallLogo key={row.mall} name={row.mall} index={index}/>
      <div className="min-w-0 flex-1"><div className="flex flex-wrap items-center gap-2"><p className="text-sm font-black">{row.mall || '판매처 미확인'}</p>{row.price === lowest && <span className="rounded bg-rose-500/10 px-1.5 py-0.5 text-[9px] font-extrabold text-rose-500">조회 최저가</span>}</div>
        <p className="mt-1 text-[11px] text-[var(--subtle)]">{row.payment === 'cash' ? '현금 전용' : row.payment === 'card' ? '카드 결제 가능' : '결제 조건 미확인'} · {row.shippingCost === 0 ? '무료배송' : row.shippingCost !== null ? `배송비 ${money(row.shippingCost)}` : '배송비 미확인'}</p>
        {row.group && <p className="mt-1 break-words text-[11px] text-[var(--muted)]">{row.group}</p>}
      </div>
      <div className="ml-auto text-right"><p className="whitespace-nowrap text-sm font-black">{money(row.price!)}</p>{row.quotedTotal !== null && <p className="mt-1 text-[10px] text-[var(--subtle)]">배송 포함 {money(row.quotedTotal)}</p>}{row.totalMismatch && <p className="text-[10px] text-amber-600">배송 합계 확인 필요</p>}</div>
      {row.url && <a href={row.url} target="_blank" rel="noopener noreferrer" aria-label={`${row.mall} 판매처 확인`} className={`grid h-9 w-9 shrink-0 place-items-center rounded-xl transition ${index === 0 ? 'bg-[var(--primary)] text-white hover:brightness-110' : 'border border-[var(--line)] hover:bg-[var(--surface-2)]'}`}><Icon name="external" size={15}/></a>}
    </div>)}</div>
    <p className="mt-4 text-[10px] leading-5 text-[var(--subtle)]">상품가 기준이며 카드·현금 조건이 다를 수 있습니다. 쿠폰은 차감하지 않으며 옵션·재고와 최종 결제 금액은 판매처에서 확인해 주세요.</p>
  </>
}
