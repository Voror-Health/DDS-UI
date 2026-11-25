<template>
  <div>
    <div class="page-header">
      <h1>Remote Filing Status</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refresh" />
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <h2 style="margin: 0">Remote Feed Status
          <span class="sub muted" v-if="remoteFilers">(showing {{ toDisplay.length }} of {{ remoteFilers.length }})</span>
        </h2>
        <span class="spacer"></span>
        <span class="field-inline"><Checkbox v-model="f.showWarningsOnly" binary inputId="wo" /><label for="wo">Show Warnings Only</label></span>
        <span class="field-inline"><Checkbox v-model="f.showDates" binary inputId="sd" /><label for="sd">Show Dates</label></span>
        <span class="field-inline">
          <label>Sort By</label>
          <Select v-model="f.sortByFilter" :options="[{ value: 'name', label: 'Name' }, { value: 'id', label: 'ID' }]" optionLabel="label" optionValue="value" />
        </span>
      </div>
    </div>

    <div class="card">
      <div v-if="!remoteFilers" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
      <DataTable v-else :value="toDisplay" size="small" stripedRows>
        <Column header="Subscriber Configuration" style="width: 25%">
          <template #body="{ data }">
            <div>{{ data.name }}</div>
            <div class="muted">{{ data.description }}</div>
          </template>
        </Column>
        <Column field="subscriberId" header="Subscriber ID" />
        <Column header="Queued in Database">
          <template #body="{ data }">
            <i v-if="!statusMap[data.subscriberId]" class="fa-solid fa-rotate spin"></i>
            <template v-else>
              <div>{{ statusMap[data.subscriberId].countQueuedMessages }}</div>
              <template v-if="statusMap[data.subscriberId].oldestQueuedMessage">
                <div>Oldest: {{ show(statusMap[data.subscriberId].oldestQueuedMessage) }}
                  <Tag v-if="statusMap[data.subscriberId].queuedMessageWarning" severity="danger" value="Warning"
                       v-tooltip="statusMap[data.subscriberId].queuedMessageWarning" />
                </div>
                <div>Newest: {{ show(statusMap[data.subscriberId].newestQueuedMessage) }}</div>
              </template>
            </template>
          </template>
        </Column>
        <Column header="Written to SFTP / Sent">
          <template #body="{ data }">
            <i v-if="!statusMap[data.subscriberId]" class="fa-solid fa-rotate spin"></i>
            <template v-else>
              <div>{{ statusMap[data.subscriberId].countMessagesWrittenToSftp }}</div>
              <template v-if="statusMap[data.subscriberId].oldestMessageWrittenToSftp">
                <div>Oldest: {{ show(statusMap[data.subscriberId].oldestMessageWrittenToSftp) }}
                  <Tag v-if="statusMap[data.subscriberId].writtenToSftpWarning" severity="danger" value="Warning"
                       v-tooltip="statusMap[data.subscriberId].writtenToSftpWarning" />
                </div>
                <div>Newest: {{ show(statusMap[data.subscriberId].newestMessageWrittenToSftp) }}</div>
              </template>
            </template>
          </template>
        </Column>
        <Column header="Subscriber Error">
          <template #body="{ data }">
            <i v-if="!statusMap[data.subscriberId]" class="fa-solid fa-rotate spin"></i>
            <template v-else>
              <div>{{ statusMap[data.subscriberId].countMessagesInError }}
                <Tag v-if="statusMap[data.subscriberId].countMessagesInError > 0" severity="danger" value="Warning"
                     v-tooltip="statusMap[data.subscriberId].messagesInErrorWarning" />
              </div>
              <template v-if="statusMap[data.subscriberId].oldestMessageInError">
                <div>Oldest: {{ formatYYYYMMDDHHMM(statusMap[data.subscriberId].oldestMessageInError) }}</div>
                <div>Newest: {{ formatYYYYMMDDHHMM(statusMap[data.subscriberId].newestMessageInError) }}</div>
              </template>
            </template>
          </template>
        </Column>
        <Column>
          <template #body="{ data }">
            <span v-if="statusMap[data.subscriberId]" class="hover-show nowrap">
              <Button label="Technical Details" size="small" severity="secondary" outlined @click="viewTechnicalDetails(data)" />
              <Button v-if="statusMap[data.subscriberId].countMessagesInError > 0" label="View Errors" size="small"
                      severity="secondary" outlined class="ml-1" @click="viewErrors(data)" />
            </span>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- errors -->
    <Dialog v-model:visible="errorsVisible" modal :header="errorsFiler ? `Errors for ${errorsFiler.name} (ID ${errorsFiler.subscriberId})` : ''"
            :style="{ width: '75rem' }">
      <Tag v-if="errorsLimited" severity="warn" value="Only most recent 50 errors are shown" class="mb-1" />
      <DataTable :value="errors" size="small" stripedRows scrollable scrollHeight="60vh">
        <Column header="Publisher">
          <template #body="{ data }"><span v-tooltip="data.serviceName">{{ data.serviceOdsCode }}</span></template>
        </Column>
        <Column field="queuedMessageId" header="Queued Message ID" />
        <Column header="Filing Attempted">
          <template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMMSS(data.dtFileFilingAttempted) }}</span></template>
        </Column>
        <Column field="filingFailureMessage" header="Error Message" />
        <Column>
          <template #body="{ data }">
            <Button label="Download" size="small" severity="secondary" outlined class="hover-show" @click="downloadMessage(data)" />
          </template>
        </Column>
      </DataTable>
      <template #footer><Button label="Close" @click="errorsVisible = false" /></template>
    </Dialog>

    <!-- technical details -->
    <Dialog v-model:visible="techVisible" modal :header="techFiler ? `Technical Details for ${techFiler.name} (ID ${techFiler.subscriberId})` : ''"
            :style="{ width: '55rem' }">
      <template v-if="techFiler">
        <h2>File Paths</h2>
        <table class="simple mb-1">
          <tbody>
            <tr v-for="[label, key] in filePaths" :key="key">
              <th style="width: 14rem">{{ label }}</th>
              <td class="mono">{{ techFiler.technicalDetails?.subscriberFileLocationDetails?.[key] }}</td>
            </tr>
          </tbody>
        </table>
        <h2>SFTP Server Details</h2>
        <table class="simple">
          <tbody>
            <tr v-for="[label, key] in sftpDetails" :key="key">
              <th style="width: 14rem">{{ label }}</th>
              <td class="mono" style="word-break: break-all">{{ techFiler.technicalDetails?.sftpConnectionDetails?.[key] }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <template #footer><Button label="Close" @click="techVisible = false" /></template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { remoteFilingApi } from '@/services/api'
import { formatYYYYMMDDHHMM, formatYYYYMMDDHHMMSS, getDateDiffSimple, getDateDiffSimpleFromNow, getDateDiffDescMs } from '@/utils/format'
import { useNotify, orderBy } from '@/utils/ui'

const f = reactive((window.__ddsRemoteFilingFilters ||= { showWarningsOnly: false, showDates: false, sortByFilter: 'name' }))
const notify = useNotify()
const remoteFilers = ref(null)
const statusMap = reactive({})
const statusLastRefreshed = ref(null)
const DAY = 1000 * 60 * 60 * 24

const filePaths = [
  ['Data', 'dataDir'], ['Staging', 'stagingDir'], ['Archive', 'archiveDir'], ['SFTP Server Destination', 'destinationDir'],
  ['PGP certificate', 'pgpCertFile'], ['Private key', 'privateKeyFile'], ['SFTP Server Results Source', 'resultsSourceDir'],
  ['Results staging', 'resultsStagingDir'],
]
const sftpDetails = [
  ['Host name', 'hostname'], ['Port', 'port'], ['Username', 'username'], ['Public key', 'hostPublicKey'], ['Private key', 'clientPrivateKey'],
]

const toDisplay = computed(() => {
  const list = (remoteFilers.value || []).filter((filer) => {
    const status = statusMap[filer.subscriberId]
    return !f.showWarningsOnly || !status || status.queuedMessageWarning || status.writtenToSftpWarning || status.messagesInErrorWarning
  })
  return f.sortByFilter === 'id' ? orderBy(list, (x) => x.subscriberId) : orderBy(list, (x) => x.name)
})

function show(ms) {
  return f.showDates ? formatYYYYMMDDHHMM(ms) : getDateDiffSimple(ms, statusLastRefreshed.value)
}

function calculateWarnings(r) {
  const now = statusLastRefreshed.value.getTime()
  if (r.oldestQueuedMessage && now - r.oldestQueuedMessage > DAY) {
    r.queuedMessageWarning = 'Queued messages not written to SFTP for ' + getDateDiffDescMs(r.oldestQueuedMessage, now, 2)
  }
  if (r.oldestMessageWrittenToSftp && now - r.oldestMessageWrittenToSftp > DAY * 2) {
    r.writtenToSftpWarning = 'Messages not collected or fed back for ' + getDateDiffDescMs(r.oldestMessageWrittenToSftp, now, 2)
  }
  if (r.countMessagesInError) r.messagesInErrorWarning = r.countMessagesInError + ' messages in error'
}

async function refresh() {
  try {
    const filers = orderBy(await remoteFilingApi.getRemoteFilers(), (x) => x.name)
    statusLastRefreshed.value = new Date()
    for (const k of Object.keys(statusMap)) delete statusMap[k]
    remoteFilers.value = filers
    for (const filer of filers) {
      remoteFilingApi
        .getRemoteFilerStatus(filer.subscriberId)
        .then((result) => {
          calculateWarnings(result)
          statusMap[filer.subscriberId] = result
        })
        .catch((e) => notify.error('Failed to get status for subscriber ' + filer.subscriberId, e))
    }
  } catch (e) {
    notify.error('Failed to get remote filer details', e)
  }
}

// errors dialog (most recent 50)
const errorsVisible = ref(false)
const errorsFiler = ref(null)
const errors = ref([])
const errorsLimited = ref(false)
function viewErrors(filer) {
  const all = statusMap[filer.subscriberId].errors || []
  errorsLimited.value = all.length > 50
  errors.value = errorsLimited.value ? orderBy(all, (e) => e.dtInserted, true).slice(0, 50) : all
  errorsFiler.value = filer
  errorsVisible.value = true
}

async function downloadMessage(error) {
  try {
    const data = await remoteFilingApi.getQueuedMessage(error.queuedMessageId)
    const a = document.createElement('a')
    a.href = 'data:application/zip;base64,' + data
    a.download = error.queuedMessageId + '.zip'
    document.body.appendChild(a)
    a.click()
    a.remove()
  } catch (e) {
    notify.error('Failed to get queued message data', e)
  }
}

const techVisible = ref(false)
const techFiler = ref(null)
function viewTechnicalDetails(filer) {
  if (!filer.technicalDetails) {
    notify.error('No technical details found')
    return
  }
  techFiler.value = filer
  techVisible.value = true
}

refresh()
</script>
