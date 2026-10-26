import { Icon, PrizmMark } from './Icons'
import { useId, useRef, useState } from 'react'
import { legalDocuments } from './legalDocuments'

type Props = {
  disabled: boolean
  signedIn: boolean
  onHome: () => void
  onWishlist: () => void
  onAccount: () => void
}

const questions = [
  ['표시 가격과 결제 가격이 다른 이유는 무엇인가요?', '결제 수단, 상품 옵션, 배송 지역과 쿠폰 조건에 따라 가격이 달라질 수 있습니다. 구매 전 판매처에서 최종 결제 금액과 재고를 확인해 주세요.'],
  ['가격은 언제 갱신되나요?', '저장된 가격을 보여드리며 마지막 확인 시각을 함께 표시합니다. 상세 페이지의 최신 가격 갱신 버튼으로 다시 확인할 수 있습니다.'],
  ['AI 구매 비서는 어떻게 이용하나요?', '로그인한 뒤 상품 상세에서 가격 흐름, 관련 뉴스, 사양과 구매 시점을 질문할 수 있습니다. 답변은 제공된 자료를 바탕으로 하며 미래 가격이나 할인을 보장하지 않습니다.'],
]

export default function ServiceFooter({ disabled, signedIn, onHome, onWishlist, onAccount }: Props) {
  const [openQuestion, setOpenQuestion] = useState<number | null>(null)
  const [document, setDocument] = useState<'terms' | 'privacy'>('terms')
  const dialog = useRef<HTMLDialogElement>(null)
  const prefix = useId()
  function openDocument(value: 'terms' | 'privacy') {
    setDocument(value)
    dialog.current?.showModal()
    if (dialog.current) dialog.current.scrollTop = 0
  }
  const link = 'w-fit text-left text-xs leading-6 text-[var(--muted)] transition hover:text-[var(--primary)] focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-[var(--primary)] disabled:opacity-40'
  return <footer className="service-footer mt-16 border-t border-[var(--line)] bg-[var(--surface)]">
    <div className="mx-auto max-w-[1440px] px-5 lg:px-8">
      <div className="grid gap-9 py-10 sm:grid-cols-2 lg:grid-cols-[1.4fr_.7fr_1.2fr] lg:gap-16 lg:py-12">
        <div>
          <button type="button" onClick={onHome} disabled={disabled} aria-label="PRIZM 홈으로 이동" className="flex items-center gap-2 disabled:opacity-40"><span className="logo-symbol"><PrizmMark size={30}/></span><span className="text-xl font-black tracking-[-0.05em]">PRIZM</span></button>
          <p className="mt-4 text-sm font-bold">더 나은 구매의 시작</p>
          <p className="mt-2 max-w-xs text-xs leading-6 text-[var(--muted)]">흩어진 쇼핑몰 가격을 한눈에 비교하고,<br/>가격의 흐름을 살펴 합리적으로 선택하세요.</p>
          <div className="mt-5 flex flex-wrap gap-2">{['쇼핑몰별 가격 비교', '가격 흐름', 'AI 구매 상담'].map(text => <span key={text} className="rounded-full bg-[var(--surface-2)] px-2.5 py-1 text-[10px] font-medium text-[var(--muted)]">{text}</span>)}</div>
        </div>
        <nav aria-label="푸터 서비스 메뉴">
          <h2 className="text-xs font-black">서비스</h2>
          <div className="mt-4 flex flex-col gap-2">
            <button type="button" disabled={disabled} onClick={onHome} className={link}>상품 둘러보기</button>
            <button type="button" disabled={disabled} onClick={onWishlist} className={link}>찜한 상품</button>
            <button type="button" disabled={disabled} onClick={onAccount} className={link}>{signedIn ? '내 계정' : '로그인 · 회원가입'}</button>
            <button type="button" onClick={() => window.scrollTo({ top: 0, behavior: 'smooth' })} className={`${link} mt-2 flex items-center gap-1.5`}>맨 위로<span className="-rotate-90"><Icon name="arrow" size={12}/></span></button>
          </div>
        </nav>
        <section aria-label="자주 묻는 질문" className="sm:col-span-2 lg:col-span-1">
          <h2 className="text-xs font-black">이용 안내</h2>
          <div className="mt-3 divide-y divide-[var(--line)]">{questions.map(([title, answer], index) => <div key={title} className="py-3">
            <button type="button" id={`${prefix}-question-${index}`} aria-expanded={openQuestion === index} aria-controls={`${prefix}-answer-${index}`} onClick={() => setOpenQuestion(previous => previous === index ? null : index)} className="flex w-full items-start justify-between gap-3 text-left text-xs leading-5 text-[var(--muted)] transition hover:text-[var(--text)]"><span>{title}</span><span className={`mt-1 shrink-0 transition-transform ${openQuestion === index ? 'rotate-90' : ''}`}><Icon name="chevron" size={12}/></span></button>
            <p id={`${prefix}-answer-${index}`} aria-labelledby={`${prefix}-question-${index}`} hidden={openQuestion !== index} className="mt-3 rounded-xl bg-[var(--surface-2)] p-3 text-[11px] leading-6 text-[var(--muted)]">{answer}</p>
          </div>)}</div>
        </section>
      </div>
      <div className="flex items-start gap-3 rounded-2xl bg-[var(--surface-2)] px-4 py-4 sm:px-5">
        <span className="mt-0.5 shrink-0 text-[var(--mint)]"><Icon name="shield" size={17}/></span>
        <div className="text-[11px] leading-6 text-[var(--muted)]"><p className="font-bold text-[var(--text)]">구매 전 꼭 확인해 주세요</p><p>PRIZM은 상품 가격과 구매 판단을 돕는 정보를 제공합니다. 상품 구매와 결제는 각 판매처에서 진행됩니다. 표시된 가격·혜택은 수집 시점의 정보로, 실제 판매 조건과 다를 수 있습니다.</p></div>
      </div>
      <nav aria-label="약관 및 개인정보 안내" className="mt-7 flex flex-wrap items-center gap-5 border-t border-[var(--line)] pt-5 text-xs"><button type="button" onClick={() => openDocument('terms')} className="text-[var(--muted)] hover:text-[var(--primary)]">이용약관</button><button type="button" onClick={() => openDocument('privacy')} className="font-bold text-[var(--text)] hover:text-[var(--primary)]">개인정보처리방침</button><span className="text-[10px] text-[var(--subtle)]">운영 정보 확정 전 초안</span></nav>
      <div className="flex flex-col gap-2 py-5 text-[10px] leading-5 text-[var(--subtle)] sm:flex-row sm:items-center sm:justify-between"><p>© {new Date().getFullYear()} PRIZM. All rights reserved.</p><p>가격 비교부터 구매 결정까지, PRIZM</p></div>
    </div>
    <dialog ref={dialog} aria-labelledby={`${prefix}-legal-title`} onClick={event => { if (event.target === event.currentTarget) dialog.current?.close() }} className="fixed inset-0 m-auto max-h-[85dvh] w-[92vw] max-w-3xl overflow-y-auto rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-0 text-[var(--text)] shadow-xl backdrop:bg-black/50">
      <div className="sticky top-0 flex items-center justify-between gap-4 border-b border-[var(--line)] bg-[var(--surface)] px-6 py-5"><div><h2 id={`${prefix}-legal-title`} className="text-lg font-black">{legalDocuments[document].title}</h2><p className="mt-1 text-[10px] text-[var(--muted)]">작성일 2026. 10. 26. · 시행일 미확정</p></div><form method="dialog"><button className="rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold" autoFocus>닫기</button></form></div>
      <div className="space-y-6 p-6 sm:p-8"><p className="rounded-xl bg-[var(--surface-2)] p-4 text-xs leading-6 text-[var(--muted)]">현재 서비스 구현을 기준으로 작성한 초안입니다. [입력 필요]·[확정 필요] 항목과 실제 계약·설정을 확인한 뒤 최종 문서를 공지합니다.</p>{legalDocuments[document].sections.map(section => <section key={section.title}><h3 className="text-sm font-bold">{section.title}</h3>{section.paragraphs.map((text, index) => <p key={index} className="mt-2 text-xs leading-7 text-[var(--muted)]">{text}</p>)}</section>)}</div>
    </dialog>
  </footer>
}
