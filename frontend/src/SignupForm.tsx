import { useEffect, useId, useState, type FormEvent } from 'react'
import { createUserWithEmailAndPassword, getIdToken, reload, sendEmailVerification, type User } from 'firebase/auth'
import { request } from './api'
import { displayNameError, useNamePolicy } from './memberProfile'
import { auth } from './firebase'

export default function SignupForm({ user, busy, setBusy, onComplete }: { user: User | null; busy: boolean; setBusy: (value: boolean) => void; onComplete: () => void }) {
  const namePolicy = useNamePolicy()
  const [name, setName] = useState(user?.displayName ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const confirmationId = useId()
  const [verified, setVerified] = useState(false)
  const [sent, setSent] = useState(false)
  const [cooldown, setCooldown] = useState(0)
  const [notice, setNotice] = useState('')
  const [error, setError] = useState('')
  useEffect(() => { if (user?.email) setEmail(user.email) }, [user?.uid, user?.email])
  useEffect(() => {
    if (!cooldown) return
    const timer = setTimeout(() => setCooldown(previous => Math.max(0, previous - 1)), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])
  const pending = Boolean(user)
  const nameError = displayNameError(name, namePolicy.policy)
  const validName = nameError === ''
  const passwordsMatch = pending || password.length >= 8 && password === confirmPassword
  const passwordMismatch = !pending && confirmPassword.length > 0 && password !== confirmPassword
  function fail(error: unknown) {
    const code = error && typeof error === 'object' && 'code' in error ? error.code : ''
    setError(code === 'auth/email-already-in-use' ? '이미 사용 중인 이메일입니다. 로그인 후 인증을 이어서 진행해 주세요.' : code === 'auth/weak-password' ? '비밀번호가 Firebase 비밀번호 정책을 충족하지 않습니다.' : code === 'auth/invalid-email' ? '이메일 형식을 확인해 주세요.' : code === 'auth/too-many-requests' ? '요청이 많습니다. 잠시 후 다시 시도해 주세요.' : code === 'auth/network-request-failed' ? '인증 서비스 연결을 확인해 주세요.' : error instanceof Error && !code ? error.message : '인증을 처리하지 못했습니다. 다시 시도해 주세요.')
  }
  async function send() {
    if (!auth || busy || cooldown || verified) return
    if (!validName) { setError(nameError); return }
    if (!validName || !email.trim() || !pending && password.length < 8) { setError('표시 이름, 이메일과 8자 이상의 비밀번호를 입력해 주세요.'); return }
    if (!passwordsMatch) { setError('비밀번호와 비밀번호 확인이 일치하지 않습니다.'); return }
    setBusy(true); setError(''); setNotice('')
    try {
      // Firebase needs an Auth user to send its verification link; keep this form open.
      const target = user ?? (await createUserWithEmailAndPassword(auth, email.trim(), password)).user
      setPassword('')
      setConfirmPassword('')
      if (auth.currentUser?.uid !== target.uid) throw new Error('계정이 변경되었습니다. 다시 확인해 주세요.')
      await sendEmailVerification(target)
      setSent(true); setCooldown(60)
      setNotice('인증 메일을 보냈습니다. 메일의 링크를 누르고 이 화면에서 인증 확인을 눌러 주세요.')
    } catch (error) { fail(error) } finally { setBusy(false) }
  }
  async function check() {
    if (!user || busy || auth?.currentUser?.uid !== user.uid) return
    setBusy(true); setError(''); setNotice('')
    try {
      await reload(user)
      if (auth?.currentUser?.uid !== user.uid) return
      const matches = user.email?.toLowerCase() === email.trim().toLowerCase()
      setVerified(user.emailVerified && matches)
      setNotice(user.emailVerified && matches ? '이메일 인증이 확인되었습니다. 회원가입을 완료해 주세요.' : '아직 인증이 확인되지 않았습니다. 인증 메일의 링크를 먼저 눌러 주세요.')
    } catch (error) { fail(error) } finally { setBusy(false) }
  }
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!auth || busy) return
    if (!user || !verified || !validName || !passwordsMatch) { setError('이메일 인증과 가입 정보 확인을 완료해 주세요.'); return }
    setBusy(true); setError('')
    try {
      await reload(user)
      if (auth.currentUser?.uid !== user.uid || !user.emailVerified || user.email?.toLowerCase() !== email.trim().toLowerCase()) {
        setVerified(false); throw new Error('이메일 인증을 다시 확인해 주세요.')
      }
      await getIdToken(user, true)
      await request('/me/profile', undefined, 'PATCH', { displayName: name.normalize('NFKC').trim() })
      onComplete()
    } catch (error) { fail(error) } finally { setBusy(false) }
  }
  const input = 'mt-2 w-full rounded-2xl border border-[var(--line-strong)] bg-[var(--surface)] px-4 py-3 text-sm outline-none focus:border-[var(--primary)] disabled:bg-[var(--surface-2)] disabled:text-[var(--muted)]'
  return <form onSubmit={submit} className="space-y-4" aria-label="이메일 인증 회원가입">
    <label className="block text-xs font-bold">표시 이름<input autoComplete="nickname" minLength={2} maxLength={24} required value={name} onChange={event => setName(event.target.value)} disabled={busy} placeholder="표시 이름 (2~24자)" className={input}/></label>
    {name && namePolicy.policy && nameError && <p className="text-xs text-rose-500">{nameError}</p>}
    {namePolicy.error && <p role="alert" className="text-xs text-rose-500">{namePolicy.error}<button type="button" onClick={namePolicy.retry} className="ml-2 underline">다시 확인</button></p>}
    <p className="text-[11px] text-[var(--muted)]">표시 이름은 등록·변경 후 7일에 한 번 변경할 수 있습니다. 운영진 사칭 이름은 사용할 수 없습니다.</p>
    <div><label className="block text-xs font-bold">이메일<input type="email" autoComplete="email" required value={email} onChange={event => { setEmail(event.target.value); setVerified(false) }} disabled={busy || pending} placeholder="이메일 주소" className={input}/></label>
      <div className="mt-2 flex flex-wrap gap-2"><button type="button" disabled={busy || !auth || cooldown > 0 || verified || !passwordsMatch} onClick={event => { if (event.currentTarget.form?.reportValidity()) void send() }} className="rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold text-[var(--primary)] disabled:opacity-40">{cooldown ? `${cooldown}초 후 재발송` : sent ? '인증메일 다시 보내기' : '인증메일 보내기'}</button><button type="button" disabled={busy || !user} onClick={() => void check()} className="rounded-xl border border-[var(--line)] px-3 py-2 text-xs font-bold text-[var(--primary)] disabled:opacity-40">인증 확인</button></div>
      <p role="status" className={`mt-2 text-xs ${verified ? 'font-bold text-[var(--mint)]' : 'text-[var(--muted)]'}`}>{verified ? '이메일 인증 완료' : '이메일 인증 확인이 필요합니다.'}</p>
    </div>
    <label className="block text-xs font-bold">비밀번호<input type="password" autoComplete="new-password" minLength={8} required={!pending} value={password} onChange={event => setPassword(event.target.value)} disabled={busy || pending} placeholder={pending ? '처음 입력한 비밀번호로 설정되어 있습니다' : '비밀번호 8자 이상'} className={input}/></label>
    <label className="block text-xs font-bold">비밀번호 확인<input type="password" autoComplete="new-password" minLength={8} required={!pending} value={confirmPassword} onChange={event => setConfirmPassword(event.target.value)} disabled={busy || pending} placeholder={pending ? '기존 비밀번호가 설정되어 있습니다' : '비밀번호를 다시 입력해 주세요'} aria-invalid={passwordMismatch} aria-describedby={passwordMismatch ? confirmationId : undefined} className={input}/></label>
    {passwordMismatch && <p id={confirmationId} className="text-xs text-rose-500">비밀번호가 일치하지 않습니다.</p>}
    {pending && <p className="text-[11px] leading-5 text-[var(--muted)]">인증을 요청한 이메일과 비밀번호는 고정됩니다. 이메일 인증 후 아래 버튼으로 가입을 완료해 주세요.</p>}
    <button disabled={busy || !auth || !verified || !validName || !passwordsMatch} className="w-full rounded-2xl bg-[var(--primary)] px-5 py-3.5 text-sm font-black text-white disabled:opacity-40">{busy ? '처리 중…' : '회원가입 완료'}</button>
    {notice && <p role="status" className="text-xs leading-6 text-[var(--mint)]">{notice}</p>}{error && <p role="alert" className="text-xs leading-6 text-rose-500">{error}</p>}
  </form>
}
