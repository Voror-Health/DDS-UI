<template>
  <div>
    <div class="page-header">
      <h1>Data Service Logging Monitor</h1>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refresh" />
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <div class="field" style="width: 22rem">
          <label>Filter by Service</label>
          <Select v-model="serviceId" :options="services || []" optionLabel="name" optionValue="uuid" filter showClear placeholder="All" @change="refresh" />
        </div>
        <div class="field" style="width: 12rem">
          <label>Filter by Event Type</label>
          <Select v-model="level" :options="levels" optionLabel="label" optionValue="value" @change="refresh" />
        </div>
      </div>
    </div>

    <div class="card">
      <h2>Recent Logging Events <i v-if="loading" class="fa-solid fa-rotate spin"></i></h2>
      <DataTable :value="events || []" size="small" stripedRows :loading="loading">
        <Column header="Timestamp"><template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMMSS(data.timestmp) }}</span></template></Column>
        <Column header="Type"><template #body="{ data }"><i :class="levelIcon(data.levelString)"></i> {{ data.levelString }}</template></Column>
        <Column header="Message" style="width: 60%"><template #body="{ data }"><a @click="view(data)">{{ data.formattedMessage }}</a></template></Column>
        <Column field="callerMethod" header="Caller Function" />
      </DataTable>
      <div v-if="events && events.length" class="field-inline mt-1">
        <Button icon="fa-solid fa-backward-fast" text size="small" :disabled="page === 0" @click="goTo(0)" />
        <Button icon="fa-solid fa-backward-step" text size="small" :disabled="page === 0" @click="goTo(page - 1)" />
        <span>Page {{ page + 1 }}</span>
        <Button icon="fa-solid fa-forward-step" text size="small" @click="goTo(page + 1)" />
      </div>
    </div>

    <Dialog v-model:visible="entry.visible" modal :style="{ width: '70rem' }" maximizable>
      <template #header>
        <span class="bold"><i :class="levelIcon(entry.event?.levelString)"></i> Log Entry : {{ formatYYYYMMDDHHMMSS(entry.event?.timestmp) }}</span>
      </template>
      <table v-if="entry.event" class="simple mb-1">
        <tbody>
          <tr><th style="width: 7rem">Message</th><td colspan="3">{{ entry.event.formattedMessage }}</td></tr>
          <tr><th>Logger</th><td colspan="3">{{ entry.event.loggerName }}</td></tr>
          <tr><th>Level</th><td>{{ entry.event.levelString }}</td><th style="width: 7rem">Thread</th><td>{{ entry.event.threadName }}</td></tr>
          <tr><th>File</th><td>{{ entry.event.callerFilename }}</td><th>Method</th><td>{{ entry.event.callerMethod }} (~{{ entry.event.callerLine }})</td></tr>
          <tr><th>Args</th><td colspan="3">{{ [entry.event.arg0, entry.event.arg1, entry.event.arg2, entry.event.arg3].filter((a) => a != null).join(' ') }}</td></tr>
        </tbody>
      </table>
      <label class="field-label">Stack Trace</label>
      <pre class="block">{{ entry.stackTrace }}</pre>
      <template #footer><Button label="Close" @click="entry.visible = false" /></template>
    </Dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { loggingApi, serviceApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS } from '@/utils/format'
import { useNotify } from '@/utils/ui'

const notify = useNotify()
const services = ref(null)
const serviceId = ref(null)
const level = ref('')
const page = ref(0)
const events = ref(null)
const loading = ref(false)

const levels = [
  ['', 'All'], ['INFO', 'Information'], ['WARN', 'Warnings'], ['ERROR', 'Error'], ['FATAL', 'Fatal'], ['DEBUG', 'Debug'], ['TRACE', 'Trace'],
].map(([value, label]) => ({ value, label }))

const ICONS = {
  TRACE: 'fa-solid fa-magnifying-glass ok',
  DEBUG: 'fa-solid fa-bug',
  INFO: 'fa-solid fa-info',
  WARN: 'fa-solid fa-circle-exclamation warn',
  ERROR: 'fa-solid fa-ban err',
  FATAL: 'fa-solid fa-stop err',
}
const levelIcon = (l) => ICONS[l] || ''

async function load() {
  loading.value = true
  events.value = null
  try {
    events.value = await loggingApi.getLoggingEvents(serviceId.value, level.value, page.value)
  } catch (e) {
    notify.error('Failed to load events', e)
  } finally {
    loading.value = false
  }
}

function refresh() {
  page.value = 0
  load()
}

function goTo(p) {
  page.value = Math.max(0, p)
  load()
}

const entry = reactive({ visible: false, event: null, stackTrace: '' })
async function view(event) {
  try {
    entry.stackTrace = await loggingApi.getStackTrace(event.eventId)
    entry.event = event
    entry.visible = true
  } catch (e) {
    notify.error('Failed to load stack trace', e)
  }
}

serviceApi.getAll().then((r) => (services.value = r)).catch((e) => notify.error('Failed to load services', e))
refresh()
</script>
