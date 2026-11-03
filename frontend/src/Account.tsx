import Loading from './Loading'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import {
  signInWithEmailAndPassword,
  signOut,
  sendPasswordResetEmail,
  reload,
  getIdToken,
  reauthenticateWithCredential,
  EmailAuthProvider,
  type User,
} from 'firebase/auth'
import AccountDetails from './AccountDetails'
import AccountActivity from './AccountActivity'
import type { ProductProvider } from './productSource'
import './accountPage.css'
import SignupForm from './SignupForm'
import { auth, configurationError } from './firebase'
import { useSession } from './session'
import { Icon, BrandMark } from './Icons'
import {
  ApiRequestError,
  request,
  type Listing,
  type Product,
  type Single,
  type WishlistEntry,
  type WishlistState,
} from './api'

const panel =
  'rounded-3xl border border-[var(--line)] bg-[var(--surface)] p-6 shadow-[var(--shadow)]'
function message(error: unknown) {
  if (error && typeof error === 'object' && 'code' in error) {
    switch (error.code) {
      case 'auth/invalid-email':
        return '이메일 형식을 확인해 주세요.'
      case 'auth/weak-password':
        return '비밀번호가 계정의 비밀번호 정책을 충족하지 않습니다. 더 긴 비밀번호로 다시 시도해 주세요.'
      case 'auth/email-already-in-use':
        return '이미 가입된 이메일입니다. 로그인해 주세요.'
      case 'auth/invalid-credential':
      case 'auth/user-not-found':
      case 'auth/wrong-password':
        return '이메일 또는 비밀번호를 확인해 주세요.'
      case 'auth/network-request-failed':
        return '로그인 서비스에 연결하지 못했습니다. 네트워크와 Firebase 설정을 확인해 주세요.'
      case 'auth/too-many-requests':
        return '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
      case 'auth/user-token-expired':
      case 'auth/user-disabled':
        return '로그인이 만료되었거나 계정이 비활성화되었습니다. 다시 로그인해 주세요.'
      default:
        return '로그인 처리에 실패했습니다. 다시 시도해 주세요.'
    }
  }
  return error instanceof Error ? error.message : '요청에 실패했습니다.'
}

export function AccountPanel({
  onClose,
  onLogout = onClose,
  presentation = 'dialog',
  onBusyChange,
  onWishlist,
  onProduct,
}: {
  onClose: () => void
  onLogout?: () => void
  presentation?: 'dialog' | 'page'
  onBusyChange?: (busy: boolean) => void
  onWishlist?: () => void
  onProduct?: (id: string, provider?: ProductProvider) => void
}) {
  const { accountUser: user, ready } = useSession()
  const [register, setRegister] = useState(false)
  const [signupPending, setSignupPending] = useState(false)
  useEffect(() => {
    setSignupPending(Boolean(user && !user.emailVerified))
  }, [user?.uid])
  const [resetPassword, setResetPassword] = useState(false)
  const [notice, setNotice] = useState('')
  const [memberName, setMemberName] = useState<string | null>(null)
  useEffect(() => {
    setMemberName(null)
  }, [user?.uid])
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const container = useRef<HTMLDivElement>(null)
  const page = presentation === 'page'
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    container.current?.focus()
    return () => {
      if (previous?.isConnected) previous.focus()
    }
  }, [])
  useEffect(() => {
    onBusyChange?.(busy)
  }, [busy, onBusyChange])
  useEffect(() => () => onBusyChange?.(false), [onBusyChange])
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!auth || busy) return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      if (resetPassword) {
        try {
          await sendPasswordResetEmail(auth, email.trim())
        } catch (error) {
          if (
            !(
              error &&
              typeof error === 'object' &&
              'code' in error &&
              error.code === 'auth/user-not-found'
            )
          )
            throw error
        }
        setNotice(
          '입력하신 이메일로 가입된 계정이 있다면 재설정 메일을 보냈습니다. 받은편지함과 스팸함을 확인해 주세요.',
        )
        return
      }
      const credential = await signInWithEmailAndPassword(auth, email.trim(), password)
      setPassword('')
      await reload(credential.user)
      await getIdToken(credential.user, true)
      if (!credential.user.emailVerified) {
        setSignupPending(true)
        setNotice('가입 화면에서 이메일 인증을 완료해 주세요.')
        return
      }
      setPassword('')
      onClose()
    } catch (error) {
      setError(message(error))
    } finally {
      setBusy(false)
    }
  }
  const name = memberName || '회원'
  const awaitingVerification = Boolean(user && (!user.emailVerified || signupPending))
  const showSignup = register || awaitingVerification
  const memberView = Boolean(ready && user && !showSignup)
  return (
    <div
      role={page ? 'main' : undefined}
      className={`${page ? 'mx-auto max-w-[1180px] px-5 py-10 lg:px-8 lg:py-16' : 'fixed inset-0 z-[70] grid place-items-center overflow-y-auto bg-black/50 p-4'} ${memberView ? 'account-dashboard' : ''}`}
      onClick={(event) => {
        if (!page && event.target === event.currentTarget && !busy) onClose()
      }}
    >
      <div
        ref={container}
        tabIndex={-1}
        role={page ? undefined : 'dialog'}
        aria-modal={page ? undefined : true}
        aria-labelledby="account-title"
        onKeyDown={(event) => {
          if (page) return
          if (event.key === 'Escape' && !busy) onClose()
          if (event.key === 'Tab') {
            const nodes = Array.from(
              container.current?.querySelectorAll<HTMLElement>(
                'button:not(:disabled), input:not(:disabled), summary',
              ) ?? [],
            ).filter((node) => node.getClientRects().length > 0)
            if (!nodes?.length) return
            const first = nodes[0],
              last = nodes[nodes.length - 1]
            if (
              event.shiftKey &&
              (document.activeElement === first || document.activeElement === container.current)
            ) {
              event.preventDefault()
              last.focus()
            } else if (
              !event.shiftKey &&
              (document.activeElement === last || document.activeElement === container.current)
            ) {
              event.preventDefault()
              first.focus()
            }
          }
        }}
        className={`grid w-full max-w-[1180px] overflow-hidden rounded-3xl border border-[var(--line)] bg-[var(--surface)] shadow-[var(--shadow-lg)] outline-none lg:grid-cols-[1.05fr_.95fr] ${memberView ? 'account-dashboard-shell' : ''}`}
      >
        <section className="entry-hero relative overflow-hidden p-8 text-white sm:p-12">
          <div className="relative z-10">
            <span className="mb-8 grid h-14 w-14 place-items-center rounded-2xl bg-white/10">
              <BrandMark size={34} />
            </span>
            <p className="text-sm font-bold text-emerald-200">줍줍각 멤버</p>
            <h2 className="mt-3 text-3xl font-black tracking-[-0.045em] sm:text-4xl">
              나만을 위한 가격 비교를
              <br />
              시작해 보세요
            </h2>
            <p className="mt-5 max-w-md text-sm leading-6 text-white/65">
              관심 있는 상품의 판매 조건을 비교하고,
              <br className="hidden lg:block" /> 저장한 가격 관측과 AI 설명을 이어서 확인해 보세요.
            </p>
            {!memberView && (
              <div className="mt-10 grid grid-cols-3 gap-3">
                {['상품 비교', '가격 이력', 'AI 설명'].map((benefit) => (
                  <div
                    key={benefit}
                    className="rounded-2xl border border-white/10 bg-white/5 p-3 text-center text-xs font-bold"
                  >
                    {benefit}
                  </div>
                ))}
              </div>
            )}
          </div>
          {memberView && (
            <>
              <div className="account-brand-art" aria-hidden="true">
                <span className="account-brand-halo" />
                <span className="account-float account-float-check">
                  <span>
                    <Icon name="check" size={22} />
                  </span>
                  <i />
                  <i />
                </span>
                <img src={`${import.meta.env.BASE_URL}brand/account-joopjoop.png`} alt="" />
                <span className="account-float account-float-chart">
                  <Icon name="trend" size={39} />
                </span>
                <span className="account-brand-spark">✦</span>
                <span className="account-brand-spark account-brand-spark-small">✦</span>
                <p>
                  좋은 가격을 찾는 순간까지,
                  <br />
                  줍줍이가 함께할게요.
                </p>
              </div>
              <div className="account-brand-benefits">
                {[
                  { icon: 'search' as const, title: '상품 비교', detail: '판매 조건을 한눈에' },
                  { icon: 'trend' as const, title: '가격 이력', detail: '가격의 흐름을 차근차근' },
                  { icon: 'sparkles' as const, title: 'AI 설명', detail: '확인된 정보로 신중하게' },
                ].map((item) => (
                  <div key={item.title}>
                    <span>
                      <Icon name={item.icon} size={23} />
                    </span>
                    <p>
                      <strong>{item.title}</strong>
                      <small>{item.detail}</small>
                    </p>
                  </div>
                ))}
              </div>
              <p className="account-brand-foot">검색은 가볍게, 비교는 꼼꼼하게.</p>
            </>
          )}
        </section>
        <section className={`p-8 sm:p-12 ${memberView ? 'account-dashboard-content' : ''}`}>
          <button
            disabled={busy}
            onClick={onClose}
            className="mb-10 flex items-center gap-1 text-xs font-bold text-[var(--muted)] transition hover:text-[var(--text)] disabled:opacity-50"
          >
            <span className="rotate-180">
              <Icon name="arrow" size={14} />
            </span>
            {page ? '상품 둘러보기' : '닫기'}
          </button>
          {memberView && <p className="account-page-eyebrow">MY JOOPJOOP</p>}
          <h1 id="account-title" className="text-2xl font-black tracking-tight">
            {showSignup
              ? '회원가입'
              : user
                ? '내 정보'
                : resetPassword
                  ? '비밀번호 찾기'
                  : '로그인'}
          </h1>
          <p className="mt-2 text-sm leading-6 text-[var(--muted)]">
            {showSignup
              ? '가입 정보 입력과 이메일 인증을 이 화면에서 완료해 주세요.'
              : user
                ? '나의 계정과 저장한 상품을 한곳에서 관리하세요.'
                : '줍줍각 계정으로 나의 가격 비교를 이어가세요.'}
          </p>
          <div className="mt-8">
            {ready && !user && !resetPassword && (
              <div
                role="group"
                aria-label="인증 방식 선택"
                className="mb-7 grid grid-cols-2 rounded-2xl bg-[var(--surface-2)] p-1"
              >
                {[false, true].map((signup) => (
                  <button
                    key={String(signup)}
                    type="button"
                    disabled={busy}
                    aria-pressed={register === signup}
                    onClick={() => {
                      setRegister(signup)
                      setPassword('')
                      setError('')
                      setNotice('')
                    }}
                    className={`rounded-xl px-4 py-2.5 text-xs font-black transition disabled:opacity-50 ${register === signup ? 'bg-[var(--surface)] text-[var(--text)] shadow-sm' : 'text-[var(--muted)]'}`}
                  >
                    {signup ? '회원가입' : '로그인'}
                  </button>
                ))}
              </div>
            )}
            {!ready ? (
              <p role="status" className="py-8 text-center text-sm text-[var(--muted)]">
                로그인 확인 중…
              </p>
            ) : showSignup ? (
              <>
                <SignupForm
                  user={awaitingVerification ? user : null}
                  busy={busy}
                  setBusy={setBusy}
                  onComplete={() => {
                    setRegister(false)
                    setSignupPending(false)
                    setNotice('이메일 인증과 회원가입이 완료되었습니다.')
                    setError('')
                  }}
                />
                {user && (
                  <>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={async () => {
                        if (!auth) return
                        setBusy(true)
                        try {
                          await signOut(auth)
                          setSignupPending(false)
                          setRegister(false)
                          setNotice('')
                          setError('')
                        } catch (error) {
                          setError(message(error))
                        } finally {
                          setBusy(false)
                        }
                      }}
                      className="mt-4 text-xs font-bold text-[var(--muted)] disabled:opacity-40"
                    >
                      가입 대기에서 나가기 · 로그아웃
                    </button>
                    <details className="mt-4 rounded-2xl border border-[var(--line)] p-4">
                      <summary className="cursor-pointer text-xs text-[var(--muted)]">
                        가입 대기 계정 삭제
                      </summary>
                      <DeleteAccount user={user} busy={busy} setBusy={setBusy} onClose={onLogout} />
                    </details>
                  </>
                )}
              </>
            ) : user ? (
              <div>
                <div className="account-profile-card">
                  <span className="account-profile-avatar">{Array.from(name)[0]}</span>
                  <div className="account-profile-copy">
                    <p className="account-profile-name">
                      {name}
                      <span>님</span>
                    </p>
                    <p className="account-profile-email">{user.email}</p>
                    <p className="account-profile-meta">
                      {user.metadata.creationTime
                        ? `가입일 ${new Date(user.metadata.creationTime).toLocaleDateString('ko-KR')}`
                        : '가입일 확인되지 않음'}
                    </p>
                  </div>
                  <span className="account-verification-badge">
                    <Icon name="check" size={12} />
                    {user.emailVerified ? '이메일 인증 완료' : '이메일 인증 필요'}
                  </span>
                </div>
                <AccountActivity busy={busy} onWishlist={onWishlist} onProduct={onProduct} />
                <AccountDetails
                  key={user.uid}
                  user={user}
                  busy={busy}
                  setBusy={setBusy}
                  onNameChange={setMemberName}
                />
                <div className="account-management-footer">
                  <p>
                    <Icon name="shield" size={13} />
                    계정 관리
                  </p>
                  <button
                    disabled={busy}
                    type="button"
                    onClick={async () => {
                      if (!auth) return
                      setBusy(true)
                      setError('')
                      try {
                        await signOut(auth)
                        onLogout()
                      } catch (error) {
                        setError(message(error))
                      } finally {
                        setBusy(false)
                      }
                    }}
                  >
                    {busy ? '처리 중…' : '로그아웃'}
                  </button>
                </div>
                <details className="account-delete">
                  <summary>회원 탈퇴 · 계정 삭제 안내</summary>
                  <DeleteAccount user={user} busy={busy} setBusy={setBusy} onClose={onLogout} />
                </details>
              </div>
            ) : (
              <>
                <form onSubmit={submit} className="space-y-3">
                  <label className="block">
                    <span className="sr-only">이메일</span>
                    <input
                      name="email"
                      type="email"
                      autoComplete="email"
                      required
                      placeholder="이메일"
                      value={email}
                      onChange={(event) => setEmail(event.target.value)}
                      disabled={busy || !auth}
                      className="w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] px-4 py-3.5 text-sm outline-none focus:border-[var(--primary)] disabled:opacity-60"
                    />
                  </label>
                  {!resetPassword && (
                    <label className="block">
                      <span className="sr-only">비밀번호</span>
                      <input
                        name="password"
                        type="password"
                        minLength={6}
                        autoComplete="current-password"
                        required
                        placeholder="비밀번호"
                        value={password}
                        onChange={(event) => setPassword(event.target.value)}
                        disabled={busy || !auth}
                        className="w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] px-4 py-3.5 text-sm outline-none focus:border-[var(--primary)] disabled:opacity-60"
                      />
                    </label>
                  )}
                  <button
                    disabled={busy || !auth}
                    className="w-full rounded-2xl bg-[var(--primary)] px-5 py-3.5 text-sm font-black text-white transition hover:brightness-110 disabled:opacity-60"
                  >
                    {busy ? '처리 중…' : resetPassword ? '재설정 메일 받기' : '줍줍각 로그인'}
                  </button>
                </form>
                {!register && (
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() => {
                      setResetPassword((previous) => !previous)
                      setPassword('')
                      setError('')
                      setNotice('')
                    }}
                    className="mt-4 text-xs font-bold text-[var(--primary)] disabled:opacity-50"
                  >
                    {resetPassword ? '로그인으로 돌아가기' : '비밀번호를 잊으셨나요?'}
                  </button>
                )}
              </>
            )}
          </div>
          {notice && (
            <p role="status" className="mt-4 text-center text-xs leading-6 text-[var(--mint)]">
              {notice}
            </p>
          )}
          {(error || configurationError) && (
            <p role="alert" className="mt-4 text-center text-xs font-bold leading-6 text-rose-500">
              {error || configurationError}
            </p>
          )}
          <p className="mt-6 text-center text-[10px] leading-5 text-[var(--subtle)]">
            {import.meta.env.VITE_FIREBASE_MODE === 'emulator'
              ? '로컬 개발용 계정입니다.'
              : '상품 검색과 가격 비교는 로그인 없이도 이용할 수 있습니다.'}
          </p>
        </section>
      </div>
    </div>
  )
}
function DeleteAccount({
  user,
  busy,
  setBusy,
  onClose,
}: {
  user: User
  busy: boolean
  setBusy: (value: boolean) => void
  onClose: () => void
}) {
  const [password, setPassword] = useState('')
  const [confirmed, setConfirmed] = useState(false)
  const [error, setError] = useState('')
  async function remove(event: FormEvent) {
    event.preventDefault()
    if (!auth || !user.email || busy || !confirmed) return
    setBusy(true)
    setError('')
    try {
      await reauthenticateWithCredential(user, EmailAuthProvider.credential(user.email, password))
      setPassword('')
      for (let step = 0; step < 100; step++) {
        if (auth.currentUser?.uid !== user.uid)
          throw new Error('계정이 변경되었습니다. 다시 확인해 주세요.')
        const result = await request<Single<{ state: 'DELETING' | 'DELETED' }>>(
          '/me/account',
          undefined,
          'DELETE',
          { confirm: true },
        )
        if (result.data.state === 'DELETED') {
          await signOut(auth)
          onClose()
          return
        }
      }
      throw new Error(
        '삭제할 데이터가 많습니다. 비밀번호와 삭제 확인을 다시 입력하여 이어서 진행해 주세요.',
      )
    } catch (error) {
      setError(
        `${message(error)} 삭제가 시작되었다면 개인 데이터 저장은 중단됩니다. 다시 인증하여 이어서 진행하세요. 마지막 응답을 받지 못한 경우 로그아웃 후 계정 상태를 확인하세요.`,
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <form onSubmit={remove} className="mt-6 space-y-3 border-t border-[var(--line)] pt-5">
      <h3 className="text-sm font-bold">계정 삭제</h3>
      <p className="text-xs leading-5 text-[var(--muted)]">
        찜, 목표가 알림, 알림함, 최근 비교 기록, 구매 비서 대화와 로그인 계정을 삭제합니다. 시작 후
        취소하거나 복구할 수 없습니다.
      </p>
      <label className="block text-xs font-bold">
        현재 비밀번호
        <input
          type="password"
          autoComplete="current-password"
          required
          value={password}
          disabled={busy}
          onChange={(event) => setPassword(event.target.value)}
          className="mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-3 text-sm"
        />
      </label>
      <label className="flex items-start gap-2 text-xs">
        <input
          type="checkbox"
          checked={confirmed}
          disabled={busy}
          onChange={(event) => setConfirmed(event.target.checked)}
        />
        개인 데이터와 계정 삭제에 동의합니다.
      </label>
      <button
        disabled={busy || !confirmed || !password}
        className="rounded-xl border border-rose-500 px-4 py-2 text-xs font-bold text-rose-500 disabled:opacity-50"
      >
        {busy ? '처리 중…' : '계정과 데이터 삭제'}
      </button>
      {error && (
        <p role="alert" className="text-xs leading-5 text-rose-500">
          {error}
        </p>
      )}
    </form>
  )
}

export function WishlistButton({ productId, onLogin }: { productId: string; onLogin: () => void }) {
  const { user, ready } = useSession()
  const [saved, setSaved] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [loaded, setLoaded] = useState(false)
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setSaved(false)
    setLoaded(false)
    setError('')
    if (user)
      request<Single<WishlistState>>(`/me/wishlist/${encodeURIComponent(productId)}`, abort.signal)
        .then((result) => {
          if (!abort.signal.aborted) {
            setSaved(result.data.saved)
            setLoaded(true)
          }
        })
        .catch((error) => {
          if (!abort.signal.aborted) setError(message(error))
        })
    return () => abort.abort()
  }, [user?.uid, productId, retry])
  async function toggle() {
    if (!user) {
      onLogin()
      return
    }
    if (!loaded) {
      setRetry((value) => value + 1)
      return
    }
    const uid = user.uid
    setBusy(true)
    setError('')
    try {
      const result = await request<Single<WishlistState>>(
        `/me/wishlist/${encodeURIComponent(productId)}`,
        undefined,
        saved ? 'DELETE' : 'PUT',
      )
      if (auth?.currentUser?.uid === uid) setSaved(result.data.saved)
    } catch (error) {
      if (auth?.currentUser?.uid === uid) setError(message(error))
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="mt-4">
      <button
        onClick={() => void toggle()}
        disabled={!ready || busy || (!!user && !loaded && !error)}
        aria-pressed={saved}
        className="flex items-center gap-2 rounded-xl border border-[var(--line)] px-4 py-2 text-xs font-bold text-[var(--primary)] disabled:opacity-50"
      >
        <Icon name={saved ? 'check' : 'heart'} size={16} />
        {busy ? '저장 중…' : error && !loaded ? '찜 상태 다시 확인' : saved ? '찜 해제' : '찜하기'}
      </button>
      {error && (
        <p role="alert" className="mt-2 text-xs text-rose-500">
          {error}
        </p>
      )}
    </div>
  )
}

type WishlistItem = WishlistEntry & { product: Product | null }
export function WishlistPage({
  onLogin,
  onProduct,
}: {
  onLogin: () => void
  onProduct: (id: string) => void
}) {
  const { user, ready } = useSession()
  const [items, setItems] = useState<WishlistItem[]>([])
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [cursor, setCursor] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [retry, setRetry] = useState(0)
  const [removing, setRemoving] = useState<string | null>(null)
  useEffect(() => {
    setItems([])
    setCursor('')
    setNextCursor(null)
  }, [user?.uid])
  useEffect(() => {
    const abort = new AbortController()
    if (!ready) return () => abort.abort()
    if (!user) {
      setLoading(false)
      return () => abort.abort()
    }
    setLoading(true)
    setError('')
    request<Listing<WishlistEntry>>(
      `/me/wishlist?${new URLSearchParams({ cursor, limit: '20' })}`,
      abort.signal,
    )
      .then(async (page) => {
        const resolved = await Promise.all(
          page.items.map(async (entry) => {
            try {
              const product = await request<Single<Product>>(
                `/products/${encodeURIComponent(entry.productId)}`,
                abort.signal,
              )
              return { ...entry, product: product.data }
            } catch (error) {
              if (error instanceof ApiRequestError && error.status === 404)
                return { ...entry, product: null }
              throw error
            }
          }),
        )
        if (!abort.signal.aborted) {
          setItems((previous) =>
            cursor
              ? [
                  ...previous.filter(
                    (item) => !resolved.some((row) => row.productId === item.productId),
                  ),
                  ...resolved,
                ]
              : resolved,
          )
          setNextCursor(page.nextCursor)
        }
      })
      .catch((error) => {
        if (!abort.signal.aborted) setError(message(error))
      })
      .finally(() => {
        if (!abort.signal.aborted) setLoading(false)
      })
    return () => abort.abort()
  }, [user?.uid, ready, cursor, retry])
  async function remove(id: string) {
    const uid = user?.uid
    setRemoving(id)
    setError('')
    try {
      await request(`/me/wishlist/${encodeURIComponent(id)}`, undefined, 'DELETE')
      if (uid === auth?.currentUser?.uid)
        setItems((items) => items.filter((item) => item.productId !== id))
    } catch (error) {
      if (uid === auth?.currentUser?.uid) setError(message(error))
    } finally {
      setRemoving(null)
    }
  }
  return (
    <section className={panel}>
      <p className="flex items-center gap-2 text-sm font-extrabold text-[var(--mint)]">
        <Icon name="heart" size={16} />
        MY WISHLIST
      </p>
      <h1 className="mt-2 text-2xl font-black">찜한 상품</h1>
      {!ready ? (
        <p role="status" className="mt-5">
          로그인 확인 중…
        </p>
      ) : !user ? (
        <div className="mt-5">
          <p className="text-sm text-[var(--muted)]">로그인하면 저장한 상품을 확인할 수 있어요.</p>
          <button
            onClick={onLogin}
            className="mt-4 rounded-xl bg-[var(--primary)] px-5 py-3 text-sm font-bold text-white"
          >
            로그인
          </button>
        </div>
      ) : (
        <>
          {items.map((item) => (
            <div
              key={item.productId}
              className="mt-4 flex items-center justify-between gap-3 rounded-2xl border border-[var(--line)] p-4"
            >
              <button
                disabled={!item.product}
                onClick={() => onProduct(item.productId)}
                className="min-w-0 text-left"
              >
                <p className="truncate text-sm font-bold">
                  {item.product?.title ?? `판매 종료 상품 (${item.productId})`}
                </p>
                <p className="mt-1 text-xs text-[var(--muted)]">
                  {item.product?.priceStale
                    ? '가격 갱신 필요'
                    : item.product?.currentPrice != null
                      ? `${item.product.currentPrice.toLocaleString('ko-KR')}원`
                      : '확정가 없음'}
                </p>
              </button>
              <button
                disabled={!!removing}
                onClick={() => void remove(item.productId)}
                aria-label={`${item.product?.title ?? item.productId} 찜 해제`}
                className="shrink-0 text-xs font-bold text-[var(--primary)]"
              >
                {removing === item.productId ? '해제 중…' : '찜 해제'}
              </button>
            </div>
          ))}
          {loading ? (
            <Loading label="찜 목록을 불러오는 중입니다" />
          ) : !error && !items.length ? (
            <p className="mt-5 text-sm text-[var(--muted)]">아직 찜한 상품이 없습니다.</p>
          ) : null}
          {error && (
            <div role="alert" className="mt-5 text-sm text-rose-500">
              {error}
              <button onClick={() => setRetry((value) => value + 1)} className="ml-3 underline">
                다시 시도
              </button>
            </div>
          )}
          {!loading && !error && nextCursor && (
            <button
              onClick={() => setCursor(nextCursor)}
              className="mt-5 text-sm font-bold text-[var(--primary)]"
            >
              더 보기
            </button>
          )}
        </>
      )}
    </section>
  )
}
