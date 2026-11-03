import { useEffect, useState, type FormEvent } from 'react'
import { Icon } from './Icons'
import type { ProductProvider } from './productSource'
import {
  brandChoices,
  emptyProductFilters,
  type BrandChoice,
  type ProductFilters,
} from './productFilters'
import {
  classificationBrands,
  classificationDetails,
  classificationGroups,
} from './classificationFilters'
import './productSearchFilters.css'

const input = 'product-filter-select'
export default function ProductSearchFilters({
  categoryId,
  navigationGroup = '',
  navigationItem = '',
  provider,
  applied,
  discoveredBrands = [],
  disabled,
  onApply,
}: {
  categoryId: string
  navigationGroup?: string
  navigationItem?: string
  provider: ProductProvider
  applied: ProductFilters
  discoveredBrands?: BrandChoice[]
  disabled: boolean
  onApply: (filters: ProductFilters) => void
}) {
  const [draft, setDraft] = useState(applied)
  const groups = classificationGroups(categoryId)
  const group = navigationGroup || draft.classificationGroup
  const item = navigationItem || draft.classificationItem
  const items = groups.find((entry) => entry.label === group)?.items ?? []
  const details = classificationDetails[item] ?? []
  const suggested = classificationBrands[item] ?? classificationBrands[group]
  const allChoices = brandChoices(categoryId, provider, [
    ...discoveredBrands,
    ...(applied.brand ? [{ label: applied.brand, code: applied.brandCode || undefined }] : []),
  ])
  const choices: BrandChoice[] = suggested
    ? suggested.map((label) => allChoices.find((choice) => choice.label === label) ?? { label })
    : allChoices
  useEffect(() => {
    setDraft(applied)
  }, [applied, categoryId, navigationGroup, navigationItem, provider])
  function submit(event: FormEvent) {
    event.preventDefault()
    const brand = choices.find((choice) => choice.label === draft.brand)
    // Only classification and brand conditions are exposed; discard former attribute filters.
    onApply({
      ...emptyProductFilters(),
      classificationGroup: group,
      classificationItem: item,
      classification: draft.classification,
      brand: brand?.label ?? '',
      brandCode: brand?.code ?? '',
    })
  }
  const conditions = [
    ...new Set(
      [
        applied.classificationGroup !== navigationGroup && applied.classificationGroup,
        applied.classificationItem !== navigationItem && applied.classificationItem,
        applied.classification,
        applied.brand,
      ].filter(Boolean),
    ),
  ]
  const path = [navigationGroup, navigationItem].filter(Boolean)
  const pending = ['classificationGroup', 'classificationItem', 'classification', 'brand'].some(
    (key) => draft[key as keyof ProductFilters] !== applied[key as keyof ProductFilters],
  )
  return (
    <section className="product-filters" aria-label="카테고리 상세 검색">
      <header className="product-filters-header">
        <div className="product-filters-title">
          <span className="product-filters-icon">
            <Icon name="search" size={17} />
          </span>
          <h2>상세 검색</h2>
          <span className="product-filters-provider">
            {provider === 'musinsa' ? '무신사' : '다나와'}
          </span>
        </div>
        {path.length > 0 ? (
          <nav className="product-filters-path" aria-label="현재 상품 분류">
            {path.map((label, index) => (
              <span key={label}>
                {index > 0 && <Icon name="chevron" size={12} />}
                <span>{label}</span>
              </span>
            ))}
          </nav>
        ) : (
          <p className="product-filters-description">분류와 브랜드로 원하는 상품을 찾아보세요.</p>
        )}
      </header>
      <form onSubmit={submit}>
        <fieldset disabled={disabled} className="product-filters-fields">
          <legend className="sr-only">상품 검색 조건</legend>
          {!navigationGroup && (
            <label className="product-filter-row">
              <span className="product-filter-label">상품 분류</span>
              <span className="product-filter-control">
                <select
                  className={input}
                  value={draft.classificationGroup}
                  onChange={(event) =>
                    setDraft((previous) => ({
                      ...previous,
                      classificationGroup: event.target.value,
                      classificationItem: '',
                      classification: '',
                      brand: '',
                      brandCode: '',
                    }))
                  }
                >
                  <option value="">전체 분류</option>
                  {groups.map((entry) => (
                    <option key={entry.label}>{entry.label}</option>
                  ))}
                </select>
                <span className="product-filter-hint">찾으시는 상품의 분류를 선택해 주세요.</span>
              </span>
            </label>
          )}
          {!navigationItem && items.length > 0 && (
            <label className="product-filter-row">
              <span className="product-filter-label">상품 종류</span>
              <span className="product-filter-control">
                <select
                  className={input}
                  value={draft.classificationItem}
                  onChange={(event) =>
                    setDraft((previous) => ({
                      ...previous,
                      classificationItem: event.target.value,
                      classification: '',
                      brand: '',
                      brandCode: '',
                    }))
                  }
                >
                  <option value="">전체 종류</option>
                  {items.map((label) => (
                    <option key={label}>{label}</option>
                  ))}
                </select>
              </span>
            </label>
          )}
          {details.length > 0 && (
            <label className="product-filter-row">
              <span className="product-filter-label">
                {['아이폰', '갤럭시', '아이패드', '갤럭시탭', '맥북', '애플워치'].includes(item)
                  ? '기종·제품군'
                  : '세부 분류'}
              </span>
              <span className="product-filter-control">
                <select
                  className={input}
                  value={draft.classification}
                  onChange={(event) =>
                    setDraft((previous) => ({ ...previous, classification: event.target.value }))
                  }
                >
                  <option value="">{item} 전체</option>
                  {details.map((label) => (
                    <option key={label}>{label}</option>
                  ))}
                </select>
              </span>
            </label>
          )}
          <label className="product-filter-row">
            <span className="product-filter-label">브랜드·제조사</span>
            <span className="product-filter-control">
              <select
                className={input}
                value={draft.brand}
                onChange={(event) =>
                  setDraft((previous) => ({
                    ...previous,
                    brand: event.target.value,
                    brandCode: '',
                  }))
                }
              >
                <option value="">전체 브랜드</option>
                {choices.map((choice) => (
                  <option key={choice.label}>{choice.label}</option>
                ))}
              </select>
            </span>
          </label>
        </fieldset>
        <div className="product-filters-summary" aria-label="적용된 검색 조건">
          <span className="product-filters-summary-label">
            선택 조건 <strong>{conditions.length}</strong>
          </span>
          <div className="product-filters-chips">
            {conditions.length > 0 ? (
              conditions.map((condition) => (
                <span key={String(condition)} className="product-filter-chip">
                  <Icon name="check" size={12} />
                  {condition}
                </span>
              ))
            ) : (
              <span className="product-filters-empty">전체 상품</span>
            )}
          </div>
        </div>
        <footer className="product-filters-footer">
          <p role="status">
            {disabled
              ? '상품을 검색하고 있습니다.'
              : pending
                ? '변경한 조건을 적용해 주세요.'
                : '조건을 선택한 후 검색해 주세요.'}
          </p>
          <div className="product-filters-actions">
            <button
              type="button"
              disabled={disabled}
              onClick={() => {
                const reset = emptyProductFilters()
                setDraft(reset)
                onApply(reset)
              }}
              className="product-filters-reset"
            >
              <svg
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.8"
                strokeLinecap="round"
                strokeLinejoin="round"
                aria-hidden="true"
              >
                <path d="M3 10a9 9 0 1 1 2 8M3 4v6h6" />
              </svg>
              초기화
            </button>
            <button disabled={disabled} className="product-filters-submit">
              <Icon name="search" size={15} />
              {disabled ? '검색 중' : '선택 조건으로 검색'}
            </button>
          </div>
        </footer>
      </form>
    </section>
  )
}
