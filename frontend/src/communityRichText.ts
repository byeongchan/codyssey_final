export type RichNode = {
  type: string
  text?: string
  href?: string
  id?: string
  children?: RichNode[]
}
export type RichDocument = { type: 'doc'; children: RichNode[] }
const containers = new Set([
  'p',
  'h2',
  'h3',
  'h4',
  'strong',
  'em',
  'del',
  'ul',
  'ol',
  'li',
  'blockquote',
  'pre',
  'code',
  'a',
])
const imageId = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/
export function safeCommunityLink(value: string) {
  try {
    const url = new URL(value)
    return value.length <= 2048 &&
      ['http:', 'https:'].includes(url.protocol) &&
      !url.username &&
      !url.password
      ? url.href
      : null
  } catch {
    return null
  }
}
export function readRichDocument(value: string): RichDocument | null {
  if (value.length > 100000) return null
  try {
    const doc = JSON.parse(value)
    let count = 0,
      text = 0
    function valid(node: RichNode, depth: number): boolean {
      if (!node || typeof node !== 'object' || ++count > 2000 || depth > 16) return false
      if (node.type === 'text') {
        if (typeof node.text !== 'string') return false
        text += node.text.length
        return text <= 10000
      }
      if (node.type === 'br' || node.type === 'hr') return true
      if (node.type === 'image') return typeof node.id === 'string' && imageId.test(node.id)
      if (!(depth === 0 && node.type === 'doc') && !containers.has(node.type)) return false
      if (node.type === 'a' && (typeof node.href !== 'string' || !safeCommunityLink(node.href)))
        return false
      return Array.isArray(node.children) && node.children.every((child) => valid(child, depth + 1))
    }
    return doc.type === 'doc' && valid(doc, 0) ? doc : null
  } catch {
    return null
  }
}
export function richTextLength(value: string) {
  const doc = readRichDocument(value)
  if (!doc) return value.length
  function count(node: RichNode): number {
    return node.type === 'text'
      ? node.text!.length
      : (node.children ?? []).reduce((sum, child) => sum + count(child), 0)
  }
  return count(doc)
}
export function hasCommunityContent(value: string) {
  const doc = readRichDocument(value)
  if (!doc) return Boolean(value.trim())
  function content(node: RichNode): boolean {
    return (
      node.type === 'hr' ||
      (node.type === 'text' && Boolean(node.text?.trim())) ||
      Boolean(node.children?.some(content))
    )
  }
  return content(doc)
}
/** Serialize only a small set of semantic elements. Pasted HTML/attributes are never stored. */
export function documentFromEditor(root: HTMLElement): RichDocument {
  function nodes(parent: Node): RichNode[] {
    return [...parent.childNodes].flatMap((child): RichNode[] => {
      if (child.nodeType === Node.TEXT_NODE)
        return child.textContent
          ? [{ type: 'text', text: child.textContent.replace(/\u00a0/g, ' ') }]
          : []
      if (!(child instanceof HTMLElement)) return []
      const tag = child.tagName.toLowerCase()
      if (['script', 'style', 'iframe', 'object'].includes(tag)) return []
      if (tag === 'img') return []
      if (tag === 'br' || tag === 'hr') return [{ type: tag }]
      const type =
        ({ b: 'strong', i: 'em', s: 'del', strike: 'del', div: 'p' } as Record<string, string>)[
          tag
        ] ?? tag
      const children = nodes(child)
      if (type === 'a') {
        const href = safeCommunityLink(child.getAttribute('href') ?? '')
        return href ? [{ type, href, children }] : children
      }
      if (!containers.has(type)) return children
      if (
        type === 'p' &&
        children.some((node) =>
          ['p', 'ul', 'ol', 'blockquote', 'pre', 'h2', 'h3', 'h4'].includes(node.type),
        )
      )
        return children
      return [{ type, children }]
    })
  }
  return { type: 'doc', children: nodes(root) }
}
