import Loading from './Loading'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useSession } from './session'
import { collectedRequest } from './collectedRequest'
import { Icon } from './Icons'

type Article = { originalUrl?: string | null; naverUrl?: string | null; relevanceScore?: number; assessmentStage?: string; priceImpact?: string; reportedFacts?: string[]; impactPath?: string[]; uncertainties?: string[]; eventId?: string; dateBasis?: string; summary?: string; title: string; publisher: string; publishedAt: string; url: string; relevance: string }
type PeriodAnalysis = { granularity: string; fallback: boolean; sampleCount: number; coverageStart: string | null; coverageEnd: string | null; lowest: number | null; median: number | null; latestPeriodLowest: number | null; periodChangePercent: number | null; canCompareCurrent: boolean; gapFromLowPercent: number | null; priceBasis: string; limitation: string }
type Answer = { aiUnavailable?: boolean; intent?: string; showDecision?: boolean; decision?: "BUY" | "WAIT" | "INSUFFICIENT_DATA"; headline?: string; reasoning?: string[]; wait_condition?: string | null; data_limitations?: string[]; answer?: string; analysis?: Record<string, unknown>; verdict: string; conclusion: string; priceReasons: string[]; newsReasons: string[]; risks: string[]; generatedAt: string; source: string; currentPrice?: number | null; dailyMetricBasis?: string; priceSignal?: string; evidenceVersion?: string; dataAsOf: Record<string, string | null>; metrics?: Record<string, unknown>; periodAnalysis?: PeriodAnalysis; news?: { identity?: { category: string; description: string; basis: string; confidence: string }; evaluation?: { evaluated: number; evidence: number; reference: number }; searchTopics?: { query: string; state: string }[]; category?: string; searchBasis?: string; windowDays?: number; state: string; articles: Article[]; checkedAt?: string } }
type Message = { role: 'user' | 'assistant'; text?: string; answer?: Answer }
const format = (value: unknown, suffix = '') => typeof value === 'number' && Number.isFinite(value) ? `${value.toLocaleString('ko-KR', { maximumFractionDigits: 2 })}${suffix}` : ''
function Facts({ rows }: { rows: string[][] }) {
  const confirmed = rows.filter(([, value]) => value !== '')
  if (!confirmed.length) return null
  return <dl className="space-y-2 rounded-xl bg-[var(--surface)] p-3">{confirmed.map(([label, value]) => <div key={label} className="flex justify-between gap-3"><dt className="text-[var(--muted)]">{label}</dt><dd className="text-right font-semibold">{value}</dd></div>)}</dl>
}
function NewsArticle({ article }: { article: Article }) {
  const impact = ({ UPWARD_PRESSURE: '상승 압력 가능성', DOWNWARD_PRESSURE: '하락 압력 가능성', MIXED: '상승·하락 요인 혼재', UNCERTAIN: '영향 방향 불확실' } as Record<string,string>)[article.priceImpact ?? '']
  return <article className="space-y-2 rounded-xl border border-[var(--line)] p-3">
    <a href={article.url} target="_blank" rel="noopener noreferrer" className="font-bold hover:text-[var(--primary)]">{article.title}</a>
    <p className="text-[10px] text-[var(--muted)]">{article.publisher} · {article.dateBasis==='NAVER_PROVIDED_AT'?'네이버 제공일 ':''}{new Date(article.publishedAt).toLocaleDateString('ko-KR')}</p>
    {impact && <p className="font-bold text-[var(--mint)]">{impact} · {article.assessmentStage==='EVIDENCE'?'보완 근거':'참고 기사'}</p>}
    {typeof article.relevanceScore==='number' && <p className="text-[10px] text-[var(--muted)]">관련성 {format(article.relevanceScore)}점 / 100 · 초기 규칙 평가</p>}
    {!!article.reportedFacts?.length ? <div><p className="font-bold">기사 요약의 근거 문장</p>{article.reportedFacts.map((text,i)=><p key={i} className="mt-1 text-xs leading-5">{text}</p>)}</div> : article.summary && <p className="text-xs leading-5">{article.summary}</p>}
    {!!article.impactPath?.length && <div><p className="font-bold">시스템이 추론한 가격 영향 경로</p>{article.impactPath.map((text,i)=><p key={i} className="mt-1 text-xs leading-5">{text}</p>)}</div>}
    <p className="text-[10px] text-[var(--subtle)]">{article.relevance}</p>
    {!!article.uncertainties?.length && <details className="text-[10px] text-[var(--muted)]"><summary className="cursor-pointer">불확실성과 분석 범위</summary><ul className="mt-2 space-y-1">{article.uncertainties.map((text,i)=><li key={i}>{text}</li>)}</ul></details>}
    {article.naverUrl && article.naverUrl!==article.url && <a href={article.naverUrl} target="_blank" rel="noopener noreferrer" className="text-[10px] underline">네이버 뉴스에서 보기</a>}
  </article>
}
function Reply({ answer }: { answer: Answer }) {
  if (!answer.evidenceVersion) return <p className="whitespace-pre-wrap">{answer.conclusion}</p>
  const stats: Record<string, unknown> = { ...answer.metrics, ...answer.analysis }
  const showPurchaseEvidence = answer.showDecision === true
  const showNews = showPurchaseEvidence || answer.intent === 'NEWS'
  return <div className="space-y-3">
    {answer.showDecision && <p className="font-black text-[var(--mint)]">판단: {({BUY:"구매 추천",WAIT:"조건부 대기",INSUFFICIENT_DATA:"판단 유보"} as Record<string,string>)[answer.decision ?? ""] ?? answer.verdict}</p>}<p className="whitespace-pre-wrap">{answer.answer ?? answer.conclusion}</p>

    {showPurchaseEvidence && <details className="rounded-xl border border-[var(--line)] p-3"><summary className="cursor-pointer font-bold">가격 분석 근거 보기</summary><div className="mt-3 space-y-3">
    {answer.wait_condition && <p className="rounded-lg border border-[var(--mint)] p-3">{answer.wait_condition}</p>}
    {answer.analysis?.assessmentBasis==='PERIOD_PRICE_LEVEL' && <p className="text-[10px] font-bold text-[var(--mint)]">기간별 가격 흐름 기준 참고 판단</p>}
    {!!answer.priceReasons.length && <><h4 className="font-black">주요 가격 근거</h4><ul className="space-y-1">{answer.priceReasons.map((text, i) => <li key={i}>• {text}</li>)}</ul></>}
    {answer.periodAnalysis && !answer.periodAnalysis.fallback && <p className="text-[10px] font-bold text-[var(--mint)]">분석 기준: {answer.periodAnalysis.granularity==='daily_api'?'API 제공 일별 가격':'저장된 일별 가격 관측'}</p>}
    {answer.periodAnalysis?.fallback && answer.periodAnalysis.sampleCount>0 && <div className="space-y-2 rounded-xl border border-[var(--mint)]/20 bg-[var(--mint-soft)] p-3"><p className="font-black text-[var(--mint)]">{({daily:'일별',weekly:'주간',monthly:'월간'} as Record<string,string>)[answer.periodAnalysis.granularity] ?? '기간별'} 자료로 보완 분석 · {answer.periodAnalysis.sampleCount}개 표본</p>{answer.periodAnalysis.coverageStart && answer.periodAnalysis.coverageEnd && <p className="text-[10px] text-[var(--muted)]">{answer.periodAnalysis.coverageStart} ~ {answer.periodAnalysis.coverageEnd}</p>}<Facts rows={[
      ['반환 표본 범위 최저',format(answer.periodAnalysis.lowest,'원')],['반환 표본 범위 중앙값',format(answer.periodAnalysis.median,'원')],['최근 기간 최저',format(answer.periodAnalysis.latestPeriodLowest,'원')],['첫 표본 대비 마지막 변화',format(answer.periodAnalysis.periodChangePercent,'%')],
      ['현재가와 비교',answer.periodAnalysis.canCompareCurrent?format(answer.periodAnalysis.gapFromLowPercent,'% 차이'):''],
    ]}/></div>}
    <Facts rows={[
      [answer.dailyMetricBasis==='CARD_WITHOUT_SHIPPING'?'카드·배송 제외 비교가':answer.dailyMetricBasis==='CARD_WITH_SHIPPING'?'카드·배송 포함 비교가':'동일 조건 현재가', format(answer.currentPrice, '원')],
      ['30일 최저가', format(stats.lowest30, '원')], ['30일 중앙값', format(stats.median30, '원')], ['90일 중앙값', format(stats.median90, '원')], ['관측 추세(30일 환산)', format(stats.trend30EquivalentPercent, '%')], ['완료된 할인 구간', format(stats.completedDiscountEpisodes)], ['최저가 대비 차이', format(stats.gapFromLowPercent, '%')],
      ['30일 변동성', format(stats.volatility30Percent, '%p')], ['할인 관측 비율', format(stats.discountFrequencyPercent, '%')], ['관측 표본', [['30일',stats.days30],['90일',stats.days90]].filter(([,value])=>typeof value==='number' && value>0).map(([label,value])=>`${label} 중 ${format(value)}일`).join(' · ')],
      ['계절성', stats.seasonalityAvailable ? '과거 같은 월의 표본 있음' : ''],
    ]}/>
    {typeof stats.discountFrequencyPercent === "number" && <p className="text-[10px] text-[var(--subtle)]">할인 관측: 90일 중앙값보다 5% 이상 낮은 일별 표시가. 실제 행사 여부는 미확인입니다.</p>}
    </div></details>}
    {showNews && <>
    {answer.news && answer.news.state!=="NOT_REQUESTED" && <><h4 className="font-black">관련 뉴스 근거</h4>{answer.news.category && <p className="text-xs font-bold text-[var(--mint)]">검색 카테고리: {answer.news.category}{answer.news.identity?.basis==='AI_INFERRED'?' (AI 자동 분류·추정)':''}</p>}<ul className="space-y-1">{answer.newsReasons.map((text,i)=><li key={i}>{text}</li>)}</ul>{answer.news.windowDays && <p className="text-[10px] text-[var(--muted)]">최근 {answer.news.windowDays}일 · 네이버 제공일·설정된 검색 범위 기준</p>}</>}
    {answer.news?.articles.map(article => <NewsArticle key={article.eventId ?? article.url} article={article}/>)}
    {answer.news?.evaluation && <p className="text-[10px] text-[var(--muted)]">기사 {answer.news.evaluation.evaluated}건 검토 · 보완 근거 {answer.news.evaluation.evidence}건 · 참고 {answer.news.evaluation.reference}건</p>}
    {!!answer.news?.searchTopics?.length && <details className="text-[10px] text-[var(--muted)]"><summary className="cursor-pointer">뉴스 검색 주제 보기</summary><ul className="mt-2 space-y-1">{answer.news.searchTopics.map(topic=><li key={topic.query}>{topic.query} · {topic.state==='READY'?'검색 완료':'검색 확인 필요'}</li>)}</ul></details>}
    {answer.news?.checkedAt && <p className="text-[10px] text-[var(--subtle)]">뉴스 확인: {new Date(answer.news.checkedAt).toLocaleString('ko-KR')}</p>}
    </>}
    {showPurchaseEvidence && !!answer.risks.length && <><h4 className="font-black">주의할 점</h4><ul className="space-y-1">{answer.risks.map((text, i) => <li key={i}>• {text}</li>)}</ul></>}
    {showPurchaseEvidence && <div className="border-t border-[var(--line)] pt-2 text-[10px] text-[var(--subtle)]"><p>분석 생성: {new Date(answer.generatedAt).toLocaleString('ko-KR')}</p>{Object.entries(answer.dataAsOf).filter(([,value])=>!!value).map(([key, value]) => <p key={key}>{({ product: '상품', offers: '판매처 가격', periodHistory: '주·월간 이력', dailyLatest: '최근 일별 관측' } as Record<string,string>)[key] ?? key} 기준: {value ? value.length === 10 ? value : new Date(value).toLocaleString('ko-KR') : ''}</p>)}</div>}
  </div>
}
function ChatSession({ productId, title }: { productId: string; title: string }) {
  const [urgency, setUrgency] = useState("NORMAL")
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const active = useRef<AbortController | null>(null)
  const lock = useRef(false)
  const bottom = useRef<HTMLDivElement | null>(null)
  useEffect(() => () => active.current?.abort(), [])
  useEffect(() => {
    bottom.current?.scrollIntoView({ block: 'nearest' })
  }, [messages, busy])
  async function send(question = input) {
    const text = question.trim(); if (!text || text.length > 1000 || lock.current) return
    const history = messages.slice(-6).map(message => ({ role: message.role, content: (message.text ?? message.answer?.answer ?? message.answer?.conclusion ?? '').slice(0,1000) })).filter(message=>message.content.trim())
    lock.current = true; setBusy(true); setError(''); setInput(''); setMessages(previous => [...previous, { role: 'user' as const, text }].slice(-12))
    const abort = new AbortController(); active.current?.abort(); active.current = abort
    try {
      const response = await collectedRequest<Answer>(`/me/danawa/agent/${productId}/messages`, abort.signal, { message: text, urgency, history }, undefined, '/me/danawa/agent/jobs/')
      if (response.data.aiUnavailable) throw new Error('답변 생성을 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.')
      if (!abort.signal.aborted) setMessages(previous => [...previous, { role: 'assistant' as const, answer: response.data }].slice(-12))
    } catch (error) { if (!abort.signal.aborted) { setError(error instanceof Error ? error.message : '답변을 불러오지 못했습니다.'); setInput(text) } }
    finally { if (active.current === abort) { lock.current = false; if (!abort.signal.aborted) setBusy(false) } }
  }
  function submit(event: FormEvent) { event.preventDefault(); void send() }
  return <>
    <div role="log" aria-label="선택 상품 구매 상담" aria-live="polite" className="chat-scroll h-[400px] sm:h-[480px] space-y-4 overflow-y-auto overscroll-contain p-5">
      <div className="rounded-2xl bg-[var(--surface-2)] p-4 text-xs leading-6"><p className="font-black">{title}</p><p className="mt-2 text-[var(--muted)]">이 상품의 가격 흐름, 구매 시점, 사양과 판매처를 질문해 주세요. 실제 근거가 부족하면 판단을 보류합니다.</p></div>
      {messages.map((message, index) => <div key={index} className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}><div className={`max-w-[95%] rounded-2xl px-4 py-3 text-xs leading-6 ${message.role === 'user' ? 'rounded-br-md bg-[var(--primary)] text-white' : 'w-full rounded-bl-md bg-[var(--surface-2)]'}`}>{message.answer ? <Reply answer={message.answer}/> : message.text}</div></div>)}
      {busy && <Loading showLabel label="잠시만 기다려주세요. 답변을 생성하는 중입니다."/>}
      {error && <p role="alert" className="text-xs text-rose-500">{error}</p>}<div ref={bottom}/>
    </div>
    <div className="border-t border-[var(--line)] p-4"><div className="mb-3 flex gap-2 overflow-x-auto">{['지금 구매해도 될까요?', '가격 흐름은 어떤가요?', '관련 뉴스가 있나요?', '사양을 알려줘'].map(text => <button key={text} type="button" disabled={busy} onClick={() => void send(text)} className="shrink-0 rounded-full border border-[var(--line)] px-3 py-1.5 text-[10px] font-bold text-[var(--muted)] disabled:opacity-40">{text}</button>)}</div><label className="mb-3 flex items-center justify-between gap-3 text-xs">구매 시급성<select aria-label="구매 시급성" value={urgency} disabled={busy} onChange={event=>setUrgency(event.target.value)} className="rounded-lg bg-[var(--surface-2)] p-2"><option value="URGENT">급해요</option><option value="NORMAL">보통</option><option value="FLEXIBLE">여유 있어요</option></select></label><form onSubmit={submit} className="flex items-center gap-2 rounded-2xl bg-[var(--surface-2)] p-2"><input aria-label="선택 상품 구매 질문" value={input} maxLength={1000} disabled={busy} onChange={event => setInput(event.target.value)} placeholder="가격이나 구매 시점을 물어보세요" className="min-w-0 flex-1 bg-transparent px-2 text-xs outline-none"/><button disabled={busy || !input.trim()} aria-label="메시지 보내기" className="grid h-9 w-9 shrink-0 place-items-center rounded-xl bg-[var(--primary)] text-white disabled:opacity-40"><Icon name="send" size={15}/></button></form><p className="mt-2 text-center text-[9px] text-[var(--subtle)]">가격 기준 시각과 판매 조건을 함께 확인해 주세요.</p></div>
  </>
}
export default function PriceAgentChat({ productId, title, onLogin }: { productId: string; title: string; onLogin: () => void }) {
  const { user, ready } = useSession()
  return <article className="overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow)]"><div className="ai-header flex items-center justify-between gap-3 p-5 text-white"><div className="flex items-center gap-3"><span className="grid h-10 w-10 place-items-center rounded-2xl border border-white bg-white"><img src={`${import.meta.env.BASE_URL}brand/ai-assistant-joopjoop.png`} alt="" aria-hidden="true" width={36} height={36} className="h-9 w-9 shrink-0 object-contain -scale-x-100"/></span><div><h2 className="text-sm font-black">줍줍각 AI 구매 비서</h2><p className="mt-1 text-[10px] text-white/65">실제 가격 근거로 살펴보는 구매 타이밍</p></div></div><span className="rounded-full border border-white/15 px-2 py-1 text-[9px] font-bold">BETA</span></div>{ready && user ? <ChatSession key={`${user.uid}:${productId}`} productId={productId} title={title}/> : <div className="p-6 text-center"><span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-[var(--mint-soft)] text-[var(--mint)]"><Icon name="user" size={23}/></span><p className="mt-4 text-sm font-black">로그인하고 AI 구매 비서와 대화하세요</p><p className="mt-2 text-xs leading-6 text-[var(--muted)]">선택한 상품의 가격과 구매 시점을 상담할 수 있어요.</p><button type="button" disabled={!ready} onClick={onLogin} className="mt-5 w-full rounded-xl bg-[var(--primary)] py-3 text-xs font-bold text-white disabled:opacity-40">로그인하기</button></div>}</article>
}
