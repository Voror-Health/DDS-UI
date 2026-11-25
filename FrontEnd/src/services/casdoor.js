// Casdoor login (OIDC authorization-code flow). Replaces keycloak-js.
//
// Same contract as vhp-datasteward's SPA: the client settings come from the public
// GET <app>/public/system/auth-config (the config DB 'auth' row the Casdoor valve also reads), and the
// code / refresh-token exchange goes through POST <app>/public/system/auth-token, so the client secret
// stays on the server. Tokens are kept in sessionStorage (per tab, cleared when the tab closes).
import { getAppBasePath } from './routes'

const KEY_ACCESS = 'dds_ui_access_token'
const KEY_REFRESH = 'dds_ui_refresh_token'
const KEY_EXPIRY = 'dds_ui_token_expiry'

let accessToken = null
let refreshToken = null
let tokenExpiry = 0
let refreshTimer = null
let config = null

function callbackUrl() {
  return `${window.location.origin}${getAppBasePath()}callback`
}

async function loadConfig() {
  if (config) return config
  const response = await fetch(`${getAppBasePath()}public/system/auth-config`)
  if (!response.ok) throw new Error(`Login configuration not available (HTTP ${response.status})`)
  config = await response.json()
  return config
}

function randomState() {
  const a = new Uint8Array(16)
  window.crypto.getRandomValues(a)
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('')
}

function payload() {
  if (!accessToken) return null
  try {
    const part = accessToken.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const bytes = Uint8Array.from(atob(part), (c) => c.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes))
  } catch {
    return null
  }
}

function safeSession(fn) {
  try {
    return fn()
  } catch {
    return null
  }
}

function storeTokens(tokens) {
  accessToken = tokens.access_token
  refreshToken = tokens.refresh_token || refreshToken
  tokenExpiry = Date.now() + (tokens.expires_in || 3600) * 1000
  safeSession(() => {
    sessionStorage.setItem(KEY_ACCESS, accessToken)
    if (refreshToken) sessionStorage.setItem(KEY_REFRESH, refreshToken)
    sessionStorage.setItem(KEY_EXPIRY, String(tokenExpiry))
  })
}

function clearTokens() {
  accessToken = null
  refreshToken = null
  tokenExpiry = 0
  safeSession(() => {
    sessionStorage.removeItem(KEY_ACCESS)
    sessionStorage.removeItem(KEY_REFRESH)
    sessionStorage.removeItem(KEY_EXPIRY)
  })
}

async function redirectToLogin() {
  const cfg = await loadConfig()
  const state = randomState()
  safeSession(() => {
    sessionStorage.setItem('dds_ui_state', state)
    sessionStorage.setItem('dds_ui_return', window.location.pathname + window.location.search)
  })
  const params = new URLSearchParams({
    response_type: 'code',
    client_id: cfg.clientId,
    redirect_uri: callbackUrl(),
    scope: 'openid profile email',
    state,
  })
  window.location.href = `${cfg.endpoint}/login/oauth/authorize?${params}`
}

async function tokenCall(body) {
  const response = await fetch(`${getAppBasePath()}public/system/auth-token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) throw new Error(`Token request failed (HTTP ${response.status})`)
  return response.json()
}

async function handleCallback() {
  const params = new URLSearchParams(window.location.search)
  const code = params.get('code')
  const state = params.get('state')
  const saved = safeSession(() => sessionStorage.getItem('dds_ui_state'))
  safeSession(() => sessionStorage.removeItem('dds_ui_state'))
  if (!code || !saved || state !== saved) {
    throw new Error('Login failed: the login response did not match this session. Please try again.')
  }
  storeTokens(await tokenCall({ grantType: 'authorization_code', code, redirectUri: callbackUrl() }))
  let back = safeSession(() => sessionStorage.getItem('dds_ui_return')) || getAppBasePath()
  safeSession(() => sessionStorage.removeItem('dds_ui_return'))
  if (back.includes('/callback')) back = getAppBasePath()
  window.history.replaceState(null, '', back)
}

async function refresh() {
  if (!refreshToken) return false
  try {
    storeTokens(await tokenCall({ grantType: 'refresh_token', refreshToken }))
    return true
  } catch (e) {
    console.error('Token refresh failed', e)
    return false
  }
}

function scheduleRefresh() {
  if (refreshTimer) clearTimeout(refreshTimer)
  const delay = Math.max(tokenExpiry - Date.now() - 60000, 10000)
  refreshTimer = setTimeout(async () => {
    if (await refresh()) {
      scheduleRefresh()
    } else {
      clearTokens()
      redirectToLogin()
    }
  }, delay)
}

const Casdoor = {
  /** Resolves once there is a valid token; otherwise redirects to Casdoor (and never resolves). */
  async init() {
    safeSession(() => {
      accessToken = sessionStorage.getItem(KEY_ACCESS)
      refreshToken = sessionStorage.getItem(KEY_REFRESH)
      tokenExpiry = parseInt(sessionStorage.getItem(KEY_EXPIRY) || '0', 10)
    })

    const params = new URLSearchParams(window.location.search)
    if (window.location.pathname.endsWith('/callback') && params.get('code')) {
      await handleCallback()
    } else if (!accessToken || Date.now() >= tokenExpiry) {
      if (!(await refresh())) {
        clearTokens()
        await redirectToLogin()
        return new Promise(() => {})
      }
    }
    scheduleRefresh()
    return true
  },

  token: () => accessToken,

  /** Called when the API answers 401: try one refresh, otherwise log in again. */
  async onUnauthorised() {
    if (await refresh()) return true
    clearTokens()
    await redirectToLogin()
    return false
  },

  userName() {
    const p = payload()
    return p?.displayName || p?.name || p?.preferred_username || ''
  },

  /** Casdoor role names from the token (role objects or strings). Only for showing/hiding UI. */
  roles() {
    const roles = payload()?.roles
    if (!Array.isArray(roles)) return []
    return roles.map((r) => (typeof r === 'string' ? r : r?.name)).filter(Boolean)
  },

  isAdmin() {
    return this.roles().includes('vhp_ui_admin')
  },

  async logout() {
    if (refreshTimer) clearTimeout(refreshTimer)
    clearTokens()
    try {
      const cfg = await loadConfig()
      const redirect = encodeURIComponent(`${window.location.origin}${getAppBasePath()}`)
      // same logout call as the vhp-datasteward SPA
      window.location.href = `${cfg.endpoint}/api/logout?redirect_uri=${redirect}&client_id=${encodeURIComponent(cfg.clientId)}`
    } catch {
      window.location.reload()
    }
  },
}

export default Casdoor
