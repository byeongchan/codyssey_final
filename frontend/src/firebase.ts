import { getApps, initializeApp } from 'firebase/app'
import { browserLocalPersistence, connectAuthEmulator, getAuth, initializeAuth, type Auth } from 'firebase/auth'

let auth: Auth | null = null
let configurationError = ''
try {
  const mode = import.meta.env.VITE_FIREBASE_MODE || 'cloud'
  const projectId = import.meta.env.VITE_FIREBASE_PROJECT_ID || 'demo-prizm'
  if (mode !== 'disabled') {
    if (!['emulator', 'cloud'].includes(mode)) throw new Error('Firebase 모드를 확인하세요.')
    if (mode === 'emulator' && (!import.meta.env.DEV || !['127.0.0.1', 'localhost'].includes(location.hostname) || !projectId.startsWith('demo-')))
      throw new Error('에뮬레이터는 로컬 개발 프로젝트에서만 사용하세요.')
    if (mode === 'cloud' && (projectId.startsWith('demo-') || !import.meta.env.VITE_FIREBASE_API_KEY || !import.meta.env.VITE_FIREBASE_AUTH_DOMAIN))
      throw new Error('실제 Firebase 웹 앱 설정을 입력해 주세요.')
    const name = mode === 'cloud' ? 'prizm-cloud' : 'prizm-local'
    const existing = getApps().find(app => app.name === name)
    const app = existing || initializeApp({ projectId, apiKey: mode === 'cloud' ? import.meta.env.VITE_FIREBASE_API_KEY : 'demo-local-key', authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN }, name)
    auth = existing ? getAuth(app) : initializeAuth(app, { persistence: browserLocalPersistence })
    if (mode === 'emulator' && !auth.emulatorConfig) connectAuthEmulator(auth, 'http://127.0.0.1:9099', { disableWarnings: true })
  } else configurationError = '로그인이 비활성화되어 있습니다.'
} catch (error) { auth = null; configurationError = error instanceof Error ? error.message : '로그인 환경을 확인해 주세요.' }

export { auth, configurationError }

export async function accessToken(forceRefresh = false): Promise<string> {
  if (!auth) throw new Error(configurationError || '로그인 환경을 확인해 주세요.')
  await auth.authStateReady()
  if (!auth.currentUser) throw new Error('로그인이 필요합니다.')
  return auth.currentUser.getIdToken(forceRefresh)
}
