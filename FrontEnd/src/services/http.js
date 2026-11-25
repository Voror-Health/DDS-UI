import axios from 'axios'
import { getAppBasePath } from './routes'
import Casdoor from './casdoor'

// All API calls go to "<context path>api/...", with the Casdoor bearer token.
export const http = axios.create({ baseURL: getAppBasePath() + 'api' })

http.interceptors.request.use((cfg) => {
  const token = Casdoor.token()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const cfg = error.config
    if (error.response?.status === 401 && cfg && !cfg.__retried) {
      cfg.__retried = true
      if (await Casdoor.onUnauthorised()) {
        cfg.headers.Authorization = `Bearer ${Casdoor.token()}`
        return http(cfg)
      }
    }
    return Promise.reject(error)
  },
)

/** GET returning the response body. params are sent as the query string. */
export const get = (url, params, cfg = {}) => http.get(url, { params, ...cfg }).then((r) => r.data)
export const post = (url, body, params, cfg = {}) => http.post(url, body, { params, ...cfg }).then((r) => r.data)
export const del = (url, params) => http.delete(url, { params }).then((r) => r.data)

/** Turns an axios error into a short message for a toast. */
export function errorMessage(error) {
  const data = error?.response?.data
  if (data) {
    if (typeof data === 'string' && data.length < 500) return data
    if (data.message) return data.message
    if (data.error) return data.error
  }
  if (error?.response?.status === 403) return 'You do not have permission to do this (needs vhp_ui_admin)'
  return error?.message || String(error)
}

/** Saves text or a Blob as a download (CSV exports etc.). */
export function saveFile(content, fileName, type = 'text/csv') {
  const blob = content instanceof Blob ? content : new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
