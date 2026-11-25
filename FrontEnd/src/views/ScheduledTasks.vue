<template>
  <div>
    <div class="page-header">
      <h1>Scheduled Tasks</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <span class="sub" v-if="latest">(showing {{ toDisplay.length }} of {{ latest.length }})</span>
      <div class="actions">
        <span class="field-inline"><Checkbox v-model="f.showWarningsOnly" binary inputId="wo" /><label for="wo">Show Warnings Only</label></span>
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refreshAll" />
      </div>
    </div>

    <div class="card">
      <div v-if="!latest || !rules" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
      <DataTable v-else :value="toDisplay" size="small" stripedRows>
        <Column field="applicationName" header="Application" />
        <Column field="taskName" header="Task" />
        <Column header="Last Run">
          <template #body="{ data }">
            <template v-if="data.timestamp">
              <Tag v-if="data.notRunWarning" severity="danger" value="Warning" v-tooltip="'Not run recently'" />
              {{ formatYYYYMMDDHHMM(data.timestamp) }}
            </template>
            <template v-else><Tag severity="danger" value="Warning" v-tooltip="'Never run'" /> NEVER</template>
          </template>
        </Column>
        <Column header="Due In">
          <template #body="{ data }">
            <span v-if="data.rule && data.rule.within" v-tooltip="ruleWithinDesc(data)">{{ dueIn(data) }}</span>
          </template>
        </Column>
        <Column header="Details" style="width: 35%">
          <template #body="{ data }"><TaskDetails :item="data" /></template>
        </Column>
        <Column>
          <template #body="{ data }">
            <Button label="View History" size="small" severity="secondary" outlined class="hover-show" @click="openHistory(data)" />
          </template>
        </Column>
      </DataTable>
    </div>

    <Dialog v-model:visible="history.visible" modal :header="history.audit ? `${history.audit.applicationName} ${history.audit.taskName}` : ''" :style="{ width: '60rem' }">
      <div class="field-inline mb-1">
        <label>Run between</label>
        <InputText type="date" v-model="history.from" />
        <label>and</label>
        <InputText type="date" v-model="history.to" />
        <Button label="Search" :loading="history.searching" @click="searchHistory" />
      </div>
      <DataTable :value="history.results || []" size="small" stripedRows scrollable scrollHeight="55vh">
        <Column header="Executed"><template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMM(data.timestamp) }}</span></template></Column>
        <Column header="Status"><template #body="{ data }"><TaskDetails :item="data" history /></template></Column>
      </DataTable>
      <template #footer><Button label="Close" @click="history.visible = false" /></template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, h, reactive, ref } from 'vue'
import Tag from 'primevue/tag'
import { scheduledTaskApi } from '@/services/api'
import { formatYYYYMMDD, formatYYYYMMDDHHMM, getDateDiffDesc, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify, orderBy } from '@/utils/ui'

// host | parameters, then FAILED / NEVER RUN and the error message
const TaskDetails = (props) => {
  const i = props.item
  const out = []
  const head = [i.hostName, i.taskParameters].filter(Boolean).join(' | ')
  if (head) out.push(h('span', head))
  if (!i.success) {
    const never = !i.timestamp && !props.history
    out.push(h('div', [h(Tag, { severity: 'danger', value: 'Warning' }), never ? ' NEVER RUN' : ' FAILED']))
  }
  if (i.errorMessage) out.push(h('pre', { class: 'block', style: 'max-width: 650px' }, i.errorMessage))
  return h('div', out)
}
TaskDetails.props = ['item', 'history']

const f = reactive((window.__ddsScheduledTaskFilters ||= { showWarningsOnly: false }))
const notify = useNotify()
const latest = ref(null)
const rules = ref(null)
const statusLastRefreshed = ref(new Date())

const UNIT_MS = { minute: 60000, hour: 3600000, day: 86400000, week: 604800000, month: 86400000 * 31, year: 86400000 * 365 }
const rulePeriod = (a) => (UNIT_MS[a.rule.withinUnit] || 0) * a.rule.within
const hasRule = (a) => a.rule && a.rule.withinUnit && a.rule.within

function isItemTooOld(a) {
  if (!hasRule(a)) return false
  if (!a.timestamp) return true
  return a.timestamp < statusLastRefreshed.value.getTime() - rulePeriod(a)
}

function ruleWithinDesc(a) {
  if (!hasRule(a)) return null
  return 'Should be run every ' + a.rule.within + ' ' + a.rule.withinUnit + (a.rule.within > 1 ? 's' : '')
}

function dueIn(a) {
  if (!hasRule(a)) return ''
  if (!a.timestamp) return 'Now'
  return getDateDiffDesc(statusLastRefreshed.value, new Date(a.timestamp + rulePeriod(a)), 2)
}

// every rule gets a row, even if the task has never run; then flag tasks not run within their rule
const merged = computed(() => {
  if (!latest.value || !rules.value) return []
  const list = latest.value.map((x) => ({ ...x }))
  for (const rule of rules.value) {
    const matches = list.filter((n) => n.applicationName === rule.applicationName && n.taskName === rule.taskName)
    if (matches.length) matches.forEach((n) => (n.rule = rule))
    else list.push({ applicationName: rule.applicationName, taskName: rule.taskName, rule })
  }
  for (const n of list) n.notRunWarning = isItemTooOld(n)
  return list
})

const toDisplay = computed(() => {
  const list = merged.value.filter((n) => !(f.showWarningsOnly && n.success && !n.errorMessage && !n.notRunWarning))
  return orderBy(orderBy(list, (s) => (s.taskName || '').toLowerCase()), (s) => (s.applicationName || '').toLowerCase())
})

function refreshAll() {
  statusLastRefreshed.value = new Date()
  latest.value = null
  rules.value = null
  scheduledTaskApi.getRules().then((r) => (rules.value = r)).catch((e) => notify.error('Failed to load rules', e))
  scheduledTaskApi.getSummary().then((r) => (latest.value = r)).catch((e) => notify.error('Failed to load summary', e))
}

// history, run in the last 30 days by default
const history = reactive({ visible: false, audit: null, from: '', to: '', results: null, searching: false })
function openHistory(audit) {
  const d = new Date()
  d.setDate(d.getDate() - 30)
  Object.assign(history, { visible: true, audit, from: formatYYYYMMDD(d), to: '', results: null })
}
async function searchHistory() {
  history.searching = true
  try {
    history.results = await scheduledTaskApi.getHistory(
      history.audit.applicationName, history.audit.taskName,
      history.from ? new Date(history.from) : new Date('1900-01-01'),
      history.to ? new Date(history.to) : new Date('9999-12-31'),
    )
  } catch (e) {
    notify.error('Error searching', e)
  } finally {
    history.searching = false
  }
}

refreshAll()
</script>
