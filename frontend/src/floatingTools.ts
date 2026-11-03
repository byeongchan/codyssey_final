import { useEffect, useSyncExternalStore } from 'react'

type Tool = 'menu' | 'ai' | 'recent' | null
let state: { tool: Tool; aiAvailable: boolean } = { tool: null, aiAvailable: false }
const listeners = new Set<() => void>()
function update(next: typeof state) {
  state = next
  listeners.forEach((listener) => listener())
}
const subscribe = (listener: () => void) => {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
export function openFloatingTool(tool: Tool) {
  update({ ...state, tool })
}
export function useFloatingTools() {
  return useSyncExternalStore(subscribe, () => state)
}
export function useFloatingAgent() {
  const { tool } = useFloatingTools()
  useEffect(() => {
    update({ ...state, aiAvailable: true })
    return () => update({ aiAvailable: false, tool: state.tool === 'ai' ? null : state.tool })
  }, [])
  return {
    panelOpen: tool === 'ai',
    setPanelOpen: (open: boolean) => openFloatingTool(open ? 'ai' : null),
  }
}
