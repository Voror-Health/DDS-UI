// Port of the state and helpers in eds-ui's services/service.service.ts: the publisher list filters
// (kept for the session, as the Angular singleton did), the cached look-up lists and the filtering /
// sorting / formatting used by both the Publishers and Publisher Errors pages.
import { reactive } from 'vue'
import { serviceApi } from './api'
import { getDateDiffDesc, formatYYYYMMDDHHMM } from '@/utils/format'
import { orderBy } from '@/utils/ui'

function defaultFilters() {
  return {
    serviceNameSearchIncludeTags: false,
    serviceNameSearchSpecificTag: '',
    serviceCcgCodeFilterIsRegex: false,
    sortFilter: 'NameAsc',
    serviceNameFilter: null,
    servicePublisherConfigFilter: null,
    serviceStatusFilter: null,
    serviceCcgCodeFilterStr: null,
    serviceCcgCodeFilterRegex: null,
    serviceLastDataFilter: null,
    servicePublisherModeFilter: null,
    serviceHideClosedFilter: true,
    serviceShowDateFilter: false,
  }
}

export const filters = reactive(defaultFilters())

export function clearFilters() {
  Object.assign(filters, defaultFilters())
}

export const caches = reactive({
  tagNames: null,
  publisherConfigNames: null,
  ccgCodes: null,
  ccgNames: null,
})

const loading = {}
function loadCache(key, fn) {
  if (caches[key] || loading[key]) return
  loading[key] = true
  fn()
    .then((r) => (caches[key] = r))
    .catch(() => {})
    .finally(() => (loading[key] = false))
}

export const getTagNames = () => (loadCache('tagNames', serviceApi.getTagNames), caches.tagNames || [])
export const getPublisherConfigNames = () => (loadCache('publisherConfigNames', serviceApi.getPublisherConfigNames), caches.publisherConfigNames || [])
export const getCcgCodes = () => (loadCache('ccgCodes', serviceApi.getCcgCodes), caches.ccgCodes || [])

export function getCcgName(ccgCode) {
  if (!ccgCode) return ''
  loadCache('ccgNames', serviceApi.getCcgNames)
  if (!caches.ccgNames) return 'loading...'
  return caches.ccgNames[ccgCode] || '???'
}

export function createTagStr(service) {
  const list = getTagNames()
  let ret = ''
  if (service.tags && list) {
    for (const tagName of list) {
      if (Object.prototype.hasOwnProperty.call(service.tags, tagName)) {
        const tagValue = service.tags[tagName]
        if (ret.length > 0) ret += ', '
        ret += tagName
        if (tagValue) ret += ' ' + tagValue
      }
    }
  }
  return ret
}

function sortingStatusValue(s) {
  let ret = 0 // no status
  for (const status of s.systemStatuses || []) {
    let v
    if (!status.lastReceivedExtract) v = 1 // no data
    else if (!status.processingInError && status.processingUpToDate) v = 2 // OK
    else if (!status.processingInError && !status.processingUpToDate) v = 3 // behind
    else v = 4 // error
    if (v > ret) ret = v
  }
  return ret
}

function sortingDate(s) {
  let ret
  for (const status of s.systemStatuses || []) {
    if (status.lastReceivedExtractCutoff && (!ret || status.lastReceivedExtractCutoff > ret)) {
      ret = status.lastReceivedExtractCutoff
    }
  }
  if (!ret) ret = new Date().getTime()
  return ret * -1
}

const STATUS_FILTER_VALUES = { NoStatus: 0, NoData: 1, OK: 2, Behind: 3, Error: 4 }

/** eds-ui ServiceService.applyFiltering. transformErrorsView skips the status filter. */
export function applyFiltering(services, transformErrorsView) {
  if (!services) return []
  const f = filters

  let nameRegex
  if (f.serviceNameFilter) {
    try {
      new RegExp(f.serviceNameFilter)
      nameRegex = f.serviceNameFilter.toLowerCase().trim()
    } catch {
      /* invalid regex: ignore the filter */
    }
  }
  let parentRegex
  if (f.serviceCcgCodeFilterIsRegex && f.serviceCcgCodeFilterRegex) {
    try {
      new RegExp(f.serviceCcgCodeFilterRegex)
      parentRegex = f.serviceCcgCodeFilterRegex.toLowerCase()
    } catch {
      /* ignore */
    }
  }

  const now = Date.now()
  const day = 1000 * 60 * 60 * 24
  const cutoffs = { '1d': day, '2d': day * 2, '1wk': day * 7, '2wk': day * 14, older: day * 14 }
  const latestCutoffLimit = f.serviceLastDataFilter ? now - cutoffs[f.serviceLastDataFilter] : null

  const out = []
  for (const service of services) {
    if (f.servicePublisherConfigFilter) {
      const pc = service.publisherConfigName
      if (f.servicePublisherConfigFilter === 'NoPublisher') {
        if (pc) continue
      } else if (!pc || pc !== f.servicePublisherConfigFilter) {
        continue
      }
    }
    if (!f.serviceCcgCodeFilterIsRegex && f.serviceCcgCodeFilterStr) {
      if (!service.ccgCode || service.ccgCode !== f.serviceCcgCodeFilterStr) continue
    }
    if (f.serviceCcgCodeFilterIsRegex && parentRegex) {
      if (!service.ccgCode || !service.ccgCode.toLowerCase().match(parentRegex)) continue
    }
    if (f.serviceStatusFilter && !transformErrorsView) {
      if (sortingStatusValue(service) !== STATUS_FILTER_VALUES[f.serviceStatusFilter]) continue
    }
    if (f.servicePublisherModeFilter) {
      const include = (service.systemStatuses || []).some((s) => s.publisherMode === f.servicePublisherModeFilter)
      if (!include) continue
    }
    if (latestCutoffLimit) {
      const include = (service.systemStatuses || []).some((s) =>
        f.serviceLastDataFilter === 'older'
          ? s.lastReceivedExtractCutoff < latestCutoffLimit
          : s.lastReceivedExtractCutoff > latestCutoffLimit,
      )
      if (!include) continue
    }
    if (f.serviceHideClosedFilter && service.tags && Object.prototype.hasOwnProperty.call(service.tags, 'Closed')) {
      continue
    }
    if (f.serviceNameSearchIncludeTags && f.serviceNameSearchSpecificTag) {
      if (!service.tags || !Object.prototype.hasOwnProperty.call(service.tags, f.serviceNameSearchSpecificTag)) continue
    }
    if (nameRegex) {
      const lc = (s) => (s ? s.toLowerCase() : null)
      let include = false
      if (lc(service.name)?.match(nameRegex)) include = true
      if (lc(service.alias)?.match(nameRegex)) include = true
      else if (lc(service.localIdentifier)?.match(nameRegex)) include = true
      else if (lc(service.uuid) === nameRegex) include = true
      else if (service.tags && f.serviceNameSearchIncludeTags) {
        for (const [tagName, tagValue] of Object.entries(service.tags)) {
          if (!f.serviceNameSearchSpecificTag || tagName === f.serviceNameSearchSpecificTag) {
            if (tagName.toLowerCase().match(nameRegex) || (tagValue && tagValue.toLowerCase().match(nameRegex))) {
              include = true
              break
            }
          }
        }
      }
      if (!include) continue
    }
    out.push(service)
  }

  let sorted = orderBy(out, (s) => (s.name || '').toLowerCase())
  const sort = f.sortFilter || 'NameAsc'
  const desc = sort.endsWith('Desc')
  const keyFns = {
    ID: (s) => s.localIdentifier || '',
    Parent: (s) => s.ccgCode || '',
    PublisherConfig: (s) => s.publisherConfigName || '',
    LastData: sortingDate,
    Status: sortingStatusValue,
  }
  const key = sort.replace(/(Asc|Desc)$/, '')
  if (key !== 'Name' && keyFns[key]) {
    sorted = orderBy(sorted, keyFns[key])
  }
  if (desc) sorted.reverse()
  return sorted
}

// ---------------------------------------------------------------------------------------------
// formatting (eds-ui ServiceListComponent)
// ---------------------------------------------------------------------------------------------

export function formatDate(d) {
  if (!d) return '<missing date>'
  return formatYYYYMMDDHHMM(d)
}

export function formatLatestCutoff(service, status) {
  let ret = ''
  if ((service.systemStatuses || []).length > 1) ret += status.systemName + ': '
  if (status.lastReceivedExtractCutoff) {
    ret += getDateDiffDesc(new Date(status.lastReceivedExtractCutoff), new Date(), 2)
  } else {
    ret += 'n/a'
  }
  return ret
}

export function formatLatestCutoffTooltip(service, status) {
  if (status.lastReceivedExtractCutoff) {
    return (
      'Last data received ' + formatDate(new Date(status.lastReceivedExtract)) +
      ' for ' + formatDate(new Date(status.lastReceivedExtractCutoff))
    )
  }
  return 'No data received'
}

function publisherModePrefix(mode, long) {
  if (!mode || mode === 'Publisher_Normal') return ''
  if (mode === 'Publisher_Draft') return long ? '(Draft - new data will be rejected by messaging API) ' : '(D) '
  if (mode === 'Publisher_Auto_Fail') return long ? '(Auto-fail - inbound transform will automatically fail) ' : '(AF) '
  if (mode === 'Publisher_Bulk') return long ? '(Bulk - exchanges will be routed to bulk queues) ' : '(B) '
  return '<<<' + mode + '>>> '
}

export function formatProcessingStatusTooltip(service, status) {
  let ret = publisherModePrefix(status.publisherMode, true)
  if (status.lastProcessedExtract) {
    ret += 'Last successfully processed on ' + formatDate(new Date(status.lastProcessedExtract))
    if (status.lastProcessedExtractCutoff) {
      ret += ' for ' + formatDate(new Date(status.lastProcessedExtractCutoff))
    }
  } else {
    ret += 'Not successfully processed any data yet'
  }
  return ret
}

export function formatProcessingStatus(service, status) {
  let ret = publisherModePrefix(status.publisherMode, false)
  if (status.processingInError) ret += 'ERROR'
  else if (status.processingUpToDate) ret += 'OK'
  else if (status.lastReceivedExtract) ret += 'Behind'
  else ret += 'No data'
  if ((status.processingInError || !status.processingUpToDate) && status.lastProcessedExtractCutoff) {
    ret += ' (' + getDateDiffDesc(new Date(status.lastProcessedExtractCutoff), new Date(), 2) + ')'
  }
  return ret
}

export const PUBLISHER_MODES = [
  { value: 'Publisher_Draft', label: 'Draft' },
  { value: 'Publisher_Normal', label: 'Normal' },
  { value: 'Publisher_Auto_Fail', label: 'Auto-fail' },
  { value: 'Publisher_Bulk', label: 'Bulk' },
]
