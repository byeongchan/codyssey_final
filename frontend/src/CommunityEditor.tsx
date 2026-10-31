import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import CommunityContent from './CommunityContent'
import { documentFromEditor, hasCommunityContent, readRichDocument, richTextLength, safeCommunityLink } from './communityRichText'

export default function CommunityEditor({ value, onChange, disabled, initialFormat = 'plain' }: {
  value: string; onChange: (value: string) => void; disabled: boolean; initialFormat?: string
}) {
  const editor = useRef<HTMLDivElement>(null)
  const selection = useRef<Range | null>(null), lastHtml = useRef('')
  const [error, setError] = useState('')
  const [marks, setMarks] = useState({ bold: false, italic: false, strikeThrough: false })
  function rememberSelection() {
    const selected = window.getSelection()
    if (editor.current && selected?.rangeCount && editor.current.contains(selected.getRangeAt(0).commonAncestorContainer)) {
      selection.current = selected.getRangeAt(0).cloneRange()
      setMarks({ bold: document.queryCommandState('bold'), italic: document.queryCommandState('italic'), strikeThrough: document.queryCommandState('strikeThrough') })
    }
  }
  function restoreSelection() {
    const root = editor.current, selected = window.getSelection(); if (!root || !selected) return
    root.focus(); const range = selection.current
    if (range && root.contains(range.commonAncestorContainer)) { selected.removeAllRanges(); selected.addRange(range) }
    else { const end = document.createRange(); end.selectNodeContents(root); end.collapse(false); selected.removeAllRanges(); selected.addRange(end) }
  }
  function sync() {
    const root = editor.current; if (!root) return
    const next = JSON.stringify(documentFromEditor(root))
    if (!readRichDocument(next)) { root.innerHTML = lastHtml.current; selection.current = null; restoreSelection(); setError('본문은 10,000자 이내로 작성하고 서식을 간단히 정리해 주세요.'); return }
    root.dataset.empty = String(!hasCommunityContent(next)); lastHtml.current = root.innerHTML; onChange(hasCommunityContent(next) ? next : ''); setError(''); rememberSelection()
  }
  useLayoutEffect(() => {
    const root = editor.current; if (!root) return
    // React escapes all text/attributes; never assign server-supplied HTML to the editor.
    const markup = initialFormat === 'plain'
      ? renderToStaticMarkup(<p>{value.split('\n').map((line, index) => <span key={index}>{index > 0 && <br/>}{line}</span>)}</p>)
      : renderToStaticMarkup(<CommunityContent content={value} format={initialFormat}/>)
    const wrapper = document.createElement('div'); wrapper.innerHTML = markup
    root.innerHTML = wrapper.querySelector('.board-rich-content')?.innerHTML ?? markup
    lastHtml.current = root.innerHTML
    const next = JSON.stringify(documentFromEditor(root)); root.dataset.empty = String(!hasCommunityContent(next)); onChange(hasCommunityContent(next) ? next : '')
  }, [])
  useEffect(() => { document.addEventListener('selectionchange', rememberSelection); return () => document.removeEventListener('selectionchange', rememberSelection) }, [])
  function command(name: string, argument?: string) {
    if (disabled) return
    restoreSelection(); document.execCommand('styleWithCSS', false, 'false')
    document.execCommand(name, false, argument); sync()
  }
  function block(tag: string) { restoreSelection(); command('formatBlock', document.queryCommandValue('formatBlock').toLowerCase() === tag ? 'p' : tag) }
  const tools: { label: string; title: string; content: ReactNode; active?: boolean; action: () => void }[] = [
    { label: '굵게', title: '굵게 (Ctrl+B)', content: <strong>B</strong>, active: marks.bold, action: () => command('bold') },
    { label: '기울임', title: '기울임 (Ctrl+I)', content: <em>I</em>, active: marks.italic, action: () => command('italic') },
    { label: '취소선', title: '취소선', content: <s>S</s>, active: marks.strikeThrough, action: () => command('strikeThrough') },
    { label: '소제목', title: '소제목', content: 'H', action: () => block('h3') },
    { label: '글머리 목록', title: '글머리 목록', content: '☷', action: () => command('insertUnorderedList') },
    { label: '번호 목록', title: '번호 목록', content: '1.', action: () => command('insertOrderedList') },
    { label: '인용', title: '인용', content: '❝', action: () => block('blockquote') },
    { label: '코드 블록', title: '코드 블록', content: '</>', action: () => block('pre') },
    { label: '링크 삽입', title: '링크 삽입', content: '↗', action: () => {
      rememberSelection(); const value = window.prompt('연결할 주소를 입력해 주세요.', 'https://'); if (!value) return
      const url = safeCommunityLink(value); if (!url) { setError('올바른 HTTP 또는 HTTPS 주소를 입력해 주세요.'); return }
      restoreSelection()
      if (window.getSelection()?.isCollapsed) { const link = document.createElement('a'); link.href = url; link.textContent = url; command('insertHTML', link.outerHTML) }
      else command('createLink', url)
    } },
  ]
  return <div className="board-compose">
    <div className="board-editor-toolbar" role="group" aria-label="본문 서식"><div className="board-format-tools">{tools.map(tool => <button key={tool.label} type="button" disabled={disabled} title={tool.title} aria-label={tool.label} aria-pressed={tool.active} onMouseDown={event => event.preventDefault()} onClick={tool.action}>{tool.content}</button>)}</div><span className="board-editor-live-label">서식 바로 적용</span></div>
    <div id="board-content" ref={editor} className="board-rich-content board-rich-document board-visual-editor" role="textbox" aria-label="게시글 내용" aria-multiline="true" aria-required="true" aria-disabled={disabled} contentEditable={!disabled} suppressContentEditableWarning data-placeholder="내용을 입력해 주세요" onInput={event => { if (!(event.nativeEvent as InputEvent).isComposing) sync() }} onCompositionEnd={sync} onMouseUp={rememberSelection} onKeyUp={rememberSelection} onClick={event => { if (event.target instanceof Element && event.target.closest('a')) event.preventDefault() }} onPaste={event => { event.preventDefault(); const text = event.clipboardData.getData('text/plain'); command('insertText', text) }} onDrop={event => event.preventDefault()} onKeyDown={event => { if ((event.ctrlKey || event.metaKey) && ['b', 'i'].includes(event.key.toLowerCase())) { event.preventDefault(); command(event.key.toLowerCase() === 'b' ? 'bold' : 'italic') } }}/>
    <div className="board-compose-status"><span>텍스트와 서식으로 작성할 수 있습니다.</span><span>{richTextLength(value).toLocaleString()} / 10,000</span></div>
    {error && <p role="alert" className="board-editor-error">{error}</p>}
  </div>
}
