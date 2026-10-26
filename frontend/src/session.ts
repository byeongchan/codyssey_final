import { useEffect, useState } from 'react'
import { onIdTokenChanged, type User } from 'firebase/auth'
import { auth } from './firebase'

export function useSession() {
  const [session, setSession] = useState<{ accountUser: User | null; ready: boolean }>({ accountUser: auth?.currentUser ?? null, ready: !auth })
  useEffect(() => {
    if (!auth) return
    return onIdTokenChanged(auth, accountUser => { setSession({ accountUser, ready: true }) }, () => { setSession({ accountUser: null, ready: true }) })
  }, [])
  return { user: session.accountUser?.emailVerified ? session.accountUser : null, accountUser: session.accountUser, ready: session.ready }
}
