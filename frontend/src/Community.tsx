import { useEffect, useState, type FormEvent } from 'react'
import { request } from './api'
import { useSession } from './session'
import type { MemberProfile } from './memberProfile'
import { Icon } from './Icons'
import { hasCommunityContent } from './communityRichText'
import './community.css'
import CommunityEditor from './CommunityEditor'
import CommunityContent from './CommunityContent'

type Article = { id: string; title: string; content: string; kind: 'NOTICE' | 'GENERAL'; authorUid: string; authorName: string; createdAt: number; deleted: boolean; format?: 'plain' | 'markdown-v1' | 'richtext-v1'; updatedAt?: number }
type Comment = { id: string; content: string; authorUid: string; authorName: string; createdAt: number; editedAt?: number }
type Page<T> = { items: T[]; nextCursor: string }
type PostPage = Page<Article> & { pinnedItems?: Article[] }
type Result<T> = { data: T }
const button = 'board-button'
const input = 'board-input'
const date = (value: number) => new Date(value).toLocaleString('ko-KR')
const RECENT_POST_MILLIS = 24 * 60 * 60 * 1000
const shortDate = (value: number, now: number) => {
  const elapsed = now - value
  return elapsed >= 0 && elapsed < RECENT_POST_MILLIS
    ? new Date(value).toLocaleTimeString('ko-KR', { timeZone: 'Asia/Seoul', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    : new Date(value).toLocaleDateString('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' })
}

export default function Community({ onLogin }: { onLogin: () => void }) {
  const { user } = useSession()
  const [profile, setProfile] = useState<MemberProfile | null>(null)
  const [posts, setPosts] = useState<PostPage>({ items: [], nextCursor: '' })
  const [article, setArticle] = useState<Article | null>(null)
  const [comments, setComments] = useState<Page<Comment>>({ items: [], nextCursor: '' })
  const [writing, setWriting] = useState(false)
  const [editingPost, setEditingPost] = useState<Article | null>(null)
  const [editingComment, setEditingComment] = useState<{ id: string; content: string; version: number } | null>(null)
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [kind, setKind] = useState('GENERAL')
  const [reply, setReply] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [now, setNow] = useState(Date.now)
  const [filter, setFilter] = useState<'ALL' | 'GENERAL' | 'NOTICE'>('ALL')
  const activePosts = posts.items.filter(post => !post.deleted)
  const newestFirst = (a: Article, b: Article) => b.createdAt - a.createdAt || b.id.localeCompare(a.id)
  // Include notices from the regular page as well, even if pinnedItems was omitted.
  const pinnedPosts = [...new Map([...(posts.pinnedItems ?? []), ...activePosts]
    .filter(post => !post.deleted && post.kind === 'NOTICE')
    .map(post => [post.id, post])).values()].sort(newestFirst).slice(0, 5)
  const pinnedIds = new Set(pinnedPosts.map(post => post.id))
  const chronologicalPosts = [...pinnedPosts, ...activePosts.filter(post => !pinnedIds.has(post.id))]
    .sort(newestFirst)
  const visiblePosts = filter === 'ALL'
    ? [...pinnedPosts, ...activePosts.filter(post => !pinnedIds.has(post.id))]
    : chronologicalPosts.filter(post => post.kind === filter)
  const admin = profile?.role === 'ADMIN'
  useEffect(() => {
    const update = () => setNow(Date.now())
    const timer = window.setInterval(update, 60000)
    window.addEventListener('focus', update)
    return () => { window.clearInterval(timer); window.removeEventListener('focus', update) }
  }, [])
  useEffect(() => {
    const abort = new AbortController()
    setBusy(true); setError(''); setArticle(null); setWriting(false); setEditingPost(null); setEditingComment(null); setReply(''); setProfile(null)
    setTitle(''); setContent(''); setKind('GENERAL')
    void Promise.all([
      request<Result<PostPage>>('/community/posts', abort.signal).then(result => { setPosts(result.data); setNow(Date.now()) }),
      user ? request<Result<MemberProfile>>('/me/profile', abort.signal).then(result => setProfile(result.data)) : Promise.resolve(),
    ]).catch(e => { if (!abort.signal.aborted) setError(e.message) }).finally(() => { if (!abort.signal.aborted) setBusy(false) })
    return () => abort.abort()
  }, [user?.uid, revision])
  async function run(action: () => Promise<void>) {
    setBusy(true); setError('')
    try { await action() } catch (e) { setError(e instanceof Error ? e.message : '요청에 실패했습니다.') } finally { setNow(Date.now()); setBusy(false) }
  }
  async function open(id: string) {
    const result = await request<Result<Article>>(`/community/posts/${id}`)
    const replies = await request<Result<Page<Comment>>>(`/community/posts/${id}/comments`)
    setArticle(result.data); setComments(replies.data); setReply(''); setWriting(false); setEditingPost(null); setEditingComment(null)
  }
  function startPostEdit(post: Article) {
    setEditingPost(post); setTitle(post.title); setContent(post.content); setKind(post.kind)
    setWriting(true); setError(''); setEditingComment(null)
  }
  function cancelPostEdit() {
    setWriting(false)
    setTitle(''); setContent(''); setEditingPost(null); setKind('GENERAL')
    setError('')
  }
  async function saveComment() {
    if(!editingComment) return
    const result = await request<Result<Comment>>(`/me/community/comments/${editingComment.id}`, undefined, 'PATCH', { content: editingComment.content, version: editingComment.version })
    setComments(previous => ({ ...previous, items: previous.items.map(comment => comment.id === result.data.id ? result.data : comment) }))
    setEditingComment(null)
  }
  function publish(event: FormEvent) {
    event.preventDefault()
    void run(async () => {
      if (!hasCommunityContent(content)) throw new Error('본문을 입력해 주세요.')
      const data = { title, content, kind, format: 'richtext-v1', ...(editingPost ? { version: editingPost.updatedAt ?? editingPost.createdAt } : {}) }
      const result = await request<Result<{ id: string }>>(editingPost ? `/me/community/posts/${editingPost.id}` : '/me/community/posts', undefined, editingPost ? 'PATCH' : 'POST', data)
      setTitle(''); setContent(''); setKind('GENERAL')
      await open(result.data.id)
      const refreshed = await request<Result<PostPage>>('/community/posts')
      setPosts(refreshed.data)
    })
  }
  return <section className="community-board" aria-busy={busy}>
    <div className="board-breadcrumb"><span>줍줍각</span><Icon name="chevron" size={12}/><span>커뮤니티</span>{(writing || article) && <><Icon name="chevron" size={12}/><span>{writing ? editingPost ? '글 수정' : '글쓰기' : article?.kind === 'NOTICE' ? '공지사항' : '일반 게시글'}</span></>}</div>
    <header className="board-heading">
      <div><p className="board-eyebrow">줍줍각 커뮤니티</p><h1>커뮤니티</h1><p className="board-description">구매 고민부터 사용 후기까지, 다양한 이야기를 나눠보세요.</p></div>
      <div className="board-actions">
        <button disabled={busy} className="board-refresh" aria-label="게시글 목록 새로고침" title="새로고침" onClick={() => setRevision(v => v + 1)}><svg className={busy ? 'board-spinning' : ''} width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M6.1 7a7 7 0 0 1 11.5-1L20 9M4 15l2.4 3A7 7 0 0 0 17.9 17"/></svg></button>
        {!writing && <button disabled={busy} className="board-button board-primary" onClick={() => { if (!user) { onLogin(); return }; setEditingPost(null); setTitle(''); setContent(''); setKind('GENERAL'); setWriting(true); setArticle(null); setError('') }}><svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m16 3 5 5-12 12-6 1 1-6L16 3Z"/><path d="m13 6 5 5"/></svg>글쓰기</button>}
      </div>
    </header>
    {error && <p role="alert" className="board-error">{error}</p>}
    {busy && <p role="status" className="board-loading">불러오는 중입니다…</p>}
    {writing ? <form onSubmit={publish} className="board-panel board-editor">
      <div className="board-panel-heading"><h2>{editingPost ? '게시글 수정' : '게시글 작성'}</h2><p>함께 나누고 싶은 이야기를 들려주세요.</p></div>
      <div className="board-field"><label htmlFor="board-kind">게시판</label><select id="board-kind" className={input} value={kind} disabled={busy || Boolean(editingPost)} onChange={e => setKind(e.target.value)}><option value="GENERAL">일반 게시글</option>{(admin || editingPost?.kind === 'NOTICE') && <option value="NOTICE">공지사항</option>}</select></div>
      <div className="board-field"><label htmlFor="board-title">제목</label><div><input id="board-title" className={input} placeholder="제목을 입력해 주세요" required maxLength={120} value={title} disabled={busy} onChange={e => setTitle(e.target.value)}/><span className="board-counter">{title.length} / 120</span></div></div>
      <div className="board-field board-field-body"><label htmlFor="board-content">내용</label><div><CommunityEditor key={editingPost?.id ?? 'new'} initialFormat={editingPost?.format ?? 'plain'} value={content} onChange={setContent} disabled={busy}/></div></div>
      <p className="board-editor-note"><Icon name="shield" size={16}/>이메일·전화번호 등 개인정보를 게시하지 마세요. 작성자의 표시 이름은 공개됩니다.</p>
      <div className="board-editor-footer"><button type="button" disabled={busy} className={button} onClick={cancelPostEdit}>취소</button><button disabled={busy || !title.trim() || !hasCommunityContent(content)} className="board-button board-primary">{editingPost ? '수정 저장' : '게시글 등록'}</button></div>
    </form> : article ? <article className="board-panel">
      <div className="board-article-heading"><span className={`board-badge ${article.kind === 'NOTICE' ? 'board-notice' : ''}`}>{article.kind === 'NOTICE' ? '공지사항' : '일반 게시글'}</span><h2>{article.title}</h2><div className="board-article-meta"><span className="board-author"><span className="board-avatar"><Icon name="user" size={14}/></span>{article.authorName}</span><time dateTime={new Date(article.createdAt).toISOString()}>{date(article.createdAt)}</time></div></div>
      <div className="board-article-body">{article.format === 'markdown-v1' || article.format === 'richtext-v1' ? <CommunityContent format={article.format} content={article.content}/> : article.content}</div>
      <div className="board-article-actions"><button disabled={busy} className={button} onClick={() => setRevision(v => v + 1)}><Icon name="menu" size={15}/>목록으로</button><div className="board-article-controls">{!article.deleted && user?.uid === article.authorUid && <button disabled={busy} className={button} onClick={() => startPostEdit(article)}>수정</button>}{!article.deleted && user && (admin || article.authorUid === user.uid) && <button disabled={busy} className="board-button board-danger" onClick={() => { if (window.confirm('게시글을 삭제할까요?')) void run(async () => { await request(`/me/community/posts/${article.id}`, undefined, 'DELETE'); setRevision(v => v + 1) }) }}>삭제</button>}</div></div>
      {article.kind === 'NOTICE' ? <div className="board-comment-notice"><Icon name="shield" size={16}/>공지사항에는 댓글을 작성할 수 없습니다.</div> : !article.deleted && <section className="board-comments"><h3>댓글 <span>{comments.items.length}{comments.nextCursor ? '+' : ''}</span></h3>
        {comments.items.length === 0 && <div className="board-comments-empty">아직 댓글이 없습니다. 첫 이야기를 남겨보세요.</div>}
        {comments.items.map(comment => <div key={comment.id} className="board-comment"><span className="board-avatar"><Icon name="user" size={17}/></span><div className="board-comment-main"><div className="board-comment-meta"><strong>{comment.authorName}</strong><time dateTime={new Date(comment.createdAt).toISOString()}>{date(comment.createdAt)}</time>{comment.editedAt != null && <span className="board-edited" title={date(comment.editedAt)}>수정됨</span>}<div className="board-comment-controls">{user?.uid === comment.authorUid && editingComment?.id !== comment.id && <button disabled={busy} className="board-text-button" onClick={() => { setEditingComment({ id: comment.id, content: comment.content, version: comment.editedAt ?? comment.createdAt }); setError('') }}>수정</button>}{user && (admin || comment.authorUid === user.uid) && <button disabled={busy} className="board-text-button" onClick={() => { if (window.confirm('댓글을 삭제할까요?')) void run(async () => { await request(`/me/community/comments/${comment.id}`, undefined, 'DELETE'); setComments(previous => ({ ...previous, items: previous.items.filter(item => item.id !== comment.id) })); if(editingComment?.id === comment.id) setEditingComment(null) }) }}>삭제</button>}</div></div>
          {editingComment?.id === comment.id ? <form className="board-comment-edit" onSubmit={event => { event.preventDefault(); void run(saveComment) }}><label htmlFor={`comment-edit-${comment.id}`} className="sr-only">댓글 수정</label><textarea id={`comment-edit-${comment.id}`} className={input} autoFocus required maxLength={1000} disabled={busy} value={editingComment.content} onChange={event => { const content = event.target.value; setEditingComment(previous => previous ? { ...previous, content } : null) }}/><div><span>{editingComment.content.length} / 1,000</span><button type="button" className={button} disabled={busy} onClick={() => { setEditingComment(null); setError('') }}>취소</button><button className="board-button board-primary" disabled={busy || !editingComment.content.trim()}>저장</button></div></form> : <p>{comment.content}</p>}
        </div></div>)}
        {comments.nextCursor && <button disabled={busy} className="board-more" onClick={() => void run(async () => { const result = await request<Result<Page<Comment>>>(`/community/posts/${article.id}/comments?cursor=${encodeURIComponent(comments.nextCursor)}`); setComments({ items: [...comments.items, ...result.data.items], nextCursor: result.data.nextCursor }) })}>댓글 더 보기<Icon name="chevron" size={14}/></button>}
        {user ? <form className="board-reply" onSubmit={e => { e.preventDefault(); void run(async () => { await request(`/me/community/posts/${article.id}/comments`, undefined, 'POST', { content: reply }); await open(article.id) }) }}><label htmlFor="board-reply">댓글 작성</label><textarea id="board-reply" required maxLength={1000} disabled={busy} placeholder="서로를 존중하는 따뜻한 댓글을 남겨주세요." value={reply} onChange={e => setReply(e.target.value)}/><div className="board-reply-footer"><span>{reply.length} / 1,000</span><button disabled={busy || !reply.trim()} className="board-button board-primary">댓글 등록</button></div></form> : <div className="board-login-prompt"><p>로그인하고 이야기에 참여해 보세요.</p><button className={button} onClick={onLogin}>로그인</button></div>}
      </section>}
    </article> : <>
      <div className="board-tabs" role="group" aria-label="게시글 종류">{([{ value: 'ALL', label: '전체' }, { value: 'NOTICE', label: '공지사항' }, { value: 'GENERAL', label: '일반 게시글' }] as const).map(tab => <button key={tab.value} disabled={busy} aria-pressed={filter === tab.value} className={filter === tab.value ? 'is-active' : ''} onClick={() => setFilter(tab.value)}>{tab.label}</button>)}</div>
      <div className="board-list-summary"><span>{filter === 'ALL' ? '전체 게시글' : filter === 'NOTICE' ? '공지사항' : '일반 게시글'}</span></div>
      <div className="board-panel board-list"><table><caption className="sr-only">커뮤니티 게시글 목록</caption><thead><tr><th scope="col" className="board-kind-column">구분</th><th scope="col">제목</th><th scope="col" className="board-author-column">작성자</th><th scope="col" className="board-date-column">작성일</th></tr></thead><tbody>
        {visiblePosts.map(post => <tr key={post.id} className={`${post.kind === 'NOTICE' ? 'board-notice-row' : ''} ${filter === 'ALL' && pinnedIds.has(post.id) ? 'board-pinned-row' : ''}`}><td className="board-kind-column"><span className={`board-badge ${post.kind === 'NOTICE' ? 'board-notice' : ''}`}>{post.kind === 'NOTICE' ? '공지' : '일반'}</span></td><td className="board-title-cell"><button disabled={busy} onClick={() => void run(() => open(post.id))}>{filter === 'ALL' && pinnedIds.has(post.id) && <span className="board-pin" aria-label="상단 고정 공지"><svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="m16 3 5 5-3 1-4 5v3l-2 2-3-4-6 6-1-1 6-6-4-3 2-2h3l5-4 2-3Z"/></svg></span>}<span className="board-title-text">{post.title}</span>{post.kind === 'NOTICE' && <span className="board-mobile-badge">공지</span>}</button><div className="board-mobile-meta"><span>{post.authorName}</span><time dateTime={new Date(post.createdAt).toISOString()} title={date(post.createdAt)}>{shortDate(post.createdAt, now)}</time></div></td><td className="board-author-column">{post.authorName}</td><td className="board-date-column"><time dateTime={new Date(post.createdAt).toISOString()} title={date(post.createdAt)}>{shortDate(post.createdAt, now)}</time></td></tr>)}
        {!busy && visiblePosts.length === 0 && <tr><td colSpan={4}><div className="board-empty"><span className="board-empty-icon"><Icon name="menu" size={26}/></span><strong>{posts.items.length === 0 ? '아직 등록된 게시글이 없습니다' : '불러온 목록에 해당 게시글이 없습니다'}</strong><p>{posts.items.length === 0 ? '첫 번째 이야기를 커뮤니티에 남겨보세요.' : '다른 게시판을 선택하거나 게시글을 더 불러와 보세요.'}</p></div></td></tr>}
      </tbody></table></div>
      <div className="board-list-footer"><p>이야기를 나누고, 더 나은 구매를 함께하세요.</p>{posts.nextCursor && <button disabled={busy} className={button} onClick={() => void run(async () => { const result = await request<Result<PostPage>>(`/community/posts?cursor=${encodeURIComponent(posts.nextCursor)}`); setPosts(previous => ({ items: [...new Map([...previous.items, ...result.data.items].map(post => [post.id, post])).values()], pinnedItems: result.data.pinnedItems ?? previous.pinnedItems, nextCursor: result.data.nextCursor })) })}>게시글 더 보기<Icon name="chevron" size={14}/></button>}</div>
    </>}
  </section>
}
