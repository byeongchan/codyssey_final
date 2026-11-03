import { openFloatingTool, useFloatingTools } from './floatingTools'
import { useEffect, useId, useState } from 'react'
import { Icon } from './Icons'
import {
  clearRecentProducts,
  recentProducts,
  watchRecentProducts,
  type RecentProduct,
} from './recentProducts'
import { providerName, productIdentity, type ProductProvider } from './productSource'

function ProductImage({ item }: { item: RecentProduct }) {
  const [failed, setFailed] = useState(false)
  return (
    <span className="grid h-14 w-14 shrink-0 place-items-center overflow-hidden rounded-xl bg-[var(--surface-2)]">
      {item.image && !failed ? (
        <img
          src={item.image}
          alt=""
          loading="lazy"
          referrerPolicy="no-referrer"
          className="h-full w-full object-contain p-1"
          onError={() => setFailed(true)}
        />
      ) : (
        <Icon name="search" size={20} />
      )}
    </span>
  )
}

export default function RecentProductsBar({
  disabled,
  onSelect,
}: {
  disabled: boolean
  onSelect: (productId: string, provider?: ProductProvider) => void
}) {
  const [items, setItems] = useState(recentProducts)
  const { tool, aiAvailable } = useFloatingTools()
  const expanded = tool === 'recent'
  const setExpanded = (open: boolean) => openFloatingTool(open ? 'recent' : null)
  const panelId = useId()
  useEffect(() => watchRecentProducts(() => setItems(recentProducts())), [])
  useEffect(() => {
    if (tool !== 'recent' && tool !== 'menu') return
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setExpanded(false)
    }
    window.addEventListener('keydown', close)
    return () => window.removeEventListener('keydown', close)
  }, [tool])
  const select = (item: RecentProduct) => {
    if (!disabled) {
      setExpanded(false)
      onSelect(item.productId, item.provider ?? 'danawa')
    }
  }
  return (
    <>
      <aside
        aria-label="최근 본 상품"
        className="recent-products-rail z-30 rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-2 shadow-[var(--shadow)]"
      >
        <h2 className="py-2 text-center text-[11px] font-bold">최근 본 상품</h2>
        {!items.length && (
          <p className="px-1 py-5 text-center text-[11px] leading-5 text-[var(--muted)]">
            최근 본 상품이 없습니다.
          </p>
        )}
        <ol className="space-y-2">
          {items.map((item) => (
            <li key={productIdentity(item.productId, item.provider)}>
              <button
                type="button"
                disabled={disabled}
                onClick={() => select(item)}
                title={`${providerName(item.provider ?? 'danawa')} · ${item.title}\n마지막으로 본 가격: ${item.price === null ? '미확인' : `${item.price.toLocaleString('ko-KR')}원`}`}
                aria-label={`${item.title} 상세 보기`}
                className="flex w-full flex-col items-center gap-1 rounded-xl py-1 text-[var(--muted)] hover:bg-[var(--surface-2)] disabled:opacity-40"
              >
                <ProductImage
                  key={`${productIdentity(item.productId, item.provider)}:${item.image}`}
                  item={item}
                />
                <span className="max-w-full truncate text-[10px] font-bold">
                  {item.price === null ? '가격 미확인' : `${item.price.toLocaleString('ko-KR')}원`}
                </span>
              </button>
            </li>
          ))}
        </ol>
        <p className="mt-2 text-center text-[9px] text-[var(--subtle)]">마지막으로 본 가격</p>
        <button
          type="button"
          onClick={clearRecentProducts}
          className="mt-2 w-full border-t border-[var(--line)] pt-2 text-[10px] text-[var(--muted)]"
        >
          기록 지우기
        </button>
      </aside>
      <div className="recent-products-mobile floating-tools fixed bottom-4 right-4 z-[71] max-w-[calc(100vw-2rem)]">
        {tool === 'menu' && (
          <div
            id={panelId}
            className="floating-tools-menu mb-3 rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-2 shadow-[var(--shadow-lg)]"
          >
            {aiAvailable && (
              <button type="button" className="xl:hidden" onClick={() => openFloatingTool('ai')}>
                AI 구매 비서
              </button>
            )}
            <button type="button" onClick={() => setExpanded(true)}>
              최근 본 상품 {items.length}
            </button>
          </div>
        )}
        {expanded && (
          <section
            id={panelId}
            aria-label="최근 본 상품 목록"
            className="mb-3 max-h-[60dvh] w-80 max-w-full overflow-y-auto rounded-2xl border border-[var(--line)] bg-[var(--surface)] p-4 shadow-[var(--shadow-lg)]"
          >
            <div className="mb-3 flex items-center justify-between gap-3">
              <h2 className="text-sm font-bold">최근 본 상품</h2>
              <button
                type="button"
                onClick={() => setExpanded(false)}
                className="text-xs text-[var(--muted)]"
              >
                닫기
              </button>
            </div>
            {!items.length && (
              <p className="py-6 text-center text-xs text-[var(--muted)]">
                최근 본 상품이 없습니다.
              </p>
            )}
            <ol className="divide-y divide-[var(--line)]">
              {items.map((item) => (
                <li key={productIdentity(item.productId, item.provider)}>
                  <button
                    type="button"
                    disabled={disabled}
                    onClick={() => select(item)}
                    className="flex w-full items-center gap-3 py-3 text-left disabled:opacity-40"
                  >
                    <ProductImage
                      key={`${productIdentity(item.productId, item.provider)}:${item.image}`}
                      item={item}
                    />
                    <span className="min-w-0">
                      <span className="block text-[10px] text-[var(--subtle)]">
                        {providerName(item.provider ?? 'danawa')}
                      </span>
                      <span className="line-clamp-2 text-xs font-medium">{item.title}</span>
                      <span className="mt-1 block text-xs font-bold text-[var(--primary)]">
                        {item.price === null
                          ? '가격 미확인'
                          : `${item.price.toLocaleString('ko-KR')}원`}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ol>
            <div className="mt-3 flex items-center justify-between gap-2 border-t border-[var(--line)] pt-3 text-[10px] text-[var(--muted)]">
              <span>마지막으로 본 가격 · 최대 5개</span>
              <button
                type="button"
                onClick={() => {
                  clearRecentProducts()
                  setExpanded(false)
                }}
              >
                기록 지우기
              </button>
            </div>
          </section>
        )}
        <button
          id="shopping-tools-trigger"
          type="button"
          aria-expanded={tool !== null}
          aria-controls={tool === 'recent' || tool === 'menu' ? panelId : undefined}
          onClick={() => openFloatingTool(tool === 'menu' ? null : 'menu')}
          className="ml-auto flex items-center gap-2 rounded-full border border-[var(--line)] bg-[var(--surface)] px-4 py-3 text-[var(--text)] shadow-[var(--shadow-lg)]"
        >
          <Icon name="clock" size={17} />
          <span className="text-xs font-bold">쇼핑 도구</span>
          <span className={`transition-transform ${expanded ? 'rotate-90' : '-rotate-90'}`}>
            <Icon name="chevron" size={12} />
          </span>
        </button>
      </div>
    </>
  )
}
