import { Fragment, type ReactNode } from 'react'
import { readRichDocument, type RichNode } from './communityRichText'

export default function CommunityContent({
  content,
  format = 'markdown-v1',
}: {
  content: string
  format?: string
}) {
  if (format === 'richtext-v1') {
    const doc = readRichDocument(content)
    if (!doc) return <p className="board-muted">본문 형식을 확인하지 못했습니다.</p>
    function rich(node: RichNode, key: number): ReactNode {
      const children = node.children?.map(rich)
      switch (node.type) {
        case 'text':
          return node.text
        case 'image':
          return null // Legacy attachment nodes are no longer displayed.
        case 'br':
          return <br key={key} />
        case 'hr':
          return <hr key={key} />
        case 'p':
          return <p key={key}>{children}</p>
        case 'h2':
          return <h2 key={key}>{children}</h2>
        case 'h3':
          return <h3 key={key}>{children}</h3>
        case 'h4':
          return <h4 key={key}>{children}</h4>
        case 'strong':
          return <strong key={key}>{children}</strong>
        case 'em':
          return <em key={key}>{children}</em>
        case 'del':
          return <del key={key}>{children}</del>
        case 'ul':
          return <ul key={key}>{children}</ul>
        case 'ol':
          return <ol key={key}>{children}</ol>
        case 'li':
          return <li key={key}>{children}</li>
        case 'blockquote':
          return <blockquote key={key}>{children}</blockquote>
        case 'pre':
          return <pre key={key}>{children}</pre>
        case 'code':
          return <code key={key}>{children}</code>
        case 'a':
          return (
            <a key={key} href={node.href} target="_blank" rel="noopener noreferrer">
              {children}
            </a>
          )
        default:
          return null
      }
    }
    return <div className="board-rich-content board-rich-document">{doc.children.map(rich)}</div>
  }
  function inline(text: string): ReactNode[] {
    const pattern =
      /!\[([^\]\n]*)\]\(attachment:([a-f0-9-]{36})\)|\[([^\]\n]+)\]\((https?:\/\/[^\s)]+)\)|\*\*([^*\n]+)\*\*|\*([^*\n]+)\*|~~([^~\n]+)~~|`([^`\n]+)`/g
    const nodes: ReactNode[] = []
    let previous = 0
    for (const match of text.matchAll(pattern)) {
      const index = match.index ?? 0
      nodes.push(text.slice(previous, index))
      const key = index
      if (match[2]) {
        // Hide legacy attachment markers without making a storage request.
      } else if (match[4]) {
        nodes.push(
          <a key={key} href={match[4]} target="_blank" rel="noopener noreferrer">
            {match[3]}
          </a>,
        )
      } else if (match[5]) nodes.push(<strong key={key}>{match[5]}</strong>)
      else if (match[6]) nodes.push(<em key={key}>{match[6]}</em>)
      else if (match[7]) nodes.push(<del key={key}>{match[7]}</del>)
      else nodes.push(<code key={key}>{match[8]}</code>)
      previous = index + match[0].length
    }
    nodes.push(text.slice(previous))
    return nodes
  }
  const lines = content.replace(/\r\n?/g, '\n').split('\n')
  const blocks: ReactNode[] = []
  for (let i = 0; i < lines.length; ) {
    const line = lines[i]
    const key = i
    if (!line.trim()) {
      i++
      continue
    }
    if (/^```/.test(line)) {
      const code: string[] = []
      i++
      while (i < lines.length && !/^```/.test(lines[i])) code.push(lines[i++])
      if (i < lines.length) i++
      blocks.push(
        <pre key={key}>
          <code>{code.join('\n')}</code>
        </pre>,
      )
      continue
    }
    const heading = /^(#{1,3})\s+(.+)$/.exec(line)
    if (heading) {
      blocks.push(
        heading[1].length === 1 ? (
          <h2 key={key}>{inline(heading[2])}</h2>
        ) : heading[1].length === 2 ? (
          <h3 key={key}>{inline(heading[2])}</h3>
        ) : (
          <h4 key={key}>{inline(heading[2])}</h4>
        ),
      )
      i++
      continue
    }
    if (/^\s*---\s*$/.test(line)) {
      blocks.push(<hr key={key} />)
      i++
      continue
    }
    if (/^>\s?/.test(line)) {
      const quoted: string[] = []
      while (i < lines.length && /^>\s?/.test(lines[i]))
        quoted.push(lines[i++].replace(/^>\s?/, ''))
      blocks.push(
        <blockquote key={key}>
          {quoted.map((text, n) => (
            <Fragment key={n}>
              {n > 0 && <br />}
              {inline(text)}
            </Fragment>
          ))}
        </blockquote>,
      )
      continue
    }
    const ordered = /^\d+\.\s+/.test(line)
    if (ordered || /^[-*]\s+/.test(line)) {
      const items: ReactNode[] = []
      const pattern = ordered ? /^\d+\.\s+/ : /^[-*]\s+/
      while (i < lines.length && pattern.test(lines[i]))
        items.push(<li key={i}>{inline(lines[i++].replace(pattern, ''))}</li>)
      blocks.push(ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>)
      continue
    }
    const paragraph: string[] = [lines[i++]]
    while (
      i < lines.length &&
      lines[i].trim() &&
      !/^(#{1,3}\s|>|```|[-*]\s|\d+\.\s|\s*---\s*$)/.test(lines[i])
    )
      paragraph.push(lines[i++])
    blocks.push(
      <p key={key}>
        {paragraph.map((text, n) => (
          <Fragment key={n}>
            {n > 0 && <br />}
            {inline(text)}
          </Fragment>
        ))}
      </p>,
    )
  }
  return <div className="board-rich-content">{blocks}</div>
}
