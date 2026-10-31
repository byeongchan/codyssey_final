import { useEffect, useId, useRef, useState } from 'react'
import { Icon } from './Icons'
import { site2Categories } from './site2Design'
import { referenceCategories } from './categoryNavigationData'
import './categoryNavigation.css'

export default function CategoryNavigation({ selected, disabled, available, onCategory, onSearch }: {
  selected: string; disabled: boolean; available: string[] | null
  onCategory: (id: string) => void; onSearch: (category: string, keyword: string, group: string) => void
}) {
  const [expanded, setExpanded] = useState<string | null>(selected || null)
  const [openGroup, setOpenGroup] = useState<string | null>(null)
  const [mobileOpen, setMobileOpen] = useState(false)
  const root = useRef<HTMLElement>(null)
  const mobileToggle = useRef<HTMLButtonElement>(null)
  const id = useId()
  useEffect(() => { if(selected) setExpanded(selected); setOpenGroup(null) }, [selected])
  useEffect(() => {
    if(!mobileOpen) return
    const close = (event: PointerEvent) => { if(event.target instanceof Node && !root.current?.contains(event.target)) { setMobileOpen(false); setOpenGroup(null) } }
    document.addEventListener('pointerdown', close)
    return () => document.removeEventListener('pointerdown', close)
  }, [mobileOpen])
  function choose(category: string, keyword?: string, group = '') {
    if(disabled) return
    setMobileOpen(false); setOpenGroup(null)
    if(keyword) onSearch(category, keyword, group); else onCategory(category)
  }
  const supported = (category: string) => category === 'used' || Boolean(available?.includes(category))
  const selectedCategory = site2Categories.find(category => category.id === selected)
  return <aside ref={root} className="category-navigation" onKeyDown={event => {
    if(event.key !== 'Escape') return
    event.preventDefault(); event.stopPropagation()
    if(openGroup) { root.current?.querySelector<HTMLButtonElement>(`[data-group-toggle="${openGroup}"]`)?.focus(); setOpenGroup(null) }
    else if(mobileOpen) { setMobileOpen(false); mobileToggle.current?.focus() }
    else { root.current?.querySelector<HTMLButtonElement>(`[data-category-toggle="${expanded}"]`)?.focus(); setExpanded(null) }
  }}>
    <button ref={mobileToggle} type="button" className="category-mobile-toggle" aria-expanded={mobileOpen} aria-controls={`${id}-panel`} onClick={() => setMobileOpen(!mobileOpen)}><span className="flex items-center gap-2"><Icon name="menu" size={18}/>{selectedCategory?.id ? selectedCategory.short : '전체 카테고리'}</span><span className={mobileOpen ? 'rotate-90' : ''}><Icon name="chevron" size={15}/></span></button>
    <div id={`${id}-panel`} className={`category-navigation-panel ${mobileOpen ? 'is-mobile-open' : ''}`}>
      <button type="button" disabled={disabled} onClick={() => choose('')} className="category-navigation-heading">전체 카테고리</button>
      <nav aria-label="상품 카테고리" className="space-y-1">
        {site2Categories.filter(category => category.id).map(category => {
          const groups = referenceCategories.find(reference => reference.label === category.name)?.subcategories ?? []
          const isExpanded = expanded === category.id
          const enabled = supported(category.id)
          return <div key={category.id}>
            <button type="button" data-category-toggle={category.id} disabled={disabled || !enabled} title={!enabled ? '카테고리 정보를 불러오는 중입니다' : category.name} aria-expanded={isExpanded} aria-controls={`${id}-${category.id}`} aria-current={selected === category.id ? 'page' : undefined} className={`category-navigation-link ${selected === category.id ? 'is-selected' : ''}`} onClick={() => { setExpanded(isExpanded ? null : category.id); setOpenGroup(null) }}>
              <Icon name={category.icon} size={18}/><span className="min-w-0 flex-1 truncate">{category.id === 'pc' ? 'PC 주변기기' : category.short}</span><span className={`category-chevron ${isExpanded ? 'is-expanded' : ''}`}><Icon name="chevron" size={14}/></span>
            </button>
            <div id={`${id}-${category.id}`} className="category-submenu" hidden={!isExpanded}>
              <button type="button" disabled={disabled || !enabled} className="category-submenu-all" onClick={() => choose(category.id)}>카테고리 상품 보기<Icon name="arrow" size={12}/></button>
              {groups.map((group, index) => {
                const key = `${category.id}-${index}`
                return <div className="category-submenu-group" key={group.label} data-open={openGroup === key}
                  onMouseEnter={() => { if(window.matchMedia('(min-width: 1024px) and (hover: hover)').matches) setOpenGroup(key) }}
                  onMouseLeave={event => { if(window.matchMedia('(min-width: 1024px)').matches && !event.currentTarget.contains(document.activeElement)) setOpenGroup(previous => previous === key ? null : previous) }}
                  onFocus={() => { if(window.matchMedia('(min-width: 1024px)').matches) setOpenGroup(key) }}
                  onBlur={event => { if(!event.currentTarget.contains(event.relatedTarget)) setOpenGroup(previous => previous === key ? null : previous) }}>
                  <div className="category-submenu-row"><button type="button" disabled={disabled || !enabled} className="category-submenu-label" onClick={() => choose(category.id, group.label, group.label)}>{group.label}</button><button type="button" disabled={disabled || !enabled} data-group-toggle={key} className="category-group-toggle" aria-label={`${group.label} 세부 항목`} aria-expanded={openGroup === key} aria-controls={`${id}-${key}-items`} onClick={() => setOpenGroup(openGroup === key ? null : key)}><Icon name="chevron" size={12}/></button></div>
                  <div id={`${id}-${key}-items`} className="category-flyout"><p>{group.label}</p><div className="category-flyout-items">{group.items.map(item => <button type="button" disabled={disabled || !enabled} key={item} onClick={() => choose(category.id, item, group.label)}>{item}</button>)}</div></div>
                </div>
              })}
            </div>
          </div>
        })}
      </nav>
    </div>
  </aside>
}
