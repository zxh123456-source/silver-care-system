import type { Store } from 'vuex'
import type { IRootState } from './types'
import type { IAppState } from './modules/app/types'

const storageKey = 'stateData'
const forbiddenKeys = new Set(['__proto__', 'constructor', 'prototype'])

function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function parseState(raw: string): unknown {
  return JSON.parse(raw, (key, value) => forbiddenKeys.has(key) ? undefined : value)
}

// Keep the existing app-only storage protocol; never merge arbitrary root keys.
function appFields(value: unknown): Partial<IAppState> {
  if (!record(value)) return {}
  const app: Partial<IAppState> = {}
  if (typeof value.uid === 'number' && Number.isSafeInteger(value.uid)) app.uid = value.uid
  if (typeof value.token === 'string') app.token = value.token
  if (typeof value.hasAuth === 'boolean') app.hasAuth = value.hasAuth
  if (typeof value.siderType === 'boolean') app.siderType = value.siderType
  if (typeof value.rememberPWD === 'boolean') app.rememberPWD = value.rememberPWD
  if (Array.isArray(value.routeTree)) app.routeTree = value.routeTree
  if (record(value.userPeofile)) app.userPeofile = value.userPeofile
  return app
}

export function persistAppState(store: Store<IRootState>): void {
  try {
    const saved = parseState(window.localStorage.getItem(storageKey) || 'null')
    if (record(saved)) {
      store.replaceState({ ...store.state, app: { ...store.state.app, ...appFields(saved.app) } })
    }
  } catch {
    // Invalid or unavailable browser storage must not prevent an in-memory session.
  }

  store.subscribe((_mutation, state) => {
    try {
      const app = appFields(parseState(JSON.stringify(state.app)))
      window.localStorage.setItem(storageKey, JSON.stringify({ app }))
    } catch {
      // Quota/access failures must not turn a successful mutation into a UI error.
    }
  })
}
