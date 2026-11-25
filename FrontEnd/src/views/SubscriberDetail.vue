<template>
  <div>
    <div class="page-header">
      <h1>{{ subscriberName }} details</h1>
      <span class="sub" v-if="statusLastRefreshed">as of {{ formatHHMMSS(statusLastRefreshed) }}</span>
      <span class="sub" v-if="status">({{ filtered.length }} shown of {{ status.publisherServices.length }})</span>
      <div class="actions">
        <Button label="Save to CSV" severity="secondary" size="small" :disabled="refreshing || !status" @click="saveToCsv" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refresh" />
        <Button label="Close" severity="secondary" size="small" @click="$router.push('/subscribers')" />
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <div class="field" style="width: 14rem">
          <label>Name/ODS</label>
          <InputText v-model="f.publisherNameFilter" placeholder="Regex" />
        </div>
        <div class="field" style="width: 12rem">
          <label>System</label>
          <Select v-model="f.systemNameFilter" :options="systemNames" showClear placeholder=" " />
        </div>
        <div class="field" style="width: 13rem">
          <label>State</label>
          <Select v-model="f.statusFilter" :options="statusOptions" optionLabel="label" optionValue="value" showClear placeholder=" " />
        </div>
        <div class="field" style="width: 12rem">
          <label>Sorting</label>
          <Select v-model="f.sortFilter" :options="sortOptions" optionLabel="label" optionValue="value" />
        </div>
        <div class="field">
          <span class="field-inline"><Checkbox v-model="f.showDates" binary inputId="sd" /><label for="sd">Show Dates</label></span>
          <span class="field-inline"><Checkbox v-model="f.showFullSubscriberConfigDetails" binary inputId="sc" /><label for="sc">Show Subscriber Configuration</label></span>
        </div>
      </div>
    </div>

    <div class="card" v-if="f.showFullSubscriberConfigDetails && status">
      <div class="config-grid">
        <ReadOnly label="Name" :value="status.name" />
        <ReadOnly label="Description" :value="status.description" class="span-3" />
        <ReadOnly label="Location" :value="status.subscriberLocation" />
        <ReadOnly label="Remote Subscriber ID" :value="status.remoteSubscriberId" />
        <ReadOnly label="Schema" :value="status.schema" />
        <ReadOnly label="Cohort" :value="status.cohortDesc" />
        <ReadOnly label="PI" :value="!status.deidentified" />
        <ReadOnly label="Excludes Test Patients" :value="status.excludeTestPatients" />
        <ReadOnly label="Exclude Records Without NHS Number" :value="status.excludePatientsWithoutNhsNumber" />
        <ReadOnly label="Exclude NHS Numbers Regex" :value="status.excludeNhsNumberRegex" />
        <ReadOnly label="Subscriber Database" :value="status.subscriberDatabase" />
        <ReadOnly label="Subscriber Database Name" :value="status.subscriberDatabaseName" />
        <ReadOnly label="Subscriber Transform Database" :value="status.subscriberTransformDatabase" />
        <ReadOnly label="Subscriber Transform Database Name" :value="status.subscriberTransformDatabaseName" />
      </div>
    </div>

    <div class="card">
      <div v-if="!status" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
      <DataTable v-else :value="filtered" size="small" stripedRows paginator :rows="100" :rowsPerPageOptions="[50, 100, 500, 2000]">
        <Column header="Publisher" style="width: 22%">
          <template #body="{ data }">
            <a @click="$router.push(`/serviceEdit/edit/${data.uuid}`)">{{ data.name }}</a>
            <i v-if="data.alias" class="fa-solid fa-circle-info muted ml-1" v-tooltip="data.alias"></i>
          </template>
        </Column>
        <Column field="odsCode" header="ODS Code" />
        <Column header="System">
          <template #body="{ data }"><div v-for="(s, i) in data.systemStatus" :key="i">{{ s.name }}</div></template>
        </Column>
        <Column header="Last Received">
          <template #body="{ data }">
            <div v-for="(s, i) in data.systemStatus" :key="i" v-tooltip="'Extract received ' + formatYYYYMMDDHHMM(s.lastReceivedTimestamp)">
              {{ show(s.lastReceivedExtractCutoff) }}
            </div>
          </template>
        </Column>
        <Column header="Last Data In">
          <template #body="{ data }">
            <div v-for="(s, i) in data.systemStatus" :key="i">
              <span v-tooltip="'Transform completed ' + formatYYYYMMDDHHMM(s.lastProcessedInTimestamp)">{{ show(s.lastProcessedInExtractCutoff) }}</span>
              <Tag v-if="s.processingInError" severity="danger" value="Error" class="ml-1" v-tooltip="'Inbound error: ' + s.processingInErrorMessage" />
              <BehindTag :days="s.inboundBehindDays" :text="s.inboundBehindWarning" class="ml-1" />
            </div>
          </template>
        </Column>
        <Column header="Last Data Out">
          <template #body="{ data }">
            <div v-for="(s, i) in data.systemStatus" :key="i">
              <span v-tooltip="'Transform completed ' + formatYYYYMMDDHHMM(s.lastProcessedOutTimestamp)">{{ show(s.lastProcessedOutExtractCutoff) }}</span>
              <BehindTag :days="s.outboundBehindDays" :text="s.outboundBehindWarning" class="ml-1" />
            </div>
          </template>
        </Column>
        <Column header="Last Data Written">
          <template #body="{ data }">
            <div v-for="(s, i) in data.systemStatus" :key="i">
              <span v-tooltip="'Transform completed ' + formatYYYYMMDDHHMM(s.lastWriteOutTimestamp)">{{ show(s.lastWriteOutExtractCutoff) }}</span>
              <Tag v-if="s.writeOutError" severity="danger" value="Error" class="ml-1" v-tooltip="'Outbound write error: ' + s.writeOutErrorMessage" />
              <BehindTag :days="s.outboundWriteBehindDays" :text="s.outboundWriteBehindWarning" class="ml-1" />
            </div>
          </template>
        </Column>
        <Column>
          <template #body="{ data }">
            <span class="hover-show nowrap">
              <Button label="Exchanges" size="small" severity="secondary" outlined @click="viewExchanges(data.uuid)" />
              <Button label="Status" size="small" severity="secondary" outlined class="ml-1" @click="openStatus(data)" />
            </span>
          </template>
        </Column>
      </DataTable>
    </div>

    <PublisherStatusDialog v-model:visible="statusVisible" :publisher="statusPublisher" />
    <SystemPickerDialog v-model:visible="nav.pickerVisible.value" :service="nav.pickerService.value"
                        @select="nav.onSystemPicked" />
  </div>
</template>

<script setup>
import { computed, h, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { subscriberApi, serviceApi } from '@/services/api'
import { saveFile } from '@/services/http'
import { getTagNames } from '@/services/serviceStore'
import { formatHHMMSS, formatYYYYMMDDHHMM, getDateDiffDesc, getDateDiffDescMs } from '@/utils/format'
import { useNotify, orderBy, csvLine } from '@/utils/ui'
import { useServiceNavigation } from '@/utils/navigation'
import BehindTag from '@/components/BehindTag.vue'
import PublisherStatusDialog from '@/components/PublisherStatusDialog.vue'
import SystemPickerDialog from '@/components/SystemPickerDialog.vue'

const ReadOnly = (props) =>
  h('div', { class: 'field' }, [h('label', props.label), h('input', { class: 'p-inputtext p-component', disabled: true, value: props.value ?? '' })])
ReadOnly.props = ['label', 'value']

// filters persist for the session (eds-ui kept them in SubscribersService)
const f = reactive(
  (window.__ddsSubscriberFilters ||= {
    showDates: false,
    publisherNameFilter: '',
    systemNameFilter: null,
    sortFilter: 'NameAsc',
    statusFilter: 'any-issue',
    showFullSubscriberConfigDetails: false,
  }),
)

const route = useRoute()
const notify = useNotify()
const nav = useServiceNavigation()
const subscriberName = route.params.subscriberName
const status = ref(null)
const refreshing = ref(false)
const statusLastRefreshed = ref(null)
const statusVisible = ref(false)
const statusPublisher = ref(null)
const DAY = 1000 * 60 * 60 * 24

const statusOptions = [
  { value: 'up-to-date', label: 'Up-to-date and OK' },
  { value: 'inbound-error', label: 'Inbound Error' },
  { value: 'any-behind', label: 'Behind' },
  { value: 'severe-behind', label: 'Severely Behind' },
  { value: 'any-issue', label: 'Any Issue' },
]
const sortOptions = [
  ['NameAsc', 'Name Asc'], ['NameDesc', 'Name Desc'], ['IDAsc', 'ID Asc'], ['IDDesc', 'ID Desc'],
  ['LastDataAsc', 'Last Data Asc'], ['LastDataDesc', 'Last Data Desc'],
].map(([value, label]) => ({ value, label }))

const systemNames = computed(() => {
  const set = new Set()
  for (const p of status.value?.publisherServices || []) for (const s of p.systemStatus || []) set.add(s.name)
  return orderBy([...set], (s) => (s || '').toLowerCase())
})

function show(ms) {
  if (f.showDates) return formatYYYYMMDDHHMM(ms)
  return ms ? getDateDiffDesc(new Date(ms), new Date(), 2) : 'n/a'
}

function behind(received, done, label, noneText) {
  if (!done) return [999, noneText]
  return [(received - done) / DAY, `${label} ${getDateDiffDescMs(done, received, 2)} behind`]
}

function calculateWarnings() {
  for (const p of status.value.publisherServices || []) {
    for (const s of p.systemStatus || []) {
      s.inboundBehindDays = 0
      s.outboundBehindDays = 0
      s.outboundWriteBehindDays = 0
      s.inboundBehindWarning = s.outboundBehindWarning = s.outboundWriteBehindWarning = null
      if (s.lastReceivedExtractCutoff) {
        ;[s.inboundBehindDays, s.inboundBehindWarning] = behind(s.lastReceivedExtractCutoff, s.lastProcessedInExtractCutoff, 'Inbound transform', 'No inbound transform complete')
        ;[s.outboundBehindDays, s.outboundBehindWarning] = behind(s.lastReceivedExtractCutoff, s.lastProcessedOutExtractCutoff, 'Outbound transform', 'No outbound transform complete')
        ;[s.outboundWriteBehindDays, s.outboundWriteBehindWarning] = behind(s.lastReceivedExtractCutoff, s.lastWriteOutExtractCutoff, 'Outbound write', 'No outbound writing complete')
      }
    }
  }
}

const STATUS_TESTS = {
  'up-to-date': (s) => !s.processingInError && s.inboundBehindDays == 0 && s.outboundBehindDays == 0 && s.outboundWriteBehindDays == 0,
  'inbound-error': (s) => s.processingInError,
  'any-behind': (s) => s.inboundBehindDays > 0 || s.outboundBehindDays > 0 || s.outboundWriteBehindDays > 0,
  'severe-behind': (s) => s.inboundBehindDays > 1 || s.outboundBehindDays > 1 || s.outboundWriteBehindDays > 1,
  'any-issue': (s) => s.processingInError || s.inboundBehindDays > 0 || s.outboundBehindDays > 0 || s.outboundWriteBehindDays > 0,
}

function lastDataCutoff(p) {
  let ret
  for (const s of p.systemStatus || []) {
    if (s.lastReceivedExtractCutoff && (!ret || s.lastReceivedExtractCutoff < ret)) ret = s.lastReceivedExtractCutoff
  }
  return ret || 0
}

const filtered = computed(() => {
  if (!status.value) return []
  let regex
  if (f.publisherNameFilter) {
    try {
      new RegExp(f.publisherNameFilter)
      regex = f.publisherNameFilter.toLowerCase().trim()
    } catch {
      /* ignore */
    }
  }
  const test = f.statusFilter ? STATUS_TESTS[f.statusFilter] : null
  let out = (status.value.publisherServices || []).filter((p) => {
    if (test && !(p.systemStatus || []).some(test)) return false
    if (regex) {
      const lc = (x) => (x ? x.toLowerCase() : null)
      let ok = false
      if (lc(p.name)?.match(regex)) ok = true
      if (lc(p.alias)?.match(regex)) ok = true
      else if (lc(p.odsCode)?.match(regex)) ok = true
      else if (lc(p.uuid) === regex) ok = true
      if (!ok) return false
    }
    if (f.systemNameFilter && !(p.systemStatus || []).some((s) => s.name === f.systemNameFilter)) return false
    return true
  })
  out = orderBy(out, (p) => (p.name || '').toLowerCase())
  switch (f.sortFilter) {
    case 'NameDesc': return out.reverse()
    case 'IDAsc': return orderBy(out, (p) => p.odsCode || '')
    case 'IDDesc': return orderBy(out, (p) => p.odsCode || '').reverse()
    case 'LastDataAsc': return orderBy(out, lastDataCutoff).reverse()
    case 'LastDataDesc': return orderBy(out, lastDataCutoff)
    default: return out
  }
})

async function refresh() {
  statusLastRefreshed.value = new Date()
  refreshing.value = true
  try {
    const result = await subscriberApi.getSubscriberDetails(subscriberName)
    status.value = result
    calculateWarnings()
  } catch (e) {
    notify.error('Failed get subscriber details', e)
  } finally {
    refreshing.value = false
  }
}

async function viewExchanges(serviceUuid) {
  try {
    nav.viewExchanges(await serviceApi.get(serviceUuid))
  } catch (e) {
    notify.error('Failed get service details', e)
  }
}

function openStatus(p) {
  statusPublisher.value = p
  statusVisible.value = true
}

function saveToCsv() {
  const fd = (ms) => formatYYYYMMDDHHMM(ms)
  const lines = [
    csvLine(['Publisher Name', 'ODS Code', 'System', 'Inbound Error', 'Last Extract Received', 'Last Extract Cutoff',
      'Complete Inbound Extract Cutoff', 'Complete Outbound Extract Cutoff', 'Complete Outbound Write Extract Cutoff', 'Outbound Error']),
  ]
  for (const p of filtered.value) {
    for (const s of p.systemStatus || []) {
      const cols = [p.name, p.odsCode, s.name, s.processingInError ? s.processingInErrorMessage : '']
      if (s.lastReceivedTimestamp) {
        cols.push(fd(s.lastReceivedTimestamp), fd(s.lastReceivedExtractCutoff),
          s.lastProcessedInExtractCutoff ? fd(s.lastProcessedInExtractCutoff) : 'None',
          s.lastProcessedOutExtractCutoff ? fd(s.lastProcessedOutExtractCutoff) : 'None',
          s.lastWriteOutExtractCutoff ? fd(s.lastWriteOutExtractCutoff) : 'None')
      } else {
        cols.push('Never', '', '', '', '')
      }
      cols.push(s.writeOutError ? s.writeOutErrorMessage : '')
      lines.push(csvLine(cols))
    }
  }
  saveFile(lines.join('\r\n'), subscriberName + '_status.csv', 'text/plain')
}

getTagNames()
refresh()
</script>

<style scoped>
.config-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.6rem 1rem;
}

.config-grid :deep(input) {
  width: 100%;
}

.span-3 {
  grid-column: span 3;
}

@media (max-width: 1000px) {
  .config-grid {
    grid-template-columns: 1fr 1fr;
  }
  .span-3 {
    grid-column: span 1;
  }
}
</style>
