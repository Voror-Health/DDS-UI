<template>
  <div>
    <div class="page-header">
      <h1>SFTP Reader Status</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button v-if="admin" label="Add Feed" severity="success" size="small" @click="openAddFeed" />
        <Button label="Save to CSV" severity="secondary" size="small" @click="saveToCsv" />
        <Button v-if="admin" label="Toggle Pause All" severity="secondary" size="small" @click="togglePauseAll" />
        <Button label="ODS Search" severity="secondary" size="small" @click="odsVisible = true" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refreshScreen" />
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <h2 style="margin: 0">Application Instances</h2>
        <span class="spacer"></span>
        <span class="field-inline" v-for="o in appOptions" :key="o.key">
          <Checkbox v-model="f[o.key]" binary :inputId="o.key" /><label :for="o.key">{{ o.label }}</label>
        </span>
      </div>
      <div class="apps mt-1">
        <span v-for="(app, i) in applicationStatus || []" :key="i" class="app">
          {{ app.applicationInstanceName }}
          <Tag v-if="isDead(app)" severity="danger" v-tooltip="app.isBusyDetail">
            <i class="fa-solid fa-triangle-exclamation"></i>&nbsp;{{ getDateDiffDesc(new Date(app.timestmp), appLastRefreshed, 2) }}
          </Tag>
          <template v-else>
            <i v-if="app.dtJar && app.dtStarted && app.dtJar > app.dtStarted" class="fa-regular fa-clock"
               v-tooltip="'Jar built: ' + formatYYYYMMDDHHMM(app.dtJar) + ', app started: ' + formatYYYYMMDDHHMM(app.dtStarted)"></i>
            <i v-if="app.isBusy" class="fa-solid fa-play" v-tooltip="app.isBusyDetail"></i>
            <i v-else class="fa-solid fa-pause" v-tooltip="'Application running but idle'"></i>
            <span v-if="f.showExecutionTime && app.isBusySince" class="muted"> {{ getDateDiffDesc(new Date(app.isBusySince), new Date(app.timestmp), 1) }}</span>
            <span v-if="f.showMemory" class="muted"> [{{ app.currentHeapDesc }} / {{ app.maxHeapDesc }}]</span>
            <span v-if="f.showJarDate" class="muted"> [Jar {{ formatYYYYMMDDHHMMSS(app.dtJar) }}]</span>
            <span v-if="f.showStartDate" class="muted"> [Started {{ formatYYYYMMDDHHMMSS(app.dtStarted) }}]</span>
          </template>
        </span>
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <h2 style="margin: 0">Feed Status
          <span class="sub muted" v-if="configurations">(showing {{ toDisplay.length }} of {{ configurations.length }})</span>
        </h2>
        <span class="spacer"></span>
        <span class="field-inline"><Checkbox v-model="f.showWarningsOnly" binary inputId="wo" /><label for="wo">Show Warnings Only</label></span>
        <span class="field-inline"><Checkbox v-model="f.showDates" binary inputId="sd" /><label for="sd">Show Dates</label></span>
        <span class="field-inline"><Checkbox v-model="f.showRetired" binary inputId="sr" @change="refreshInstances(true)" /><label for="sr">Show Retired Feeds</label></span>
        <span class="field-inline">
          <label>Instance</label>
          <Select v-model="f.filterInstanceName" :options="[{ value: '', label: 'All' }, ...instanceNames.map((n) => ({ value: n, label: n }))]" optionLabel="label" optionValue="value" />
        </span>
      </div>
    </div>

    <div class="card">
      <div v-if="!configurations" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
      <DataTable v-else :value="toDisplay" size="small" stripedRows>
        <Column header="Configuration" style="width: 16%">
          <template #body="{ data }">
            <div>{{ data.configurationId }} <Tag v-if="data.retired" severity="secondary" value="Retired" /></div>
            <div class="muted">{{ data.friendlyName }}</div>
          </template>
        </Column>
        <Column field="instanceName" header="App Instance" />
        <Column header="Third Party → SFTP Reader" style="width: 33%">
          <template #body="{ data }">
            <i v-if="!statusMap[data.configurationId]" class="fa-solid fa-rotate spin"></i>
            <template v-else>
              <template v-for="s in [statusMap[data.configurationId]]" :key="data.configurationId">
                <div v-if="s.latestPollingStart">
                  <Tag v-if="isLastPollAttemptTooOld(s)" severity="danger" value="Warning" />
                  Last Checked for Data: {{ show(s.latestPollingStart) }}
                </div>
                <div v-else><Tag severity="danger" value="Warning" /> Last Checked for Data: NEVER</div>
                <div v-if="s.latestPollingException">
                  <Tag severity="danger" value="Warning" /> Error on last polling attempt:
                  <pre class="block" style="max-width: 450px">{{ s.latestPollingException }}</pre>
                </div>
                <template v-if="s.latestBatchId">
                  <div>
                    <Tag v-if="isLastExtractTooOld(s)" severity="danger" value="Warning" />
                    Last Extract Received: {{ show(s.latestBatchReceived) }}, Cutoff: {{ show(s.latestBatchExtractCutoff) }}
                  </div>
                  <div>Files Received: {{ s.latestBatchFileCount }}, Extract Size: {{ s.latestBatchSizeBytes }}, Valid &amp; Complete: {{ s.latestBatchComplete }}</div>
                </template>
                <div v-else><Tag severity="danger" value="Warning" /> Last Extract Received: NEVER</div>
              </template>
            </template>
          </template>
        </Column>
        <Column header="SFTP Reader → Messaging API" style="width: 33%">
          <template #body="{ data }">
            <i v-if="!statusMap[data.configurationId]" class="fa-solid fa-rotate spin"></i>
            <template v-else>
              <template v-for="s in [statusMap[data.configurationId]]" :key="data.configurationId">
                <div v-if="data.dtPaused"><Tag severity="warn" :value="'PAUSED ' + show(data.dtPaused)" /></div>
                <template v-if="s.completeBatchId">
                  <div>Posted to API: {{ show(s.completeBatchCompletionDate) }}</div>
                  <div>Posted Extract Received: {{ show(s.completeBatchReceived) }}, Cutoff: {{ show(s.completeBatchExtractCutoff) }}</div>
                  <div class="orgs">
                    <a @click="viewOrgs(s, s.completeBatchContents)">{{ s.completeBatchContents.length }} Orgs in extract</a>
                    <a @click="viewOrgs(s, s.okBatches)">{{ s.okBatches.length }} Orgs OK</a>
                    <span><Tag v-if="s.errorBatches.length > 0" severity="danger" value="Warning" /> <a @click="viewOrgs(s, s.errorBatches)">{{ s.errorBatches.length }} Orgs in Error</a></span>
                    <span><Tag v-if="s.dpaErrorBatches.length > 0" severity="danger" value="Warning" /> <a @click="viewOrgs(s, s.dpaErrorBatches)">{{ s.dpaErrorBatches.length }} DPA Failures</a></span>
                  </div>
                </template>
                <div v-else>No complete batches received to send to Messaging API</div>
              </template>
            </template>
          </template>
        </Column>
        <Column>
          <template #body="{ data }">
            <span class="hover-show nowrap">
              <Button label="View History" size="small" severity="secondary" outlined @click="openHistory(data)" />
              <Button v-if="admin" label="Toggle Pause" size="small" severity="secondary" outlined class="ml-1" @click="togglePause(data)" />
            </span>
            <span v-if="admin" class="hover-show nowrap feed-admin">
              <Button label="Edit" size="small" severity="secondary" outlined @click="openEditFeed(data)" />
              <Button v-if="!data.retired" label="Retire" size="small" severity="warn" outlined class="ml-1" @click="retire(data)" />
              <Button v-else label="Reinstate" size="small" severity="success" outlined class="ml-1" @click="openReinstate(data)" />
              <Button v-if="!data.hasBatches" label="Delete" size="small" severity="danger" outlined class="ml-1" @click="openDelete(data)" />
            </span>
          </template>
        </Column>
      </DataTable>
    </div>

    <InterfaceTypesSection :types="interfaceTypes" @added="refreshInterfaceTypes" @deleted="refreshInterfaceTypes" />

    <AddFeedDialog v-if="admin" v-model:visible="addFeedVisible" :interfaceTypes="interfaceTypes || []" :instanceNames="allInstanceNames"
                   :editConfigurationId="editConfigurationId" @added="onFeedAdded" @saved="onFeedAdded" />

    <!-- VHP: reinstate a retired feed -->
    <Dialog v-model:visible="reinstate.visible" modal :header="'Reinstate ' + reinstate.configurationId" :style="{ width: '30rem' }">
      <div class="field">
        <label for="ri-instance">Instance to run it</label>
        <Select inputId="ri-instance" v-model="reinstate.instanceName" :options="allInstanceNames" placeholder="Choose an instance" />
      </div>
      <p class="muted">It stays paused: use Toggle Pause when it should post to the messaging API again. Restart the instance for it to start polling.</p>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="reinstate.visible = false" />
        <Button label="Reinstate" :disabled="!reinstate.instanceName" :loading="reinstate.saving" @click="doReinstate" />
      </template>
    </Dialog>

    <!-- VHP: delete a feed that has never received a batch -->
    <Dialog v-model:visible="deleting.visible" modal header="Delete feed" :style="{ width: '34rem' }">
      <p>This permanently deletes feed <b>{{ deleting.configurationId }}</b> (its settings, keys, polling attempts and unknown-file records).
        It has never received a batch. This can't be undone.</p>
      <div class="field">
        <label for="del-confirm">Type the configuration id to confirm</label>
        <InputText id="del-confirm" v-model="deleting.typed" autocomplete="off" />
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="deleting.visible = false" />
        <Button label="Delete" severity="danger" :disabled="deleting.typed !== deleting.configurationId" :loading="deleting.saving" @click="doDelete" />
      </template>
    </Dialog>

    <!-- history -->
    <Dialog v-model:visible="history.visible" modal :header="history.config?.friendlyName" :style="{ width: '70rem' }">
      <div class="field-inline mb-1">
        <label>Received between</label>
        <InputText type="date" v-model="history.from" />
        <label>and</label>
        <InputText type="date" v-model="history.to" />
        <Button label="Search" @click="searchHistory" :loading="history.searching" />
      </div>
      <DataTable :value="history.results || []" size="small" stripedRows scrollable scrollHeight="55vh">
        <Column header="Received"><template #body="{ data }">{{ formatYYYYMMDDHHMM(data.received) }}</template></Column>
        <Column header="Extract Cutoff"><template #body="{ data }">{{ formatYYYYMMDDHHMM(data.extractCutoff) }}</template></Column>
        <Column field="sequenceNumber" header="Sequence#" />
        <Column field="fileCount" header="Files" />
        <Column field="sizeDesc" header="Size" />
        <Column header="Organisations">
          <template #body="{ data }">
            <span class="orgs">
              <a @click="openOrgs(history.config.configurationId, data.id, data.batchContents)">{{ data.batchContents.length }} Orgs in extract</a>
              <a @click="openOrgs(history.config.configurationId, data.id, data.batchContents.filter((c) => c.notified))">{{ data.batchContents.filter((c) => c.notified).length }} Orgs OK</a>
              <a @click="openOrgs(history.config.configurationId, data.id, data.batchContents.filter((c) => !c.notified))">{{ data.batchContents.filter((c) => !c.notified).length }} Orgs in Error</a>
            </span>
          </template>
        </Column>
      </DataTable>
      <template #footer><Button label="Close" @click="history.visible = false" /></template>
    </Dialog>

    <SftpReaderOrgsDialog v-model:visible="orgs.visible" :configurationId="orgs.configurationId" :batchId="orgs.batchId" :contents="orgs.contents" />
    <OdsSearchDialog v-model:visible="odsVisible" />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { sftpReaderApi, queueReaderApi } from '@/services/api'
import { saveFile } from '@/services/http'
import { formatYYYYMMDD, formatYYYYMMDDHHMM, formatYYYYMMDDHHMMSS, getDateDiffDesc, getDateDiffSimple, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify, useConfirmAsync, orderBy, csvLine, isAdmin } from '@/utils/ui'
import OdsSearchDialog from '@/components/OdsSearchDialog.vue'
import SftpReaderOrgsDialog from '@/components/SftpReaderOrgsDialog.vue'
import InterfaceTypesSection from '@/components/InterfaceTypesSection.vue'
import AddFeedDialog from '@/components/AddFeedDialog.vue'

const f = reactive(
  (window.__ddsSftpReaderFilters ||= {
    showWarningsOnly: false, showDates: false, filterInstanceName: '', showRetired: false,
    showExecutionTime: true, showMemory: false, showJarDate: false, showStartDate: false,
  }),
)
const appOptions = [
  { key: 'showExecutionTime', label: 'Execution Time' },
  { key: 'showMemory', label: 'Memory (Current/Max)' },
  { key: 'showJarDate', label: 'Jar Date' },
  { key: 'showStartDate', label: 'Start Date' },
]

const notify = useNotify()
const configurations = ref(null)
const statusMap = reactive({})
const statusLastRefreshed = ref(new Date())
const applicationStatus = ref(null)
const appLastRefreshed = ref(new Date())
const odsVisible = ref(false)

const instanceNames = computed(() => [...new Set((configurations.value || []).map((c) => c.instanceName))].sort())

const toDisplay = computed(() =>
  (configurations.value || []).filter((c) => {
    if (f.filterInstanceName && f.filterInstanceName !== c.instanceName) return false
    if (f.showWarningsOnly) {
      const s = statusMap[c.configurationId]
      if (s && !s.warning) return false
    }
    return true
  }),
)

const show = (ms) => (f.showDates ? formatYYYYMMDDHHMM(ms) : getDateDiffSimple(ms, statusLastRefreshed.value))
const isDead = (app) => app.timestmp < appLastRefreshed.value.getTime() - 1000 * 60 * 2

// twice the polling frequency is a fair window
function isLastPollAttemptTooOld(s) {
  if (!s.latestPollingStart) return true
  return statusLastRefreshed.value.getTime() - s.latestPollingStart > s.pollFrequencySeconds * 1000 * 2
}

// 1.5 times the expected data frequency
function isLastExtractTooOld(s) {
  if (!s.latestBatchReceived) return true
  return statusLastRefreshed.value.getTime() - s.latestBatchReceived > s.dataFrequencyDays * 24 * 60 * 60 * 1000 * 1.5
}

function categorise(s) {
  s.completeBatchContents ||= []
  s.okBatches = []
  s.dpaErrorBatches = []
  s.errorBatches = []
  for (const c of s.completeBatchContents) {
    if (c.notified) s.okBatches.push(c)
    else if (c.error && (c.error.indexOf('No DPA found') > -1 || c.error.indexOf('no DPA exists') > -1)) s.dpaErrorBatches.push(c)
    else s.errorBatches.push(c)
  }
  s.warning = !!(isLastPollAttemptTooOld(s) || !s.latestPollingStart || s.latestPollingException || isLastExtractTooOld(s) ||
    !s.latestBatchId || s.errorBatches.length > 0 || s.dpaErrorBatches.length > 0)
}

async function refreshInstances(fullRefresh) {
  if (fullRefresh) {
    configurations.value = null
    for (const k of Object.keys(statusMap)) delete statusMap[k]
  }
  try {
    const result = orderBy(await sftpReaderApi.getInstances(!!f.showRetired), (c) => c.configurationId)
    configurations.value = result
    if (fullRefresh) {
      statusLastRefreshed.value = new Date()
      for (const c of result) {
        sftpReaderApi
          .getStatus(c.configurationId)
          .then((s) => {
            categorise(s)
            statusMap[c.configurationId] = s
          })
          .catch((e) => notify.error('Failed to get status for ' + c.configurationId, e))
      }
    }
  } catch (e) {
    notify.error('Failed get SFTP Reader instances', e)
  }
}

async function refreshApplicationStatus() {
  appLastRefreshed.value = new Date()
  try {
    const r = await queueReaderApi.getStatus('sftpreader')
    applicationStatus.value = orderBy(orderBy(r, (s) => s.applicationInstanceNumber), (s) => s.applicationInstanceName)
  } catch (e) {
    notify.error('Failed get application status', e)
  }
}

// VHP: Add Feed and the Interface Types section
const admin = isAdmin()
const addFeedVisible = ref(false)
const editConfigurationId = ref(null)
const confirmAsync = useConfirmAsync()

function openAddFeed() {
  editConfigurationId.value = null
  addFeedVisible.value = true
}

function openEditFeed(c) {
  editConfigurationId.value = c.configurationId
  addFeedVisible.value = true
}

// retire: move to the NOT_USED instance and pause notifying; history is kept, and it can be reinstated
async function retire(c) {
  const ok = await confirmAsync(
    `Retire feed ${c.configurationId}? It is moved off instance ${c.instanceName} and paused, so nothing more is posted to the messaging API. ` +
      `Its history is kept and it can be reinstated. Restart vhp-sftpreader#${c.instanceName} so it stops polling the feed.`,
    'Retire feed', 'Retire', true)
  if (!ok) return
  try {
    const r = await sftpReaderApi.retireFeed(c.configurationId)
    notify.success('Feed retired', c.configurationId)
    if (r.previousInstanceName) {
      notify.warning('Restart the SFTP Reader', `Restart vhp-sftpreader#${r.previousInstanceName} so it stops polling ${c.configurationId}.`)
    }
    refreshInstances(true)
  } catch (e) {
    notify.error('Failed to retire feed', e)
  }
}

const reinstate = reactive({ visible: false, configurationId: null, instanceName: null, saving: false })
function openReinstate(c) {
  Object.assign(reinstate, { visible: true, configurationId: c.configurationId, instanceName: null, saving: false })
}
async function doReinstate() {
  reinstate.saving = true
  try {
    const r = await sftpReaderApi.reinstateFeed(reinstate.configurationId, reinstate.instanceName)
    notify.success('Feed reinstated', `${r.configurationId} on instance ${r.instanceName} (still paused)`)
    notify.warning('Restart the SFTP Reader', `Restart vhp-sftpreader#${r.instanceName} for it to start polling ${r.configurationId}.`)
    reinstate.visible = false
    refreshInstances(true)
  } catch (e) {
    notify.error('Failed to reinstate feed', e)
  } finally {
    reinstate.saving = false
  }
}

const deleting = reactive({ visible: false, configurationId: null, typed: '', saving: false })
function openDelete(c) {
  Object.assign(deleting, { visible: true, configurationId: c.configurationId, typed: '', saving: false })
}
async function doDelete() {
  deleting.saving = true
  try {
    await sftpReaderApi.deleteFeed(deleting.configurationId)
    notify.success('Feed deleted', deleting.configurationId)
    deleting.visible = false
    refreshInstances(true)
  } catch (e) {
    notify.error('Failed to delete feed', e)
  } finally {
    deleting.saving = false
  }
}
const interfaceTypes = ref(null)
const registeredInstances = ref([])
// every instance row (some may have no feed yet), without the special "NOT_USED" instance
const allInstanceNames = computed(() =>
  [...new Set([...registeredInstances.value, ...instanceNames.value])].filter((n) => n !== 'NOT_USED').sort())

async function refreshInterfaceTypes() {
  try {
    interfaceTypes.value = await sftpReaderApi.getInterfaceTypes()
  } catch (e) {
    interfaceTypes.value = []
    notify.error('Failed to get SFTP Reader interface types', e)
  }
}

async function refreshInstanceNames() {
  if (!admin) return
  try {
    registeredInstances.value = await sftpReaderApi.getInstanceNames()
  } catch (e) {
    notify.error('Failed to get SFTP Reader instance names', e)
  }
}

function onFeedAdded() {
  refreshInstances(true)
  refreshInstanceNames()
}

function refreshScreen() {
  refreshInstances(true)
  refreshApplicationStatus()
  refreshInterfaceTypes()
  refreshInstanceNames()
}

async function togglePauseAll() {
  try {
    await sftpReaderApi.togglePauseAll()
    refreshInstances(false)
  } catch (e) {
    notify.error('Failed to toggle pause', e)
  }
}

async function togglePause(c) {
  try {
    await sftpReaderApi.togglePause(c.configurationId)
    refreshInstances(false)
  } catch (e) {
    notify.error('Failed to toggle pause', e)
  }
}

const orgs = reactive({ visible: false, configurationId: null, batchId: null, contents: [] })
function openOrgs(configurationId, batchId, contents) {
  Object.assign(orgs, { visible: true, configurationId, batchId, contents })
}
const viewOrgs = (status, contents) => openOrgs(status.id, status.completeBatchId, contents)

// history: received in the last 7 days by default
const history = reactive({ visible: false, config: null, from: '', to: '', results: null, searching: false })
function openHistory(config) {
  const d = new Date()
  d.setDate(d.getDate() - 7)
  Object.assign(history, { visible: true, config, from: formatYYYYMMDD(d), to: '', results: null })
}
async function searchHistory() {
  history.searching = true
  try {
    const from = history.from ? new Date(history.from) : new Date('1900-01-01')
    const to = history.to ? new Date(history.to) : new Date('9999-12-31')
    history.results = await sftpReaderApi.getHistory(history.config.configurationId, from, to)
  } catch (e) {
    notify.error('Error searching', e)
  } finally {
    history.searching = false
  }
}

function saveToCsv() {
  const fd = (ms) => formatYYYYMMDDHHMM(ms)
  const lines = [csvLine(['Configuration ID', 'Configuration Name', 'Last Checked for Data', 'Error Checking', 'Last Extract Received',
    'Data From', 'Data Cutoff', 'Files Received', 'Extract Size', 'Valid and Complete', 'Orgs in Extract'])]
  for (const c of toDisplay.value) {
    const s = statusMap[c.configurationId]
    if (!s) {
      notify.error('Cannot save while loading')
      return
    }
    lines.push(csvLine([
      c.configurationId, c.friendlyName, s.latestPollingStart ? fd(s.latestPollingStart) : 'Never', s.latestPollingException ? 'Y' : 'N',
      s.latestBatchReceived ? fd(s.latestBatchReceived) : 'Never', s.latestBatchIdentifier || '',
      s.latestBatchExtractCutoff ? fd(s.latestBatchExtractCutoff) : '', s.latestBatchFileCount || '', s.latestBatchSizeBytes || '',
      s.latestBatchComplete ? 'Y' : 'N', s.completeBatchContents ? s.completeBatchContents.length : 'Unknown',
    ]))
  }
  saveFile(lines.join('\r\n'), 'SFTP_status.csv', 'text/plain')
}

refreshScreen()
</script>

<style scoped>
.apps {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1rem;
}

.app {
  background: #e5e7e9;
  padding: 0.2rem 0.5rem;
  border-radius: 4px;
}

.orgs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.25rem 1rem;
}
</style>
