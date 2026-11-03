import { useEffect, useState } from 'react'
import { request, type Single } from './api'

export type MemberProfile = {
  displayName: string | null
  role: 'MEMBER' | 'ADMIN'
  displayNameChangedAt: string | null
  nextNameChangeAt: string | null
}
type NamePolicy = {
  minLength: number
  maxLength: number
  reserved: string[]
  pattern: string
  changeIntervalDays: number
}
let policyRequest: Promise<NamePolicy> | null = null

export function useNamePolicy() {
  const [policy, setPolicy] = useState<NamePolicy | null>(null)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  useEffect(() => {
    let active = true
    setError('')
    if (!policyRequest)
      policyRequest = request<Single<NamePolicy>>('/danawa/display-name-policy')
        .then((result) => result.data)
        .catch((error) => {
          policyRequest = null
          throw error
        })
    void policyRequest
      .then((value) => {
        if (active) setPolicy(value)
      })
      .catch(() => {
        if (active) setError('표시 이름 규칙을 불러오지 못했습니다. 다시 확인해 주세요.')
      })
    return () => {
      active = false
    }
  }, [revision])
  return { policy, error, retry: () => setRevision((value) => value + 1) }
}

export function displayNameError(value: string, policy: NamePolicy | null): string {
  if (!policy) return '표시 이름 규칙을 확인 중입니다.'
  const name = value.normalize('NFKC').trim()
  if (
    [...name].length < policy.minLength ||
    [...name].length > policy.maxLength ||
    !new RegExp(policy.pattern, 'u').test(name)
  )
    return '표시 이름은 2~24자의 문자·숫자·공백·밑줄·마침표·하이픈으로 입력해 주세요.'
  const compact = name.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')
  return policy.reserved.some((word) => compact.includes(word))
    ? '관리자·어드민·운영자 등 운영진을 사칭하는 이름은 사용할 수 없습니다.'
    : ''
}
