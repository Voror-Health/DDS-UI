<template>
  <div>
    <div class="page-header">
      <h1>Exchange History for {{ service.name }}</h1>
      <span class="sub">
        {{ service.localIdentifier }}
        <Button v-if="service.localIdentifier" icon="fa-solid fa-copy" text size="small" v-tooltip="'Copy to clipboard'"
                @click="copy(service.localIdentifier)" />
        <span class="mono">{{ service.uuid }}</span>
        <Button v-if="service.uuid" icon="fa-solid fa-copy" text size="small" v-tooltip="'Copy to clipboard'"
                @click="copy(service.uuid)" />
        <span v-if="service.publisherConfigName">on {{ service.publisherConfigName }}</span>
      </span>
      <div class="actions">
        <Button label="Edit Service" severity="secondary" size="small" @click="$router.push(`/serviceEdit/edit/${service.uuid}`)" :disabled="!service.uuid" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refreshExchanges(false)" />
        <Button label="Close" severity="secondary" size="small" @click="$router.back()" />
      </div>
    </div>

    <div class="layout-cols">
      <!-- left: search options and exchange list -->
      <div>
        <Panel header="Search Options" toggleable :collapsed="searchCollapsed" @update:collapsed="searchCollapsed = $event" class="mb-1">
          <div class="field mb-1">
            <label>Search mode</label>
            <Select v-model="s.searchMode" :options="searchModes" optionLabel="label" optionValue="value" />
          </div>
          <div v-if="s.searchMode === 'Recent'" class="field mb-1">
            <label>Showing last</label>
            <div class="field-inline">
              <InputNumber v-model="s.exchangesToShow" :min="1" inputStyle="width: 6rem" />
              <Button v-for="n in [10, 100, 250, 500, 10000]" :key="n" :label="String(n)" size="small" severity="secondary"
                      outlined @click="showLast(n)" />
            </div>
          </div>
          <template v-if="s.searchMode === 'DateRange'">
            <div class="field mb-1">
              <label>Search from</label>
              <DatePicker v-model="s.exchangeSearchFrom" showTime hourFormat="24" dateFormat="dd-M-yy" showIcon />
            </div>
            <div class="field mb-1">
              <label>Search to</label>
              <DatePicker v-model="s.exchangeSearchTo" showTime hourFormat="24" dateFormat="dd-M-yy" showIcon />
            </div>
            <div class="field mb-1">
              <label>Showing last</label>
              <InputNumber v-model="s.exchangesToShow" :min="1" />
            </div>
          </template>
          <div v-if="s.searchMode === 'FirstError'" class="field mb-1">
            <label>Plus following exchanges</label>
            <InputNumber v-model="s.exchangesToShow" :min="0" />
          </div>
          <div v-if="s.searchMode === 'ExchangeId'" class="field mb-1">
            <label>Exchange ID</label>
            <InputText v-model="s.exchangeIdSearch" @keyup.enter="refreshExchanges(false)" />
          </div>
          <Button label="Go" @click="refreshExchanges(false)" />
        </Panel>

        <div class="card" style="padding: 0.4rem">
          <div v-if="loadingExchanges" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading exchanges...</div>
          <DataTable v-else :value="exchanges || []" size="small" stripedRows dataKey="exchangeId" scrollable
                     scrollHeight="60vh" selectionMode="single" :selection="selectedExchange"
                     @row-click="selectExchange($event.data)" :rowClass="(e) => (e === selectedExchange ? 'row-selected' : '')">
            <Column header="Received">
              <template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMMSS(data.timestamp) }}</span></template>
            </Column>
            <Column header="Extract Date">
              <template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMM(data.headers['extract-date']) }}</span></template>
            </Column>
            <Column header="Extract Cutoff">
              <template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMM(data.headers['extract-cutoff']) }}</span></template>
            </Column>
            <Column header="Size">
              <template #body="{ data }">
                <span class="nowrap">
                  <span :style="{ backgroundColor: sizeColour(data), padding: '0 3px' }">{{ data.exchangeSizeDesc }}</span>
                  <i v-if="isBulk(data)" class="fa-solid fa-circle-plus ml-1" v-tooltip="'Bulk extract'"></i>
                  <i v-if="!canBeQueued(data)" class="fa-solid fa-chevron-down ml-1" v-tooltip="'Cannot be re-queued'"></i>
                  <i v-if="!hasPatientData(data)" class="fa-solid fa-square-minus ml-1" v-tooltip="'No patient data'"></i>
                </span>
              </template>
            </Column>
            <Column header="Err">
              <template #body="{ data }"><span v-if="data.inError" class="err bold">Y</span></template>
            </Column>
          </DataTable>
          <div v-if="exchanges" class="muted mt-1">{{ exchanges.length }} exchanges found</div>
        </div>
      </div>

      <!-- right: the selected exchange -->
      <div v-if="selectedExchange">
        <div class="right-top">
          <div class="card">
            <h2>
              Actions for Exchange <span class="mono">{{ selectedExchange.exchangeId }}</span>
              <Button icon="fa-solid fa-copy" text size="small" v-tooltip="'Copy to clipboard'" @click="copy(selectedExchange.exchangeId)" />
              <Button v-if="canBeQueued(selectedExchange)" label="Make Non-Queueable" size="small" severity="secondary"
                      outlined style="float: right" @click="makeNonQueueable" :disabled="!admin" />
            </h2>

            <div class="filters mb-1">
              <div class="field" style="width: 11rem">
                <label>Post to exchange</label>
                <Select v-model="s.postExchange" :options="postExchanges" optionLabel="label" optionValue="value" showClear placeholder=" " />
              </div>
              <div class="field" style="width: 16rem">
                <label>Exchanges to post</label>
                <Select v-model="s.postMode" :options="postModes" optionLabel="label" optionValue="value" />
              </div>
            </div>
            <div class="muted mb-1">
              Queues:
              Inbound={{ selectedExchange.queueRoutingKeys?.EdsInbound }},
              Protocol={{ selectedExchange.queueRoutingKeys?.EdsProtocol }},
              Transform={{ selectedExchange.queueRoutingKeys?.EdsTransform }},
              Subscriber={{ selectedExchange.queueRoutingKeys?.EdsSubscriber }}
            </div>

            <div v-if="s.postExchange === 'edsInbound'" class="filters mb-1">
              <div class="field" style="width: 20rem">
                <label>Reason</label>
                <InputText v-model="s.postReason" />
              </div>
              <span class="field-inline">
                <Checkbox v-model="s.postDeleteErrorState" binary inputId="delErr" />
                <label for="delErr">Delete error state</label>
              </span>
              <span class="field-inline">
                <Checkbox v-model="s.postFilterFileTypes" binary inputId="filterTypes" />
                <label for="filterTypes">Filter file types</label>
              </span>
            </div>
            <Textarea v-if="s.postExchange === 'edsInbound' && s.postFilterFileTypes" v-model="s.postFilterFileTypesSelected"
                      rows="6" class="w-full mb-1" placeholder="File types to filter on (space, comma or newline separated)" />

            <div v-if="s.postExchange === 'edsProtocol'" class="filters mb-1">
              <div class="field" style="flex: 1; min-width: 18rem">
                <label>Protocol(s) to apply (leave empty to do all)</label>
                <MultiSelect v-model="postSpecificProtocols" :options="subscriberConfigNames" filter display="chip"
                             placeholder="All subscribers" />
              </div>
              <span class="field-inline">
                <Checkbox v-model="s.postShowAllSubscriberConfigNames" binary inputId="allSubs" @change="refreshSubscriberConfigNames" />
                <label for="allSubs">Include all subscribers</label>
              </span>
            </div>

            <div v-if="canBeQueued(selectedExchange)">
              <Button label="GO!" severity="success" :loading="busyPosting" @click="postToExchange" :disabled="!admin"
                      v-tooltip="admin ? '' : 'Needs the vhp_ui_admin role'" />
            </div>
            <Tag v-else severity="warn" value="This exchange cannot be re-queued" />
          </div>

          <div class="card" v-if="publisherMode">
            <h2>Publisher-level Settings</h2>
            <div class="field mb-1">
              <label>Publisher mode</label>
              <Select v-model="publisherMode" :options="PUBLISHER_MODES" optionLabel="label" optionValue="value" />
            </div>
            <div class="field mb-1">
              <label>Publisher config</label>
              <Select v-model="service.publisherConfigName" :options="publisherConfigList" showClear filter placeholder=" " />
            </div>
            <Button label="Save" severity="success" size="small" @click="savePublisherMode" />
          </div>
        </div>

        <Panel header="View Headers" toggleable collapsed class="mb-1">
          <ul class="headers">
            <li v-for="key in Object.keys(selectedExchange.headers || {})" :key="key">
              <span class="bold">{{ key }}</span> = <span class="mono">{{ selectedExchange.headers[key] }}</span>
            </li>
          </ul>
        </Panel>

        <Panel header="View Body" toggleable collapsed class="mb-1">
          <div class="mb-1">
            <Button label="Copy to Clipboard" size="small" severity="secondary" outlined @click="copyBody(false)" />
            <Button label="Copy as JSON" size="small" severity="secondary" outlined class="ml-1" @click="copyBody(true)" />
          </div>
          <pre class="block">{{ (selectedExchange.bodyLines || []).join('\n') }}</pre>
        </Panel>

        <div class="card">
          <h2>Events
            <Button label="Add Note" size="small" severity="secondary" outlined class="ml-1" @click="addNoteVisible = true" :disabled="!admin" />
          </h2>
          <div v-if="!selectedExchange.transformAudits" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
          <DataTable v-else :value="selectedExchange.transformAudits" size="small" stripedRows
                     @row-click="showTransformErrors($event.data)" :rowClass="(a) => (a.transformError ? 'clickable' : '')">
            <Column header="Event" style="width: 50%">
              <template #body="{ data }">
                {{ data.eventDesc }}
                <i v-if="data.transformError" class="fa-solid fa-triangle-exclamation err" v-tooltip="'Transform failed with error - click to view'"></i>
                <i v-if="data.transformSuccess" class="fa-solid fa-check ok" v-tooltip="'Transform completed OK'"></i>
                <i v-if="data.transformInProgress" class="fa-regular fa-clock" v-tooltip="'Transform in progress (or failed without error)'"></i>
              </template>
            </Column>
            <Column header="Started"><template #body="{ data }">{{ formatYYYYMMDDHHMMSS(data.transformStart) }}</template></Column>
            <Column header="Ended"><template #body="{ data }">{{ formatYYYYMMDDHHMMSS(data.transformEnd) }}</template></Column>
            <Column header="Dur"><template #body="{ data }">{{ duration(data) }}</template></Column>
            <Column field="numberBatchIdsCreated" header="#Batch IDs" />
          </DataTable>
        </div>
      </div>
    </div>

    <TransformErrorLinesDialog v-model:visible="errorsVisible" :serviceId="errorsArgs.serviceId" :systemId="errorsArgs.systemId"
                               :exchangeId="errorsArgs.exchangeId" :version="errorsArgs.version" />

    <Dialog v-model:visible="addNoteVisible" modal header="Add Note" :style="{ width: '30rem' }">
      <Textarea v-model="noteText" rows="4" class="w-full" autofocus />
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="addNoteVisible = false" />
        <Button label="Add" @click="addNote" :disabled="!noteText" />
      </template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useToast } from 'primevue/usetoast'
import { serviceApi, exchangeAuditApi } from '@/services/api'
import { getPublisherConfigNames, PUBLISHER_MODES } from '@/services/serviceStore'
import { formatYYYYMMDDHHMM, formatYYYYMMDDHHMMSS, getDateDiffDesc, copyToClipboard } from '@/utils/format'
import { useNotify, useConfirmAsync, orderBy, isAdmin } from '@/utils/ui'
import TransformErrorLinesDialog from '@/components/TransformErrorLinesDialog.vue'

// search and post options persist between visits for the session (eds-ui kept them in ExchangeAuditService)
const s = reactive(
  (window.__ddsExchangeAuditState ||= {
    searchMode: 'Recent',
    exchangesToShow: 20,
    exchangeIdSearch: '',
    exchangeSearchFrom: null,
    exchangeSearchTo: null,
    postMode: 'Onwards',
    postExchange: '',
    postReason: '',
    postFilterFileTypes: false,
    postFilterFileTypesSelected: '',
    postDeleteErrorState: false,
    postShowAllSubscriberConfigNames: false,
  }),
)

const route = useRoute()
const notify = useNotify()
const toast = useToast()
const confirmAsync = useConfirmAsync()
const admin = isAdmin()

const systemId = route.params.systemId
const service = ref({ endpoints: [] })
const exchanges = ref(null)
const selectedExchange = ref(null)
const loadingExchanges = ref(false)
const subscriberConfigNames = ref([])
const postSpecificProtocols = ref([])
const busyPosting = ref(false)
const publisherMode = ref(null)
const searchCollapsed = ref(true)

const searchModes = [
  { value: 'Recent', label: 'Most Recent' },
  { value: 'DateRange', label: 'Date Range' },
  { value: 'FirstError', label: 'First Error' },
  { value: 'ExchangeId', label: 'Exchange ID' },
]
const postExchanges = [
  { value: 'edsInbound', label: 'Inbound' },
  { value: 'edsProtocol', label: 'Protocol' },
]
const postModes = computed(() => {
  const list = [
    { value: 'This', label: 'This Exchange Only' },
    { value: 'Onwards', label: 'This Exchange and Onwards' },
    { value: 'All', label: 'All Exchanges' },
  ]
  if (s.postExchange === 'edsProtocol') {
    list.push(
      { value: 'FullDelete', label: 'Full Delete of Data' },
      { value: 'FullRefresh', label: 'Full Refresh of Data' },
      { value: 'QuickRefresh', label: 'Quick Refresh of Data' },
      { value: 'FullRefreshAdminOnly', label: 'Full Refresh of Admin Data' },
    )
  }
  return list
})
const publisherConfigList = computed(() => {
  const list = [...getPublisherConfigNames()]
  if (service.value.publisherConfigName && !list.includes(service.value.publisherConfigName)) list.push(service.value.publisherConfigName)
  return list
})

async function refreshExchanges(autoSelectFirst) {
  const serviceId = service.value.uuid
  selectedExchange.value = null
  let call
  if (s.searchMode === 'Recent') {
    call = exchangeAuditApi.getRecentExchanges(serviceId, systemId, s.exchangesToShow)
  } else if (s.searchMode === 'DateRange') {
    if (s.exchangeSearchFrom && s.exchangeSearchTo && new Date(s.exchangeSearchTo) < new Date(s.exchangeSearchFrom)) {
      notify.error('Search date range is invalid')
      return
    }
    call = exchangeAuditApi.getExchangesByDate(serviceId, systemId, s.exchangesToShow, s.exchangeSearchFrom, s.exchangeSearchTo)
  } else if (s.searchMode === 'FirstError') {
    call = exchangeAuditApi.getExchangesFromFirstError(serviceId, systemId, s.exchangesToShow)
  } else {
    if (!s.exchangeIdSearch) {
      notify.error('Enter an exchange ID to search')
      return
    }
    call = exchangeAuditApi.getExchangeById(serviceId, systemId, s.exchangeIdSearch.trim())
  }
  loadingExchanges.value = true
  try {
    const result = await call
    exchanges.value = result
    if (result.length === 0) notify.success('No exchanges found')
    else if (autoSelectFirst) selectExchange(result[0])
  } catch (e) {
    notify.error('Failed to retrieve exchanges', e)
  } finally {
    loadingExchanges.value = false
  }
}

function showLast(n) {
  s.exchangesToShow = n
  refreshExchanges(false)
}

function selectExchange(exchange) {
  // Callers may pass the raw API object (e.g. result[0] on auto-select). Writes to a raw object are
  // invisible to Vue, so the Events spinner would never clear: always mutate the reactive proxy.
  const ex = reactive(exchange)
  selectedExchange.value = ex
  if (!ex.transformAudits) loadTransformAudits(ex)
}

async function loadTransformAudits(exchange) {
  const serviceId = exchange.headers?.SenderServiceUuid
  const sysId = exchange.headers?.SenderSystemUuid
  if (!serviceId || !sysId) {
    exchange.transformAudits = []
    return
  }
  try {
    const result = await exchangeAuditApi.getInboundTransformAudits(serviceId, sysId, exchange.exchangeId, true)
    exchange.transformAudits = orderBy(result, (a) => a.transformStart || 0, true)
  } catch (e) {
    exchange.transformAudits = []
    notify.error('Failed to retrieve transform audits', e)
  }
}

async function refreshSubscriberConfigNames() {
  try {
    subscriberConfigNames.value = s.postShowAllSubscriberConfigNames
      ? await exchangeAuditApi.getAllSubscriberConfigNameList()
      : await exchangeAuditApi.getSubscriberConfigNameList(service.value.uuid)
  } catch (e) {
    notify.error('Failed to retrieve subscriber config names', e)
  }
}

function refreshPublisherMode() {
  publisherMode.value = null
  for (const e of service.value.endpoints || []) {
    if (e.systemUuid === systemId && e.endpoint && e.endpoint.startsWith('Publisher_')) publisherMode.value = e.endpoint
  }
}

async function savePublisherMode() {
  for (const e of service.value.endpoints || []) {
    if (e.systemUuid === systemId) e.endpoint = publisherMode.value
  }
  try {
    const warning = await serviceApi.validateSave(service.value)
    if (warning) {
      notify.warning(warning)
      return
    }
    await serviceApi.save(service.value)
    notify.success('Service saved')
  } catch (e) {
    notify.error('Error saving', e)
  }
}

async function postToExchange() {
  const ex = selectedExchange.value
  if (!s.postExchange) {
    notify.error('No exchange selected')
    return
  }
  if (!s.postMode) {
    notify.error('Select which exchanges to post')
    return
  }
  let fileTypesToFilterOn
  if (s.postExchange === 'edsInbound' && s.postFilterFileTypes) {
    fileTypesToFilterOn = s.postFilterFileTypesSelected
    if (!fileTypesToFilterOn) {
      notify.error('No file types selected')
      return
    }
  }
  busyPosting.value = true
  try {
    const result = await exchangeAuditApi.postToExchange({
      exchangeId: ex.exchangeId,
      serviceId: service.value.uuid,
      systemId,
      exchangeName: s.postExchange,
      postMode: s.postMode,
      specificSubscriberConfigNames: s.postExchange === 'edsProtocol' ? [...postSpecificProtocols.value] : [],
      fileTypesToFilterOn,
      deleteTransformErrorState: s.postExchange === 'edsInbound' ? s.postDeleteErrorState : false,
      reason: s.postReason,
    })
    if (result) {
      notify.warning(typeof result === 'string' ? result : JSON.stringify(result))
    } else {
      notify.success('Successfully posted to ' + s.postExchange + ' exchange')
      loadTransformAudits(ex)
    }
  } catch (e) {
    notify.error('Failed to post to ' + s.postExchange + ' exchange', e)
  } finally {
    busyPosting.value = false
  }
}

// transform error text
const errorsVisible = ref(false)
const errorsArgs = ref({})
function showTransformErrors(audit) {
  if (!audit.transformError) {
    notify.success('No errors to view')
    return
  }
  errorsArgs.value = {
    serviceId: selectedExchange.value.headers.SenderServiceUuid,
    systemId: selectedExchange.value.headers.SenderSystemUuid,
    exchangeId: audit.exchangeId,
    version: audit.version,
  }
  errorsVisible.value = true
}

// notes
const addNoteVisible = ref(false)
const noteText = ref('')
async function addNote() {
  addNoteVisible.value = false
  const ex = selectedExchange.value
  try {
    await exchangeAuditApi.addExchangeEvent(ex.exchangeId, noteText.value)
    noteText.value = ''
    loadTransformAudits(ex)
  } catch (e) {
    notify.error('Failed to add note', e)
  }
}

async function makeNonQueueable() {
  if (!(await confirmAsync('Are you sure you want to make this exchange non-queueable?', 'Make Non-Queueable'))) return
  try {
    await exchangeAuditApi.makeNonQueueable(selectedExchange.value.exchangeId)
    refreshExchanges(false)
  } catch (e) {
    notify.error('Failed to change exchange', e)
  }
}

function copy(str) {
  copyToClipboard(str, toast)
}

function copyBody(asJson) {
  let joined = (selectedExchange.value.bodyLines || []).join('\r\n')
  if (asJson) {
    try {
      joined = JSON.stringify(JSON.parse(joined), null, 2)
    } catch {
      notify.error('Not valid JSON')
      return
    }
  }
  copy(joined)
}

const canBeQueued = (e) => e.headers?.AllowQueueing !== 'false'
const hasPatientData = (e) => e.headers?.['has-patient-data'] !== 'false'
const isBulk = (e) => !!e.headers?.['is-bulk'] && e.headers['is-bulk'] !== 'false'

function duration(a) {
  if (!a || !a.transformStart || !a.transformEnd) return ''
  return getDateDiffDesc(new Date(a.transformStart), new Date(a.transformEnd), 2)
}

// colour the size cell from blue (small) to red (large) on a log scale, as eds-ui did
const MIN_LOG = Math.log10(1 / (1024 * 1024))
function sizeColour(exchange) {
  const size = exchange.exchangeSizeBytes
  if (!size) return '#FFFFFF'
  let log10 = Math.log10(size / (1024 * 1024)) + Math.abs(MIN_LOG)
  const max = 10
  if (log10 > max) log10 = max
  const hue = ((max - log10) / max) * 0.66
  return hsvToRgb(hue, 0.4, 1)
}
function hsvToRgb(h, sat, v) {
  const i = Math.floor(h * 6)
  const f = h * 6 - i
  const p = v * (1 - sat)
  const q = v * (1 - f * sat)
  const t = v * (1 - (1 - f) * sat)
  const [r, g, b] = [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i % 6]
  return '#' + [r, g, b].map((x) => Math.round(x * 255).toString(16).padStart(2, '0')).join('')
}

// init
getPublisherConfigNames()
serviceApi
  .get(route.params.serviceId)
  .then((result) => {
    service.value = result
    refreshExchanges(true)
    refreshSubscriberConfigNames()
    refreshPublisherMode()
  })
  .catch((e) => notify.error('Failed to retrieve service', e))
</script>

<style scoped>
.layout-cols {
  display: grid;
  grid-template-columns: minmax(380px, 4fr) 8fr;
  gap: 1rem;
  align-items: start;
}

.right-top {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 1rem;
}

@media (max-width: 1100px) {
  .layout-cols,
  .right-top {
    grid-template-columns: 1fr;
  }
}

.headers {
  margin: 0;
  padding-left: 1.2rem;
  word-break: break-all;
}

:deep(.clickable) {
  cursor: pointer;
}
</style>
