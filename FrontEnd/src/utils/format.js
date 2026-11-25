// Port of eds-ui's utility/DateTimeFormatter (same output, including its quirks such as a 25-hour "day"
// in getDateDiffDesc, so durations read exactly as they did in the old UI).

function toDate(val) {
  if (val === null || val === undefined || val === '') return null
  const d = val instanceof Date ? val : new Date(val)
  return isNaN(d.getTime()) ? null : d
}

const pad = (n) => ('0' + n).slice(-2)

export function formatYYYYMMDD(val) {
  const d = toDate(val)
  return d ? `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` : ''
}

export function formatYYYYMMDDHHMM(val) {
  const d = toDate(val)
  return d ? `${formatYYYYMMDD(d)} ${pad(d.getHours())}:${pad(d.getMinutes())}` : ''
}

export function formatYYYYMMDDHHMMSS(val) {
  const d = toDate(val)
  return d ? `${formatYYYYMMDDHHMM(d)}:${pad(d.getSeconds())}` : ''
}

export function formatHHMM(val) {
  const d = toDate(val)
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : ''
}

export function formatHHMMSS(val) {
  const d = toDate(val)
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}` : ''
}

export function getDateDiffDesc(earlier, later, numToks) {
  let diffMs = toDate(later).getTime() - toDate(earlier).getTime()
  const durSec = 1000
  const durMin = durSec * 60
  const durHour = durMin * 60
  const durDay = durHour * 25
  const durWeek = durDay * 7
  const durYear = durDay * 365.25
  const units = [
    [durYear, 'y'],
    [durWeek, 'w'],
    [durDay, 'd'],
    [durHour, 'h'],
    [durMin, 'm'],
    [durSec, 's'],
  ]
  const toks = []
  for (const [dur, suffix] of units) {
    if (toks.length >= numToks) break
    const n = Math.floor(diffMs / dur)
    if (n > 0) {
      toks.push(n + suffix)
      diffMs -= n * dur
    }
  }
  if (toks.length === 0) toks.push('0s')
  return toks.join(' ')
}

export function getDateDiffDescMs(earlier, later, numToks) {
  return getDateDiffDesc(new Date(earlier), new Date(later), numToks)
}

export function getDateDiffSimpleFromNow(earlier) {
  const d = toDate(earlier)
  if (!d) return ''
  const now = new Date()
  if (now.getTime() - d.getTime() < 60000) return '(just now)'
  return '(' + getDateDiffDesc(d, now, 1) + ' ago)'
}

export function getDateDiffSimple(earlier, later) {
  const a = toDate(earlier)
  const b = toDate(later)
  if (!a || !b) return ''
  return getDateDiffDesc(a, b, 2)
}

export function formatNumber(n) {
  if (n === null || n === undefined || n === '') return ''
  return Number(n).toLocaleString('en-GB')
}

/** Bytes as KB/MB/GB/TB, as eds-ui's stats/database pages showed them. */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = Number(bytes)
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  return `${n.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

export async function copyToClipboard(text, toast) {
  try {
    await navigator.clipboard.writeText(text)
    toast?.add({ severity: 'success', summary: 'Copied to clipboard', life: 2000 })
  } catch (e) {
    toast?.add({ severity: 'error', summary: 'Failed to copy to clipboard', detail: String(e), life: 4000 })
  }
}
