import { useEffect, useState } from 'react'
import { onIdTokenChanged, type User } from 'firebase/auth'
import { auth } from './firebase'

export function useSession() {
  const [user, setUser] = useState<User | null>(auth?.currentUser ?? null)
  const [ready, setReady] = useState(!auth)
  useEffect(() => {
    if (!auth) return
    return onIdTokenChanged(auth, user => { setUser(user); setReady(true) }, () => { setUser(null); setReady(true) })
  }, [])
  return { user, ready }
}
