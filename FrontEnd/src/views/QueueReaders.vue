<template>
  <div>
    <div class="page-header">
      <h1>Queue Reader Status</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <span class="field-inline" v-for="o in displayOptions" :key="o.key">
          <Checkbox v-model="opt[o.key]" binary :inputId="o.key" />
          <label :for="o.key">{{ o.label }}</label>
        </span>
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshingStatus" @click="refreshStatus" />
        <Button v-if="admin" label="Deploy All" icon="fa-solid fa-rocket" severity="warn" size="small" @click="deployAll" />
      </div>
    </div>

    <!-- one column per exchange, one row per routing key -->
    <div class="card">
      <div v-if="!routingExchangeNames" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading routing...</div>
      <table v-else class="simple qr">
        <thead>
          <tr><th v-for="ex in routingExchangeNames" :key="ex">{{ ex }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="rowIndex in rowIndexes" :key="rowIndex">
            <td v-for="ex in routingExchangeNames" :key="ex">
              <div v-if="routingKeyAt(ex, rowIndex)" class="qcell">
                <div class="qname">
                  <b v-tooltip="routingQueueDescs[queueName(ex, routingKeyAt(ex, rowIndex))]">{{ routingKeyAt(ex, rowIndex) }}</b>
                  <i v-if="refreshingRabbit" class="fa-solid fa-rotate spin ml-1"></i>
                  <span v-else class="ml-1">{{ queueSize(queueName(ex, routingKeyAt(ex, rowIndex))) }}</span>
                </div>
                <div>
                  <i v-if="refreshingStatus" class="fa-solid fa-rotate spin"></i>
                  <template v-else>
                    <div v-for="(status, i) in statusesForQueue(queueName(ex, routingKeyAt(ex, rowIndex)))" :key="i">
                      <QueueReaderStatusLine :status="status" showHost />
                    </div>
                  </template>
                </div>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- one row per server -->
    <div class="card" v-if="!refreshingRabbit">
      <table class="simple">
        <thead>
          <tr><th style="width: 14%">Server</th><th style="width: 8%">CPU</th><th style="width: 22%">Memory</th><th>Queue Readers</th></tr>
        </thead>
        <tbody>
          <tr v-for="host in hostNames" :key="host">
            <td :style="{ backgroundColor: hostColour(host) }">{{ host }}</td>
            <td>{{ cpuByHost[host] }}</td>
            <td>
              {{ physicalMemoryByHost[host] }}
              <div><code>{{ memoryGraphByHost[host] }}</code></div>
            </td>
            <td>
              <div v-for="(status, i) in statusesForHost(host)" :key="i">
                {{ status.queueName }} <QueueReaderStatusLine :status="status" />
                <span class="ml-1">{{ queueSize(status.queueName) }}</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <SystemPickerDialog v-model:visible="nav.pickerVisible.value" :service="nav.pickerService.value"
                        @select="nav.onSystemPicked" />
  </div>
</template>

<script setup>
import { computed, defineComponent, h, reactive, ref } from 'vue'
import { queueReaderApi, rabbitApi, serviceApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS, getDateDiffDesc, getDateDiffSimpleFromNow, formatYYYYMMDDHHMM } from '@/utils/format'
import { useNotify, useConfirmAsync, orderBy, isAdmin } from '@/utils/ui'
import { useServiceNavigation } from '@/utils/navigation'
import SystemPickerDialog from '@/components/SystemPickerDialog.vue'

// display options persist for the session (eds-ui QueueReaderStatusService)
const opt = reactive(
  (window.__ddsQueueReaderOptions ||= {
    showExecutionTime: true,
    showOdsCode: false,
    showPublisherConfig: false,
    showDataDate: false,
    showJarDate: false,
    showStartDate: false,
    showMissingQueueReadersOnEmptyQueues: false,
  }),
)
const displayOptions = [
  { key: 'showExecutionTime', label: 'Execution Time' },
  { key: 'showOdsCode', label: 'ODS Code' },
  { key: 'showPublisherConfig', label: 'Publisher Config' },
  { key: 'showDataDate', label: 'Data Date' },
  { key: 'showJarDate', label: 'Jar Date' },
  { key: 'showStartDate', label: 'Start Date' },
  { key: 'showMissingQueueReadersOnEmptyQueues', label: 'Warnings on Empty Queues' },
]

const PRESET_COLOURS = [
  '#f5b7b1', '#d2b4de', '#aed6f1', '#a2d9ce', '#abebc6', '#fad7a0', '#edbb99',
  '#e6b0aa', '#d7bde2', '#a9cce3', '#a3e4d7', '#a9dfbf', '#f9e79f', '#f5cba7',
  '#e5e7e9', '#ccd1d1', '#abb2b9', '#d5dbdb', '#aeb6bf', '#f7f9f9',
]

const notify = useNotify()
const confirmAsync = useConfirmAsync()
const nav = useServiceNavigation()
const admin = isAdmin()

const routingExchangeNames = ref(null)
const exchangeRoutingKeyMap = ref({})
const routingQueueDescs = ref({})
const rowIndexes = ref([])
const statusLastRefreshed = ref(new Date())
const statusList = ref([])
const refreshingStatus = ref(false)
const rabbitQueueStatus = ref(null)
const refreshingRabbit = ref(false)
const rabbitNodes = ref(null)

const queueName = (exchange, routingKey) => exchange + '-' + routingKey
const routingKeyAt = (exchange, index) => exchangeRoutingKeyMap.value[exchange]?.[index]

function isStatusDead(status) {
  return status.timestmp < statusLastRefreshed.value.getTime() - 1000 * 60 * 2
}

function queueSize(name) {
  return rabbitQueueStatus.value?.[name]?.messages_ready ?? null
}

function shouldShowStatus(status) {
  if (opt.showMissingQueueReadersOnEmptyQueues) return true
  const size = queueSize(status.queueName)
  if (size && (size > 0 || (size == 0 && status.isBusy))) return true
  return !isStatusDead(status)
}

const statusesByQueue = computed(() => groupBy(statusList.value, (s) => s.queueName))
const statusesByHost = computed(() => groupBy(statusList.value, (s) => s.hostName))
const statusesForQueue = (name) => (statusesByQueue.value[name] || []).filter(shouldShowStatus)
const statusesForHost = (host) => (statusesByHost.value[host] || []).filter(shouldShowStatus)

function groupBy(list, keyFn) {
  const ret = {}
  for (const x of list) (ret[keyFn(x)] ||= []).push(x)
  return ret
}

const hostNames = computed(() => orderBy([...new Set(statusList.value.map((s) => s.hostName))], (h) => (h || '').toLowerCase()))
const hostColour = (host) => {
  const i = hostNames.value.indexOf(host)
  return i < 0 ? undefined : i >= PRESET_COLOURS.length ? '#101010' : PRESET_COLOURS[i]
}

// most recent heartbeat per host gives its CPU and physical memory
const cpuByHost = computed(() => latestPerHost((s) => (s.cpuLoad ? s.cpuLoad + '%' : null)))
const physicalMemoryByHost = computed(() => latestPerHost((s) => s.physicalMemoryDesc))
function latestPerHost(valueFn) {
  const ret = {}
  for (const s of orderBy(statusList.value, (x) => x.timestmp, true)) {
    const v = valueFn(s)
    if (v && !ret[s.hostName]) ret[s.hostName] = v
  }
  return ret
}

// one character per GB of physical memory: O = allocated to live queue readers' max heap, X = over-allocated
const memoryGraphByHost = computed(() => {
  const size = {}
  const used = {}
  for (const s of statusList.value) {
    if (!size[s.hostName]) size[s.hostName] = s.physicalMemoryMb
    if (!isStatusDead(s)) used[s.hostName] = (used[s.hostName] || 0) + (s.maxHeapMb || 0)
  }
  const ret = {}
  for (const host of hostNames.value) {
    let graph = ''
    const memorySize = size[host]
    if (memorySize) {
      const memoryUsed = used[host] || 0
      const max = Math.round(memorySize / 1024)
      const current = Math.round((memoryUsed / memorySize) * max)
      for (let j = 0; j < current && j < max; j++) graph += 'O'
      for (let j = max; j < current; j++) graph += 'X'
      while (graph.length < max) graph += '_'
    }
    ret[host] = graph
  }
  return ret
})

// one queue reader's status: host, memory, dead/busy/idle icons and the optional details
const QueueReaderStatusLine = defineComponent({
  props: { status: Object, showHost: Boolean },
  setup(props) {
    return () => {
      const s = props.status
      const parts = []
      if (props.showHost) parts.push(s.hostName + ' ')
      if (s.applicationInstanceNumber > 1) parts.push(' #' + s.applicationInstanceNumber)
      if (s.maxHeapDesc) parts.push(' [' + s.maxHeapDesc + '] ')
      const extras = []
      if (isStatusDead(s)) {
        extras.push(
          h('span', { class: 'p-tag p-tag-danger dead', title: s.isBusyDetail || '', onClick: () => viewExchangesForStatus(s) }, [
            h('i', { class: 'fa-solid fa-triangle-exclamation' }),
            ' ' + getDateDiffDesc(new Date(s.timestmp), statusLastRefreshed.value, 2),
          ]),
        )
      } else {
        if (s.dtJar && s.dtStarted && s.dtJar > s.dtStarted) {
          extras.push(h('i', {
            class: 'fa-regular fa-clock ml-1',
            title: 'Jar built: ' + formatYYYYMMDDHHMM(s.dtJar) + ', app started: ' + formatYYYYMMDDHHMM(s.dtStarted),
          }))
        }
        if (s.isBusy) {
          extras.push(h('i', { class: 'fa-solid fa-play ml-1 clickable', title: s.isBusyDetail || '', onClick: () => viewExchangesForStatus(s) }))
          if (opt.showExecutionTime && s.isBusySince) {
            extras.push(h('span', { class: 'muted' }, ' ' + getDateDiffDesc(new Date(s.isBusySince), new Date(s.timestmp), 1)))
          }
          if (opt.showOdsCode && s.isBusyOdsCode) extras.push(h('span', { class: 'muted' }, ' ' + s.isBusyOdsCode))
          if (opt.showPublisherConfig && s.isBusyPublisherConfigName) extras.push(h('span', { class: 'muted' }, ' ' + s.isBusyPublisherConfigName))
          if (opt.showDataDate && s.isBusyDataDate) extras.push(h('span', { class: 'muted' }, ' ' + s.isBusyDataDate))
        } else {
          extras.push(h('i', { class: 'fa-solid fa-pause ml-1', title: 'Application running but idle' }))
        }
        if (opt.showJarDate) extras.push(h('span', { class: 'muted' }, ' ' + formatYYYYMMDDHHMMSS(s.dtJar)))
        if (opt.showStartDate) extras.push(h('span', { class: 'muted' }, ' ' + formatYYYYMMDDHHMMSS(s.dtStarted)))
      }
      return h('span', { class: 'qr-status', style: props.showHost ? { backgroundColor: hostColour(s.hostName) } : {} }, [parts.join(''), ...extras])
    }
  },
})

async function viewExchangesForStatus(status) {
  if (!status.isBusyOdsCode) {
    notify.warning('No ODS code found')
    return
  }
  try {
    nav.viewExchanges(await serviceApi.getForOdsCode(status.isBusyOdsCode))
  } catch (e) {
    notify.error('Failed get service', e)
  }
}

async function loadRouting() {
  try {
    const routings = await rabbitApi.getRoutings()
    const names = []
    const keyMap = {}
    const descs = {}
    for (const r of routings) {
      if (!names.includes(r.exchangeName)) names.push(r.exchangeName)
      ;(keyMap[r.exchangeName] ||= []).push(r.routeKey)
      descs[queueName(r.exchangeName, r.routeKey)] = r.description
    }
    const maxQueues = Math.max(0, ...Object.values(keyMap).map((k) => k.length))
    rowIndexes.value = Array.from({ length: maxQueues }, (_, i) => i)
    exchangeRoutingKeyMap.value = keyMap
    routingQueueDescs.value = descs
    routingExchangeNames.value = names
    refreshQueueReaders()
  } catch (e) {
    routingExchangeNames.value = []
    notify.error('Failed to load Rabbit routing', e)
  }
}

async function refreshRabbitQueues() {
  if (!rabbitNodes.value?.length) return
  refreshingRabbit.value = true
  try {
    const result = await rabbitApi.getRabbitQueues(rabbitNodes.value[0].address)
    const map = {}
    for (const q of result || []) map[q.name] = q
    rabbitQueueStatus.value = map
  } catch (e) {
    notify.error('Failed to load Rabbit queues', e)
  } finally {
    refreshingRabbit.value = false
  }
}

async function refreshQueueReaders() {
  refreshingStatus.value = true
  statusLastRefreshed.value = new Date()
  try {
    const results = await queueReaderApi.getStatus('queuereader')
    // only show extra instances (#2, #3...) up to the highest one that is still alive
    const maxInteresting = {}
    for (const r of results) {
      if (r.applicationInstanceNumber > 1 && !isStatusDead(r)) {
        const cur = maxInteresting[r.applicationInstanceName]
        if (!cur || r.applicationInstanceNumber > cur) maxInteresting[r.applicationInstanceName] = r.applicationInstanceNumber
      }
    }
    const list = results.filter((r) => {
      if (r.applicationInstanceNumber > 1) {
        const max = maxInteresting[r.applicationInstanceName]
        if (!max || r.applicationInstanceNumber > max) return false
      }
      return true
    })
    statusList.value = orderBy(orderBy(list, (s) => s.applicationInstanceNumber), (s) => s.applicationInstanceName)
  } catch (e) {
    notify.error('Failed get Queue Reader status', e)
  } finally {
    refreshingStatus.value = false
  }
}

function refreshStatus() {
  refreshQueueReaders()
  refreshRabbitQueues()
}

async function deployAll() {
  if (!(await confirmAsync('Run the deploy script on every queue reader server?', 'Deploy All'))) return
  try {
    await queueReaderApi.deploy([])
    notify.success('Deploy command sent')
  } catch (e) {
    notify.error('Deploy failed', e)
  }
}

loadRouting()
rabbitApi
  .getRabbitNodes()
  .then((nodes) => {
    rabbitNodes.value = nodes
    refreshRabbitQueues()
  })
  .catch((e) => notify.error('Failed to load Rabbit nodes', e))
</script>

<style scoped>
.qr td {
  vertical-align: top;
}

.qcell {
  display: grid;
  grid-template-columns: 9rem 1fr;
  gap: 0.4rem;
}

:deep(.qr-status) {
  padding: 0 3px;
  border-radius: 3px;
}

:deep(.dead) {
  cursor: pointer;
  margin-left: 0.3rem;
}

:deep(.clickable) {
  cursor: pointer;
}
</style>
