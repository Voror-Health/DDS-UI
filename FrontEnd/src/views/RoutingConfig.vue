<template>
  <div>
    <div class="page-header">
      <h1>Routing Config</h1>
      <span class="sub" v-if="quickestNode">RabbitMQ: {{ quickestNode.address }} ({{ quickestNode.ping }}ms)</span>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refresh" />
        <Button label="New Routing" severity="success" size="small" @click="newRouting(null)" />
        <Button v-if="admin" label="Sync to RabbitMQ" severity="warn" size="small" :disabled="!quickestNode" @click="sync" />
      </div>
    </div>

    <div class="two">
      <div class="card">
        <h2>Queue Status</h2>
        <div v-if="!exchangeNames.length" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
        <div class="status-cols">
          <div v-for="ex in exchangeNames" :key="ex">
            <div class="bold">{{ ex }}</div>
            <div v-for="(r, i) in routingMap[ex]" :key="'r' + i" class="small">
              <i :class="statusIcon(r)"></i> {{ r.routeKey }}
              <i v-if="r.description" class="fa-solid fa-circle-info muted" v-tooltip="r.description"></i>
            </div>
            <template v-for="(b, i) in bindingMap[ex] || []" :key="'b' + i">
              <div v-if="!bindingExistsInConfig(b)" class="small" v-tooltip="'Bound in RabbitMQ but not in the config'">
                <i class="fa-solid fa-circle-minus err"></i> {{ b.routing_key }}
              </div>
            </template>
          </div>
        </div>
        <p class="muted small mt-1"><i class="fa-solid fa-circle-check ok"></i> bound in RabbitMQ &nbsp;
          <i class="fa-solid fa-circle-plus err"></i> in config, not bound &nbsp;
          <i class="fa-solid fa-circle-minus err"></i> bound, not in config</p>
      </div>

      <div class="card">
        <h2>Routing Overrides <Button label="New Override" size="small" severity="success" text @click="openOverride" /></h2>
        <DataTable :value="overrides || []" size="small" stripedRows>
          <Column header="Publisher"><template #body="{ data }">{{ serviceDesc(data.serviceId) }}</template></Column>
          <Column field="exchangeName" header="Exchange" />
          <Column field="routingKey" header="Routing Key" />
          <Column style="width: 3rem">
            <template #body="{ data }">
              <Button icon="fa-solid fa-xmark" severity="danger" size="small" text class="hover-show" @click="deleteOverride(data)" />
            </template>
          </Column>
        </DataTable>
      </div>
    </div>

    <div class="card" v-for="ex in exchangeNames" :key="ex">
      <h2>Exchange: {{ ex }} <Button label="New Routing" size="small" severity="success" text @click="newRouting(ex)" /></h2>
      <DataTable :value="routingMap[ex]" size="small" stripedRows>
        <Column header="Description" style="width: 40%"><template #body="{ data }"><a @click="edit(data)">{{ data.description || '(no description)' }}</a></template></Column>
        <Column header="Regex Filter"><template #body="{ data }"><span class="mono">{{ data.regex }}</span></template></Column>
        <Column field="routeKey" header="Routing Key" />
        <Column style="width: 8rem">
          <template #body="{ data }">
            <span class="hover-show nowrap">
              <Button icon="fa-solid fa-arrow-up" size="small" text @click="move(data, true)" />
              <Button icon="fa-solid fa-arrow-down" size="small" text @click="move(data, false)" />
              <Button icon="fa-solid fa-xmark" size="small" severity="danger" text @click="remove(data)" />
            </span>
          </template>
        </Column>
      </DataTable>
    </div>

    <!-- route group editor -->
    <Dialog v-model:visible="editor.visible" modal header="Route Group" :style="{ width: '32rem' }">
      <div class="field mb-1">
        <label>Exchange</label>
        <InputText v-model="editor.data.exchangeName" placeholder="Exchange name" :disabled="!editor.allowExchange" />
      </div>
      <div class="field mb-1">
        <label>Description</label>
        <InputText v-model="editor.data.description" placeholder="Description" />
      </div>
      <div class="field mb-1">
        <label>Regex Filter</label>
        <InputText v-model="editor.data.regex" placeholder="Filter" class="mono" />
        <span class="field-inline">
          <Button v-for="f in ['[A-Z]', '[0-9]', '[A-Z,0-9]']" :key="f" :label="f" size="small" severity="secondary" outlined @click="editor.data.regex = (editor.data.regex || '') + f" />
        </span>
      </div>
      <div class="field">
        <label>Routing Key</label>
        <InputText v-model="editor.data.routeKey" placeholder="Route Key" />
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="editor.visible = false" />
        <Button label="Ok" @click="saveEditor" />
      </template>
    </Dialog>

    <!-- routing override editor -->
    <Dialog v-model:visible="override.visible" modal header="Routing Override" :style="{ width: '32rem' }">
      <div class="field mb-1">
        <label>Publisher</label>
        <div class="field-inline">
          <InputText :modelValue="serviceDesc(override.data.serviceId)" disabled class="w-full" />
          <Button label="Search Publishers" @click="pickerVisible = true" />
        </div>
      </div>
      <div class="field mb-1">
        <label>Exchange</label>
        <Select v-model="override.data.exchangeName" :options="exchangeNames" showClear placeholder=" " />
      </div>
      <div class="field">
        <label>Routing Key</label>
        <Select v-model="override.data.routingKey" :options="(routingMap[override.data.exchangeName] || []).map((r) => r.routeKey)" placeholder=" " />
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="override.visible = false" />
        <Button label="Ok" @click="saveOverride" />
      </template>
    </Dialog>

    <ServicePickerDialog v-model:visible="pickerVisible" :services="pickerServices" @ok="onPublisherPicked" />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { rabbitApi, serviceApi } from '@/services/api'
import { useNotify, useConfirmAsync, isAdmin } from '@/utils/ui'
import ServicePickerDialog from '@/components/ServicePickerDialog.vue'

const notify = useNotify()
const confirmAsync = useConfirmAsync()
const admin = isAdmin()

const quickestNode = ref(null)
const exchangeNames = ref([])
const routingMap = reactive({}) // exchange -> routings, in config order
const bindingMap = ref({}) // exchange -> bindings actually in RabbitMQ
const overrides = ref(null)
const servicesById = ref({})

function populateRoutingMap(routings) {
  for (const k of Object.keys(routingMap)) delete routingMap[k]
  const names = []
  for (const r of routings) {
    if (!routingMap[r.exchangeName]) {
      routingMap[r.exchangeName] = []
      names.push(r.exchangeName)
    }
    routingMap[r.exchangeName].push(r)
  }
  exchangeNames.value = names
}

function populateBindingMap(bindings) {
  const map = {}
  for (const b of bindings || []) (map[b.source] ||= []).push(b)
  bindingMap.value = map
}

function statusIcon(r) {
  const bindings = bindingMap.value[r.exchangeName]
  return bindings && bindings.some((b) => b.routing_key === r.routeKey) ? 'fa-solid fa-circle-check ok' : 'fa-solid fa-circle-plus err'
}

const bindingExistsInConfig = (b) => (routingMap[b.source] || []).some((r) => r.routeKey === b.routing_key)

async function refresh() {
  serviceApi.getAll().then((list) => (servicesById.value = Object.fromEntries(list.map((s) => [s.uuid, s])))).catch((e) => notify.error('Failed to load services', e))
  rabbitApi.getRoutingOverrides().then((r) => (overrides.value = r || [])).catch((e) => notify.error('Failed to get routing overrides', e))
  rabbitApi.getRoutings().then(populateRoutingMap).catch((e) => notify.error('Failed to load route groups', e))
  // the first node to answer a ping is used to read the bindings
  quickestNode.value = null
  try {
    const nodes = await rabbitApi.getRabbitNodes()
    for (const n of nodes) {
      rabbitApi
        .pingRabbitNode(n.address)
        .then((result) => {
          if (!quickestNode.value) {
            quickestNode.value = result
            loadBindings()
          }
        })
        .catch(() => notify.error('Error pinging node ' + n.address))
    }
  } catch (e) {
    notify.error('Error getting rabbit node list', e)
  }
}

async function loadBindings() {
  try {
    populateBindingMap(await rabbitApi.getRabbitBindings(quickestNode.value.address))
  } catch (e) {
    notify.error('Failed to load rabbit bindings', e)
  }
}

const routingList = () => exchangeNames.value.flatMap((ex) => routingMap[ex] || [])

async function saveRoutings() {
  try {
    await rabbitApi.saveRoutings(routingList())
    notify.success('Route group saved')
  } catch (e) {
    notify.error('Failed to save route group', e)
    refresh()
  }
}

async function sync() {
  if (!(await confirmAsync('Are you sure you want to synchronise RabbitMQ with the defined route groups?', 'Synchronise RabbitMQ'))) return
  try {
    populateBindingMap(await rabbitApi.synchronize(quickestNode.value.address))
    notify.success('RabbitMQ synchronized')
  } catch (e) {
    notify.error('Failed to synchronize', e)
    loadBindings()
  }
}

// route group editor (copy edited, applied on Ok)
const editor = reactive({ visible: false, data: {}, original: null, allowExchange: true })
function edit(item) {
  Object.assign(editor, { visible: true, data: { ...item }, original: item, allowExchange: !item.exchangeName })
}
function newRouting(exchangeName) {
  Object.assign(editor, { visible: true, data: { exchangeName: exchangeName || '', description: '', regex: '', routeKey: '' }, original: null, allowExchange: !exchangeName })
}
function saveEditor() {
  const d = editor.data
  if (!d.exchangeName || !d.routeKey) {
    notify.warning('Exchange and routing key are required')
    return
  }
  if (editor.original) {
    Object.assign(editor.original, d)
  } else {
    if (!routingMap[d.exchangeName]) {
      routingMap[d.exchangeName] = []
      exchangeNames.value.push(d.exchangeName)
    }
    routingMap[d.exchangeName].push({ ...d })
  }
  editor.visible = false
  saveRoutings()
}

async function remove(item) {
  if (!(await confirmAsync('Are you sure you want to delete the route group?', 'Delete Route group', 'Yes', true))) return
  const list = routingMap[item.exchangeName]
  list.splice(list.indexOf(item), 1)
  saveRoutings()
}

function move(item, up) {
  const list = routingMap[item.exchangeName]
  const i = list.indexOf(item)
  const j = up ? i - 1 : i + 1
  if (j < 0 || j >= list.length) return
  list.splice(i, 1)
  list.splice(j, 0, item)
  saveRoutings()
}

// routing overrides
function serviceDesc(serviceId) {
  if (!serviceId) return ''
  const s = servicesById.value[serviceId]
  return s ? `${s.localIdentifier}, ${s.name}` : 'Missing service for ' + serviceId
}

const override = reactive({ visible: false, data: {} })
function openOverride() {
  Object.assign(override, { visible: true, data: { serviceId: null, exchangeName: '', routingKey: '' } })
}
function saveOverride() {
  const d = override.data
  if (!d.serviceId) return notify.error('No service selected')
  if (!d.exchangeName) return notify.error('No exchange selected')
  if (!d.routingKey) return notify.error('No routing key selected')
  override.visible = false
  overrides.value.push({ ...d })
  saveOverrides()
}
async function deleteOverride(o) {
  if (!(await confirmAsync('Are you sure you want to delete the routing override?', 'Delete Override', 'Yes', true))) return
  overrides.value = overrides.value.filter((x) => x !== o)
  saveOverrides()
}
async function saveOverrides() {
  try {
    await rabbitApi.saveRoutingOverrides(overrides.value)
    notify.success('Routing overrides saved')
  } catch (e) {
    notify.error('Failed to save routing overrides', e)
  }
}

const pickerVisible = ref(false)
const pickerServices = computed(() => {
  const s = servicesById.value[override.data.serviceId]
  return s ? [s] : []
})
function onPublisherPicked(list) {
  if (list.length === 0) override.data.serviceId = null
  else if (list.length > 1) notify.error('Multiple services selected')
  else override.data.serviceId = list[0].uuid
}

refresh()
</script>

<style scoped>
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  align-items: start;
}

.status-cols {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr));
  gap: 0.75rem;
}

.small {
  font-size: 0.85rem;
}

@media (max-width: 1000px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
