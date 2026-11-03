import { useEffect, useState } from 'react'
import { site2Suggestions } from './site2Design'
import './recommendedSearches.css'

export default function RecommendedSearches({ disabled, onSearch }: { disabled: boolean; onSearch: (word: string) => void }) {
  const [page, setPage] = useState(0)
  const [paused, setPaused] = useState(false)
  const [resetting, setResetting] = useState(false)
  const count = Math.ceil(site2Suggestions.length / 3)
  useEffect(() => {
    const media = window.matchMedia('(max-width: 1279px)')
    let timer: ReturnType<typeof setInterval> | undefined
    const update = () => {
      clearInterval(timer)
      if (media.matches && !paused && !disabled && count > 1) timer = setInterval(() => {
        if (!document.hidden) { setResetting(false); setPage(previous => previous >= count ? 0 : previous + 1) }
      }, 4000)
    }
    update(); media.addEventListener('change', update)
    return () => { clearInterval(timer); media.removeEventListener('change', update) }
  }, [paused, disabled, count])
  useEffect(() => {
    if (page !== count) return
    const timer = setTimeout(() => { setResetting(true); setPage(0) }, 850)
    return () => clearTimeout(timer)
  }, [page, count])
  const button = (index: number) => {
    const word = site2Suggestions[index]
    return <button key={index} type="button" disabled={disabled} title={word} onClick={() => onSearch(word)}><span>{index + 1}</span> {word}</button>
  }
  return <div className="recommended-searches" onFocus={event => { if (event.target.matches(':focus-visible')) setPaused(true) }} onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setPaused(false) }}>
    <div className="recommended-desktop">{site2Suggestions.map((_, index) => button(index))}</div>
    <div className="recommended-mobile" aria-label="추천 검색어">
      <div className={`recommended-track ${resetting ? 'is-resetting' : ''}`} style={{ transform: `translate3d(0, -${page * 32}px, 0)` }} onTransitionEnd={event => {
        if (event.target === event.currentTarget && page >= count) { setResetting(true); setPage(0) }
      }}>{Array.from({ length: count + 1 }, (_, row) => <div key={row} className={`recommended-row ${row === page ? 'is-current' : ''}`} aria-hidden={row !== page} inert={row !== page}>{site2Suggestions.slice((row % count) * 3, (row % count) * 3 + 3).map((_, column) => button((row % count) * 3 + column))}</div>)}</div>
    </div>
  </div>
}
