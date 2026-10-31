import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Icon } from './Icons'
import { filterRegionDirectory, loadRegionDirectory } from './daangnRegionDirectory'

type Region = { id: string; name: string }
export default function RegionSelectionModal({ selected, popular, nearby, loading, error, onLoad, onSelect, onClose }: {
  selected: Region | null; popular: Region[]; nearby: Region[]; loading: 'popular' | 'nearby' | null; error: string
  onLoad: (mode: 'popular' | 'nearby') => void; onSelect: (region: Region) => void; onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const [directory, setDirectory] = useState<Awaited<ReturnType<typeof loadRegionDirectory>>>([])
  const [directoryLoading, setDirectoryLoading] = useState(true), [directoryError, setDirectoryError] = useState('')
  const [revision, setRevision] = useState(0), [active, setActive] = useState(-1)
  const id = useId()
  useEffect(() => {
    const element = dialog.current, previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'; element?.showModal(); input.current?.focus()
    return () => { element?.close(); document.body.style.overflow = overflow; if (previous?.isConnected) previous.focus() }
  }, [])
  useEffect(() => {
    let disposed = false; setDirectoryLoading(true); setDirectoryError('')
    void loadRegionDirectory().then(rows => { if (!disposed) setDirectory(rows) }).catch(e => {
      if (!disposed) setDirectoryError(e instanceof Error ? e.message : '지역 목록을 불러오지 못했습니다.')
    }).finally(() => { if (!disposed) setDirectoryLoading(false) })
    return () => { disposed = true }
  }, [revision])
  const searching = Boolean(query.trim())
  const matches = useMemo(() => filterRegionDirectory(directory, query), [directory, query])
  const visible = matches.slice(0, 50)
  useEffect(() => { setActive(-1) }, [query])
  function options(title: string, rows: Region[], mode: 'popular' | 'nearby') {
    return <section className="used-region-modal-group"><div className="used-region-modal-group-heading"><h3>{title}</h3><button type="button" disabled={Boolean(loading) || mode === 'nearby' && !selected} onClick={() => onLoad(mode)}>{loading === mode ? '조회 중' : rows.length ? '다시 조회' : '불러오기'}</button></div>
      {rows.length > 0 ? <div className="used-region-modal-chips">{rows.map(region => <button type="button" key={region.id} aria-pressed={selected?.id === region.id} onClick={() => onSelect(region)}>{region.name}</button>)}</div> : <p className="used-region-modal-note">{loading === mode ? '동네를 불러오고 있습니다.' : mode === 'nearby' && !selected ? '동네를 선택하면 주변 지역도 확인할 수 있어요.' : '동네 목록을 불러와 선택할 수 있어요.'}</p>}
    </section>
  }
  return <dialog ref={dialog} className="used-region-dialog" aria-labelledby={`${id}-title`} onCancel={event => { event.preventDefault(); onClose() }} onKeyDown={event => { if (event.key === 'Escape' && !event.nativeEvent.isComposing) { event.preventDefault(); onClose() } }} onClick={event => { if (event.target === event.currentTarget) onClose() }}>
    <div className="used-region-modal-shell">
      <header className="used-region-modal-header"><h2 id={`${id}-title`}>지역 변경</h2><button type="button" aria-label="지역 선택 닫기" onClick={onClose}><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></header>
      <div className="used-region-modal-search"><label htmlFor={`${id}-input`}>지역 이름</label><div className="used-region-modal-input"><input ref={input} id={`${id}-input`} type="search" autoComplete="off" maxLength={60} placeholder="지역명을 입력해 주세요" value={query} onChange={event => setQuery(event.target.value)} role="combobox" aria-autocomplete="list" aria-expanded={searching && !directoryLoading && !directoryError} aria-controls={`${id}-results`} aria-activedescendant={searching && active >= 0 && visible[active] ? `${id}-option-${active}` : undefined} onKeyDown={event => {
        if (event.nativeEvent.isComposing || !searching) return
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault()
          const next = visible.length ? event.key === 'ArrowDown' ? Math.min(active + 1, visible.length - 1) : Math.max(active - 1, 0) : -1
          setActive(next); if (next >= 0) document.getElementById(`${id}-option-${next}`)?.scrollIntoView({ block: 'nearest' })
        }
        if (event.key === 'Enter') {
          event.preventDefault(); const candidate = active >= 0 ? visible[active] : visible.length === 1 ? visible[0] : undefined
          if (candidate) onSelect(candidate)
        }
      }}/><Icon name="search" size={18}/></div>{selected && <p className="used-region-modal-current">현재 선택한 지역 <strong>{selected.name}</strong></p>}</div>
      <div className="used-region-modal-body">
        {searching ? <><div className="used-region-modal-results-heading"><h3>검색 결과</h3><span role="status">{directoryLoading ? '불러오는 중' : directoryError ? '' : `${matches.length.toLocaleString('ko-KR')}개`}</span></div>
          {directoryError ? <div className="used-region-modal-empty" role="alert"><p>{directoryError}</p><button type="button" className="used-secondary" onClick={() => setRevision(previous => previous + 1)}>다시 불러오기</button></div> : directoryLoading ? <p role="status" className="used-region-modal-empty">지역 목록을 불러오고 있습니다.</p> : <>
            <ul id={`${id}-results`} role="listbox" aria-label="동네 검색 결과" className="used-region-modal-results">{visible.map((region, index) => <li key={region.id}><button id={`${id}-option-${index}`} type="button" role="option" aria-selected={selected?.id === region.id || active === index} tabIndex={-1} onMouseEnter={() => setActive(index)} onClick={() => onSelect(region)}><span>{region.name}</span><Icon name={selected?.id === region.id ? 'check' : 'chevron'} size={15}/></button></li>)}</ul>
            {!visible.length && <div className="used-region-modal-empty"><Icon name="search" size={28}/><p>검색 결과가 없습니다.</p><span>지역명을 확인하거나 다른 이름으로 검색해 주세요.</span></div>}
            {matches.length > 50 && <p className="used-region-modal-note">일부 결과를 표시하고 있습니다. 지역명을 더 입력하면 후보를 좁힐 수 있어요.</p>}
          </>}
        </> : <>{options('주변 지역', nearby, 'nearby')}{options('인기 동네', popular, 'popular')}{error && <p className="used-region-modal-error" role="alert">{error}</p>}</>}
      </div>
    </div>
  </dialog>
}
