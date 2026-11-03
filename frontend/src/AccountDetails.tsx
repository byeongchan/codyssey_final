import { useEffect, useState } from 'react'
import {
  getIdToken,
  reload,
  sendEmailVerification,
  sendPasswordResetEmail,
  type User,
} from 'firebase/auth'
import { auth } from './firebase'
import { request, type Single } from './api'
import { displayNameError, useNamePolicy, type MemberProfile } from './memberProfile'
import Loading from './Loading'
import { Icon } from './Icons'

export default function AccountDetails({
  user,
  busy,
  setBusy,
  onNameChange,
}: {
  user: User
  busy: boolean
  setBusy: (value: boolean) => void
  onNameChange: (name: string | null) => void
}) {
  const [name, setName] = useState('')
  const [view, setView] = useState<'profile' | 'security'>('profile')
  const [targetUid, setTargetUid] = useState('')
  const [targetRole, setTargetRole] = useState<'ADMIN' | 'MEMBER'>('ADMIN')
  const [profile, setProfile] = useState<MemberProfile | null>(null)
  const [profileLoading, setProfileLoading] = useState(true)
  const [profileRevision, setProfileRevision] = useState(0)
  const namePolicy = useNamePolicy()
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  const [cooldown, setCooldown] = useState(0)
  useEffect(() => {
    const abort = new AbortController()
    setProfile(null)
    setProfileLoading(true)
    void request<Single<MemberProfile>>('/me/profile', abort.signal)
      .then((result) => {
        if (!abort.signal.aborted) {
          setProfile(result.data)
          setName(result.data.displayName ?? '')
          onNameChange(result.data.displayName)
        }
      })
      .catch((error) => {
        if (!abort.signal.aborted)
          setError(error instanceof Error ? error.message : '내 정보를 불러오지 못했습니다.')
      })
      .finally(() => {
        if (!abort.signal.aborted) setProfileLoading(false)
      })
    return () => abort.abort()
  }, [user.uid, profileRevision, onNameChange])
  const nameError = displayNameError(name, namePolicy.policy)
  const nameLocked = Boolean(
    profile?.nextNameChangeAt && Date.parse(profile.nextNameChangeAt) > Date.now(),
  )
  useEffect(() => {
    if (!cooldown) return
    const timer = setTimeout(() => setCooldown((previous) => Math.max(0, previous - 1)), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])
  async function action(kind: 'verify' | 'check' | 'reset' | 'name') {
    if (
      !auth ||
      busy ||
      auth.currentUser?.uid !== user.uid ||
      ((kind === 'verify' || kind === 'reset') && cooldown > 0)
    )
      return
    setBusy(true)
    setError('')
    setNotice('')
    try {
      if (kind === 'verify') {
        await sendEmailVerification(user)
        setCooldown(60)
        setNotice(
          '인증 메일을 보냈습니다. 받은편지함과 스팸함을 확인한 뒤 인증 완료 확인을 눌러 주세요.',
        )
      } else if (kind === 'check') {
        await reload(user)
        if (auth.currentUser?.uid !== user.uid) return
        await getIdToken(user, true)
        setNotice(
          user.emailVerified
            ? '이메일 인증이 완료되었습니다. 회원 기능을 이용할 수 있습니다.'
            : '아직 인증이 확인되지 않았습니다. 메일의 인증 링크를 누른 뒤 다시 확인해 주세요.',
        )
      } else if (kind === 'reset') {
        if (!user.email) throw new Error('재설정할 이메일을 확인해 주세요.')
        await sendPasswordResetEmail(auth, user.email)
        setCooldown(60)
        setNotice('비밀번호 재설정 메일을 보냈습니다. 메일의 링크에서 새 비밀번호를 설정해 주세요.')
      } else {
        if (!user.emailVerified) throw new Error('이메일 인증을 먼저 완료해 주세요.')
        if (nameError) throw new Error(nameError)
        const result = await request<Single<MemberProfile>>('/me/profile', undefined, 'PATCH', {
          displayName: name.normalize('NFKC').trim(),
        })
        setProfile(result.data)
        setName(result.data.displayName ?? '')
        onNameChange(result.data.displayName)
        setNotice('표시 이름을 저장했습니다.')
      }
    } catch (error) {
      const code = error && typeof error === 'object' && 'code' in error ? error.code : ''
      setError(
        code === 'auth/too-many-requests'
          ? '요청이 많습니다. 잠시 후 다시 시도해 주세요.'
          : code === 'auth/network-request-failed'
            ? '인증 서비스 연결을 확인하고 다시 시도해 주세요.'
            : error instanceof Error && !code
              ? error.message
              : '계정 정보를 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      )
    } finally {
      setBusy(false)
    }
  }
  const button =
    'account-small-button rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold text-[var(--primary)] disabled:opacity-40'
  return (
    <section className="account-details" aria-label="내 정보 관리">
      <div className="account-detail-tabs" role="group" aria-label="내 정보 관리 항목">
        <button
          type="button"
          disabled={busy}
          aria-pressed={view === 'profile'}
          onClick={() => setView('profile')}
        >
          계정 정보
        </button>
        <button
          type="button"
          disabled={busy}
          aria-pressed={view === 'security'}
          onClick={() => setView('security')}
        >
          보안 · 관리
        </button>
      </div>
      <div className="account-data-card" hidden={view !== 'security'}>
        <h2 className="account-section-title">
          <Icon name="shield" size={16} />
          로그인 · 보안
        </h2>
        <p
          className={`mt-2 text-xs font-bold ${user.emailVerified ? 'text-[var(--mint)]' : 'text-amber-600'}`}
        >
          {user.emailVerified ? '이메일 인증 완료' : '이메일 인증이 필요합니다'}
        </p>
        {!user.emailVerified && (
          <>
            <p className="mt-2 text-xs leading-6 text-[var(--muted)]">
              메일의 인증 링크를 누른 뒤 아래에서 인증 상태를 확인해 주세요. 인증 전에도 상품 검색과
              가격 비교는 이용할 수 있습니다.
            </p>
            <div className="mt-3 flex flex-wrap gap-2">
              <button
                type="button"
                disabled={busy || cooldown > 0}
                onClick={() => void action('verify')}
                className={button}
              >
                {cooldown ? `${cooldown}초 후 재발송` : '인증 메일 재발송'}
              </button>
              <button
                type="button"
                disabled={busy}
                onClick={() => void action('check')}
                className={button}
              >
                인증 완료 확인
              </button>
            </div>
          </>
        )}
      </div>
      {profileLoading && <Loading label="내 정보를 불러오는 중입니다" />}
      {!profileLoading && !profile && (
        <button
          type="button"
          disabled={busy}
          onClick={() => setProfileRevision((value) => value + 1)}
          className={button}
        >
          내 정보 다시 불러오기
        </button>
      )}
      {profile && (
        <div className="account-data-card account-profile-settings" hidden={view !== 'profile'}>
          <h2 className="account-section-title">
            <Icon name="user" size={16} />
            기본 정보
          </h2>
          <dl className="account-information">
            <div>
              <dt>이메일</dt>
              <dd>{user.email}</dd>
            </div>
            <div>
              <dt>가입일</dt>
              <dd>
                {user.metadata.creationTime
                  ? new Date(user.metadata.creationTime).toLocaleDateString('ko-KR')
                  : '확인되지 않음'}
              </dd>
            </div>
            <div>
              <dt>최근 로그인</dt>
              <dd>
                {user.metadata.lastSignInTime
                  ? new Date(user.metadata.lastSignInTime).toLocaleString('ko-KR')
                  : '확인되지 않음'}
              </dd>
            </div>
            <div>
              <dt>로그인 방식</dt>
              <dd>
                {user.providerData.some((provider) => provider.providerId === 'password')
                  ? '이메일 로그인'
                  : '연결된 인증 계정'}
              </dd>
            </div>
          </dl>
          <p className="text-xs font-bold text-[var(--primary)]">
            계정 권한: {profile.role === 'ADMIN' ? '관리자' : '일반 회원'}
          </p>
          <form
            onSubmit={(event) => {
              event.preventDefault()
              void action('name')
            }}
            className="space-y-2"
          >
            <label className="block text-xs font-bold">
              표시 이름
              <input
                autoComplete="nickname"
                minLength={2}
                maxLength={24}
                required
                value={name}
                onChange={(event) => setName(event.target.value)}
                disabled={busy}
                className="mt-2 w-full rounded-xl border border-[var(--line)] bg-[var(--surface-2)] p-3 text-sm"
              />
            </label>
            <p className="text-[11px] leading-5 text-[var(--muted)]">
              등록·변경 후 7일에 한 번 변경할 수 있습니다.
              {nameLocked && profile.nextNameChangeAt
                ? ` 다음 변경 가능: ${new Date(profile.nextNameChangeAt).toLocaleString('ko-KR')}`
                : ''}
            </p>
            {name && namePolicy.policy && nameError && (
              <p className="text-xs text-rose-500">{nameError}</p>
            )}
            <button
              disabled={
                busy ||
                Boolean(nameError) ||
                nameLocked ||
                name.normalize('NFKC').trim() === profile.displayName
              }
              className={button}
            >
              {profile.displayName ? '이름 변경' : '표시 이름 등록'}
            </button>
          </form>
        </div>
      )}
      {namePolicy.error && (
        <p className="text-xs text-rose-500">
          {namePolicy.error}
          <button type="button" onClick={namePolicy.retry} className="ml-2 underline">
            규칙 다시 확인
          </button>
        </p>
      )}
      {view === 'security' && profile?.role === 'ADMIN' && (
        <form
          className="account-data-card space-y-3 rounded-xl bg-[var(--mint-soft)] p-3"
          onSubmit={async (event) => {
            event.preventDefault()
            if (busy || !targetUid.trim()) return
            setBusy(true)
            setError('')
            setNotice('')
            try {
              await request(
                `/admin/members/${encodeURIComponent(targetUid.trim())}/role`,
                undefined,
                'PUT',
                { role: targetRole },
              )
              setNotice(
                targetRole === 'ADMIN'
                  ? '관리자 권한을 부여했습니다.'
                  : '관리자 권한을 해제했습니다.',
              )
              setTargetUid('')
              setProfileRevision((value) => value + 1)
            } catch (error) {
              setError(error instanceof Error ? error.message : '권한 변경 실패')
            } finally {
              setBusy(false)
            }
          }}
        >
          <h3 className="text-xs font-bold text-[var(--mint)]">관리자 권한 관리</h3>
          <p className="text-[11px] leading-5 text-[var(--muted)]">
            Firebase Authentication에 등록된 회원의 UID를 입력하세요. 이메일 인증된 계정에 권한을
            부여할 수 있습니다. 마지막 관리자는 해제할 수 없습니다.
          </p>
          <label className="block text-xs">
            회원 UID
            <input
              required
              maxLength={128}
              disabled={busy}
              value={targetUid}
              onChange={(event) => setTargetUid(event.target.value)}
              className="mt-1 w-full rounded-xl border border-[var(--line)] bg-[var(--surface)] p-3 text-sm"
            />
          </label>
          <label className="block text-xs">
            변경할 권한
            <select
              value={targetRole}
              disabled={busy}
              onChange={(event) => setTargetRole(event.target.value as 'ADMIN' | 'MEMBER')}
              className="ml-2 rounded-lg border border-[var(--line)] bg-[var(--surface)] p-2"
            >
              <option value="ADMIN">관리자 부여</option>
              <option value="MEMBER">관리자 해제</option>
            </select>
          </label>
          <button disabled={busy || !targetUid.trim()} className={button}>
            권한 변경
          </button>
          <p className="text-[11px] text-[var(--muted)]">
            관리자는 커뮤니티에서 공지사항을 작성하고 게시글·댓글을 삭제할 수 있습니다.
          </p>
        </form>
      )}
      <div className="account-data-card" hidden={view !== 'security'}>
        <h2 className="account-section-title">
          <Icon name="shield" size={16} />
          비밀번호 관리
        </h2>
        <p className="mb-2 mt-3 text-xs text-[var(--muted)]">
          비밀번호는 이메일로 재설정할 수 있습니다.
        </p>
        <button
          type="button"
          disabled={busy || cooldown > 0 || !user.email}
          onClick={() => void action('reset')}
          className={button}
        >
          {cooldown ? `${cooldown}초 후 메일 전송 가능` : '비밀번호 재설정 메일 받기'}
        </button>
      </div>

      {notice && (
        <p role="status" className="text-xs leading-6 text-[var(--mint)]">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="text-xs leading-6 text-rose-500">
          {error}
        </p>
      )}
    </section>
  )
}
