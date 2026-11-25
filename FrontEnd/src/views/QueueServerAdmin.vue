<template>
  <div>
    <!-- Added by the dds-ui Vue port. Server actions use SSH to the queue reader servers (see README). -->
    <div class="page-header">
      <h1>Queue Server Administration</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(lastRefreshed) }}</span>
      <span class="sub" v-if="quickestNode">
        <i class="fa-solid fa-circle" :class="quickestNode.ping < 100 ? 'ok' : quickestNode.ping < 500 ? 'warn' : 'err'" style="font-size: 0.5rem"></i>
        RabbitMQ: {{ quickestNode.address }} ({{ quickestNode.ping }}ms)
      </span>
      <span class="sub err" v-else-if="rabbitError"><i class="fa-solid fa-circle-xmark"></i> {{ rabbitError }}</span>
      <div class="actions">
        <Button label="Refresh All" icon="fa-solid fa-rotate" severity="success" size="small" :loading="loading" @click="refreshAll" />
      </div>
    </div>

    <Message v-if="!admin" severity="info" class="mb-1">You can view this page; changing servers or queues needs the vhp_ui_admin role.</Message>
    <Message v-if="actionMessage" :severity="actionError ? 'error' : 'info'" class="mb-1" closable @close="actionMessage = ''">{{ actionMessage }}</Message>

    <Tabs value="servers">
      <TabList>
        <Tab value="servers">Server Config</Tab>
        <Tab value="queueAdmin">Queue Admin</Tab>
        <Tab value="queueMgmt">Queue Management</Tab>
        <Tab value="ops">Server Operations</Tab>
      </TabList>
      <TabPanels>
        <!-- ======================= Server Config ======================= -->
        <TabPanel value="servers">
          <div class="card">
            <h2>Queue Reader Servers ({{ servers.length }})
              <Button v-if="admin" label="Add Server" icon="fa-solid fa-plus" severity="success" size="small" text @click="openServerEditor(null)" />
            </h2>
            <DataTable :value="servers" size="small" stripedRows :loading="serversLoading" dataKey="serverNumber">
              <Column header="Server #"><template #body="{ data }"><b>QR{{ data.serverNumber }}</b></template></Column>
              <Column field="hostName" header="Hostname" />
              <Column field="ipAddress" header="IP Address" />
              <Column header="AWS Instance"><template #body="{ data }"><code>{{ data.awsInstanceId || '-' }}</code></template></Column>
              <Column header="Physical Memory"><template #body="{ data }">{{ memoryByHost[data.hostName]?.physical || '-' }}</template></Column>
              <Column header="Queues">
                <template #body="{ data }">
                  <Tag v-if="data.queues" :value="splitQueues(data.queues).length + ' queue(s)'" severity="info" v-tooltip="data.queues" />
                  <span v-else class="muted">None</span>
                </template>
              </Column>
              <Column v-if="admin" header="" style="text-align: right">
                <template #body="{ data }">
                  <span class="nowrap">
                    <Button icon="fa-solid fa-stop" severity="warn" size="small" text v-tooltip="'Stop all queues'" @click="serverAction('stopAllQueues', data, 'Stop ALL queues on')" />
                    <Button icon="fa-solid fa-rotate" severity="warn" size="small" text v-tooltip="'Restart all queues'" @click="serverAction('restartAllQueues', data, 'Restart ALL queues on')" />
                    <Button icon="fa-solid fa-pen" severity="secondary" size="small" text v-tooltip="'Edit server'" @click="openServerEditor(data)" />
                    <Button icon="fa-solid fa-trash" severity="danger" size="small" text v-tooltip="'Delete server'" @click="deleteServer(data)" />
                  </span>
                </template>
              </Column>
            </DataTable>
          </div>

          <div class="card">
            <h2>Queue Reader Instances ({{ instances.length }})
              <Button v-if="admin && instances.length" label="Deploy..." icon="fa-solid fa-rocket" severity="warn" size="small" text @click="openDeploy(null)" />
            </h2>
            <DataTable :value="instances" size="small" stripedRows :loading="instancesLoading" dataKey="configId">
              <Column style="width: 2rem">
                <template #body="{ data }"><i class="fa-solid fa-circle" :class="data.status === 'running' ? 'ok' : 'err'" v-tooltip="data.status"></i></template>
              </Column>
              <Column header="Config ID"><template #body="{ data }"><b>{{ data.configId }}</b></template></Column>
              <Column header="Queue Name"><template #body="{ data }">{{ data.queueName || '-' }}</template></Column>
              <Column header="Type"><template #body="{ data }"><Tag :value="data.queueType" :severity="typeSeverity(data.queueType)" /></template></Column>
              <Column field="hostName" header="Host" />
              <Column header="Heap">
                <template #body="{ data }">{{ data.currentHeapDesc && data.maxHeapDesc ? `${data.currentHeapDesc} / ${data.maxHeapDesc}` : '-' }}</template>
              </Column>
              <Column v-if="admin" header="" style="text-align: right">
                <template #body="{ data }">
                  <span class="nowrap">
                    <Button v-if="data.status === 'running'" label="Stop" severity="danger" size="small" outlined @click="instanceAction('stop', data)" />
                    <Button v-else label="Restart" severity="success" size="small" outlined @click="instanceAction('restart', data)" />
                    <Button label="Memory" severity="warn" size="small" outlined class="ml-1" @click="openMemoryEditor(data)" />
                    <Button label="Deploy" severity="info" size="small" outlined class="ml-1" @click="openDeploy(data)" />
                  </span>
                </template>
              </Column>
            </DataTable>
          </div>
        </TabPanel>

        <!-- ======================= Queue Admin ======================= -->
        <TabPanel value="queueAdmin">
          <div class="card">
            <h2>Memory Defaults by Queue Type</h2>
            <table class="simple" style="max-width: 40rem">
              <thead><tr><th>Queue Type</th><th>Default Max Heap</th><th></th></tr></thead>
              <tbody>
                <tr v-for="row in memoryDefaults" :key="row.queueType">
                  <td><Tag :value="row.queueType" :severity="typeSeverity(row.queueType)" /></td>
                  <td>
                    <span class="field-inline">
                      <InputNumber v-model="row.maxHeap" :min="0" :max="65536" :useGrouping="false" inputStyle="width: 7rem" :disabled="!admin" />
                      <Select v-model="row.unit" :options="['MB', 'G']" :disabled="!admin" />
                    </span>
                  </td>
                  <td><Button v-if="admin" label="Save" size="small" @click="saveMemoryDefault(row)" /></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="card" v-if="admin">
            <h2>Create New Queue</h2>
            <div class="filters">
              <div class="field" style="width: 10rem">
                <label>Queue Type</label>
                <Select v-model="newQueue.type" :options="QUEUE_TYPES" optionLabel="label" optionValue="value" />
              </div>
              <div class="field" style="width: 12rem">
                <label>Name</label>
                <InputText v-model="newQueue.name" :placeholder="`e.g. Eds${capitalise(newQueue.type)}-A1`" />
              </div>
              <div class="field" style="width: 18rem">
                <label>Assign to Servers</label>
                <MultiSelect v-model="newQueue.servers" :options="serverOptions" optionLabel="label" optionValue="value" display="chip" placeholder="Select servers" />
              </div>
              <Button label="Add Queue" @click="addQueue" />
            </div>
            <p class="muted mt-1">This records which servers a queue runs on (the server_ips config). The RabbitMQ queue itself is created from Routing Config.</p>
          </div>

          <div class="card">
            <h2>Queue Assignments ({{ queueList.length }} queues)
              <span class="sub muted" v-if="quickestNode">{{ rabbitQueueNames.length }} queues from RabbitMQ</span>
              <Button icon="fa-solid fa-rotate" text size="small" :loading="rabbitQueuesLoading" @click="refreshRabbitQueues" v-tooltip="'Refresh queue list'" />
            </h2>
            <DataTable :value="queueList" size="small" stripedRows>
              <Column header="Queue Name"><template #body="{ data }"><b>{{ data.name }}</b></template></Column>
              <Column header="Type"><template #body="{ data }"><Tag :value="data.type" :severity="typeSeverity(data.type)" /></template></Column>
              <Column header="Assigned Servers" style="width: 55%">
                <template #body="{ data }">
                  <MultiSelect :modelValue="data.serverAssignments" :options="serverOptions" optionLabel="label" optionValue="value"
                               display="chip" placeholder="None" class="w-full" :disabled="!admin"
                               @update:modelValue="saveAssignments(data, $event)" />
                </template>
              </Column>
            </DataTable>
          </div>
        </TabPanel>

        <!-- ======================= Queue Management ======================= -->
        <TabPanel value="queueMgmt">
          <template v-if="admin">
            <div class="card">
              <h2><i class="fa-solid fa-play ok"></i> Start Queue</h2>
              <div class="filters">
                <div class="field" style="width: 16rem">
                  <label>Queue Name</label>
                  <Select v-model="start.queue" :options="queueNameOptions" filter editable placeholder="Select queue" />
                </div>
                <div class="field" style="width: 20rem">
                  <label>Start On Servers</label>
                  <MultiSelect v-model="start.servers" :options="serverOptions" optionLabel="label" optionValue="value" display="chip" placeholder="Select servers" />
                </div>
                <div class="field" style="width: 10rem">
                  <label>Memory (optional)</label>
                  <InputText v-model="start.memory" placeholder="e.g. 1g" />
                </div>
                <Button label="Start" :disabled="!start.queue || !start.servers.length" @click="startQueue" />
              </div>
            </div>

            <div class="card">
              <h2><i class="fa-solid fa-stop err"></i> Stop / <i class="fa-solid fa-rotate warn"></i> Restart Queue</h2>
              <div class="filters">
                <div class="field" style="width: 16rem">
                  <label>Queue Name</label>
                  <Select v-model="single.queue" :options="queueNameOptions" filter editable placeholder="Select queue" />
                </div>
                <div class="field" style="width: 14rem">
                  <label>Server</label>
                  <Select v-model="single.server" :options="serverOptions" optionLabel="label" optionValue="value" placeholder="Select server" />
                </div>
                <Button label="Stop" severity="danger" :disabled="!single.queue || !single.server" @click="singleQueueAction('stopQueue')" />
                <Button label="Restart" severity="warn" :disabled="!single.queue || !single.server" @click="singleQueueAction('restartQueue')" />
              </div>
            </div>

            <div class="card">
              <h2><i class="fa-solid fa-server"></i> All Queues on a Server</h2>
              <div class="filters">
                <div class="field" style="width: 14rem">
                  <label>Server</label>
                  <Select v-model="allServer" :options="serverOptions" optionLabel="label" optionValue="value" placeholder="Select server" />
                </div>
                <Button label="Stop All Queues" severity="danger" :disabled="!allServer" @click="serverAction('stopAllQueues', serverByNumber(allServer), 'Stop ALL queues on')" />
                <Button label="Restart All Queues" severity="warn" :disabled="!allServer" @click="serverAction('restartAllQueues', serverByNumber(allServer), 'Restart ALL queues on')" />
                <Button label="Restart All Queues on Every Server" severity="warn" outlined @click="restartEverywhere" />
              </div>
            </div>
          </template>

          <div class="card">
            <h2>Current Queue Status</h2>
            <DataTable :value="instances" size="small" stripedRows>
              <Column header="Config ID"><template #body="{ data }"><b>{{ data.configId }}</b></template></Column>
              <Column header="Queue Name"><template #body="{ data }">{{ data.queueName || '-' }}</template></Column>
              <Column header="Type"><template #body="{ data }"><Tag :value="data.queueType" :severity="typeSeverity(data.queueType)" /></template></Column>
              <Column field="hostName" header="Host" />
              <Column header="Heap"><template #body="{ data }">{{ data.currentHeapDesc && data.maxHeapDesc ? `${data.currentHeapDesc} / ${data.maxHeapDesc}` : '-' }}</template></Column>
              <Column header="Status"><template #body="{ data }"><Tag :value="data.status" :severity="data.status === 'running' ? 'success' : 'danger'" /></template></Column>
            </DataTable>
          </div>
        </TabPanel>

        <!-- ======================= Server Operations ======================= -->
        <TabPanel value="ops">
          <div class="card">
            <h2>Server Power</h2>
            <DataTable :value="servers" size="small" stripedRows>
              <Column header="Server #"><template #body="{ data }"><b>QR{{ data.serverNumber }}</b></template></Column>
              <Column field="hostName" header="Hostname" />
              <Column header="AWS Instance ID"><template #body="{ data }"><code>{{ data.awsInstanceId || 'N/A' }}</code></template></Column>
              <Column v-if="admin" header="" style="text-align: right">
                <template #body="{ data }">
                  <span class="nowrap">
                    <Button v-if="data.awsInstanceId" label="EC2 Start" severity="success" size="small" outlined @click="serverAction('ec2Start', data, 'Start the AWS EC2 instance for')" />
                    <Button v-if="data.awsInstanceId" label="EC2 Stop" severity="danger" size="small" outlined class="ml-1" @click="serverAction('ec2Stop', data, 'STOP (power off) the AWS EC2 instance for', true)" />
                    <Button label="Shutdown" severity="danger" size="small" outlined class="ml-1" @click="serverAction('shutdownServer', data, 'SHUT DOWN (in 1 minute)', true)" />
                    <Button label="Reboot" severity="warn" size="small" outlined class="ml-1" @click="serverAction('rebootServer', data, 'Reboot')" />
                  </span>
                </template>
              </Column>
            </DataTable>
            <p class="muted mt-1">EC2 start/stop runs the AWS CLI over SSH on this Tomcat host (ubuntu@localhost), as the dds-ui port did.</p>
          </div>

          <div class="card" v-if="admin">
            <h2>JAR Upload</h2>
            <div class="filters">
              <div class="field" style="width: 16rem">
                <label>Target Server</label>
                <Select v-model="jar.server" :options="serverOptions" optionLabel="label" optionValue="value" placeholder="Select server" />
              </div>
              <div class="field">
                <label>JAR File</label>
                <input type="file" accept=".jar" @change="jar.file = $event.target.files[0]" />
              </div>
              <Button label="Upload" :disabled="!jar.server || !jar.file" :loading="jar.uploading" @click="uploadJar" />
            </div>
          </div>

          <div class="card">
            <h2>JAR Versions</h2>
            <div v-if="jarVersionsLoading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
            <table v-else-if="jarVersions.length" class="simple">
              <thead><tr><th style="width: 12rem">Server</th><th>JAR Information</th></tr></thead>
              <tbody>
                <tr v-for="row in jarVersions" :key="row.serverHostName">
                  <td><b>{{ row.serverHostName }}</b></td>
                  <td><pre class="block" style="max-height: none">{{ row.jarInfo }}</pre></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="muted">No JAR version information available.</div>
          </div>
        </TabPanel>
      </TabPanels>
    </Tabs>

    <!-- server editor -->
    <Dialog v-model:visible="serverEditor.visible" modal :header="serverEditor.editing ? 'Edit Server' : 'Add Server'" :style="{ width: '34rem' }">
      <div class="field mb-1">
        <label>Server Number</label>
        <InputText v-model="serverEditor.form.serverNumber" placeholder="e.g. 1" :disabled="serverEditor.editing" />
      </div>
      <div class="field mb-1">
        <label>IP Address</label>
        <InputText v-model="serverEditor.form.ipAddress" placeholder="e.g. 10.0.0.1" />
      </div>
      <div class="field mb-1">
        <label>AWS Instance ID</label>
        <InputText v-model="serverEditor.form.awsInstanceId" placeholder="e.g. i-1234567890abcdef0" />
      </div>
      <div class="field">
        <label>Queues Assigned</label>
        <MultiSelect v-model="serverEditor.form.selectedQueues" :options="allQueueNames" filter display="chip" placeholder="Select queues" />
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="serverEditor.visible = false" />
        <Button :label="serverEditor.editing ? 'Update' : 'Add'" @click="saveServer" />
      </template>
    </Dialog>

    <!-- per-instance memory override -->
    <Dialog v-model:visible="memoryEditor.visible" modal header="Queue Reader Memory" :style="{ width: '30rem' }">
      <div class="field mb-1"><label>Config ID</label><InputText :modelValue="memoryEditor.configId" disabled /></div>
      <div class="field-inline mb-1">
        <Checkbox v-model="memoryEditor.useDefaults" binary inputId="useDef" />
        <label for="useDef">Use the {{ memoryEditor.queueType }} queue type default<span v-if="memoryEditor.defaultDesc"> ({{ memoryEditor.defaultDesc }})</span></label>
      </div>
      <div v-if="!memoryEditor.useDefaults" class="field-inline">
        <InputNumber v-model="memoryEditor.maxHeap" :min="1" :useGrouping="false" inputStyle="width: 8rem" />
        <Select v-model="memoryEditor.unit" :options="['MB', 'G']" />
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="memoryEditor.visible = false" />
        <Button label="Save" @click="saveMemoryOverride" />
      </template>
    </Dialog>

    <!-- deploy -->
    <Dialog v-model:visible="deploy.visible" modal header="Deploy JAR" :style="{ width: '36rem' }">
      <p class="muted">Runs the deploy script on the servers of the selected queue readers.</p>
      <MultiSelect v-model="deploy.selected" :options="deployOptions" optionLabel="label" optionValue="value" filter display="chip" class="w-full" placeholder="Select instances" />
      <p class="mt-1">Servers: <b>{{ deployHosts.join(', ') || 'none' }}</b></p>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="deploy.visible = false" />
        <Button label="Deploy" :disabled="!deployHosts.length" @click="runDeploy" />
      </template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { queueReaderApi, queueServerApi, rabbitApi } from '@/services/api'
import { errorMessage } from '@/services/http'
import { getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify, useConfirmAsync, isAdmin } from '@/utils/ui'

const QUEUE_TYPES = [
  { label: 'Inbound', value: 'inbound' },
  { label: 'Protocol', value: 'protocol' },
  { label: 'Transform', value: 'transform' },
  { label: 'Subscriber', value: 'subscriber' },
]

const notify = useNotify()
const confirmAsync = useConfirmAsync()
const admin = isAdmin()

const servers = ref([])
const instances = ref([])
const memoryDefaults = ref([])
const jarVersions = ref([])
const rabbitQueues = ref([])
const quickestNode = ref(null)
const rabbitError = ref('')
const lastRefreshed = ref(null)
const serversLoading = ref(false)
const instancesLoading = ref(false)
const jarVersionsLoading = ref(false)
const rabbitQueuesLoading = ref(false)
const loading = ref(false)
const actionMessage = ref('')
const actionError = ref(false)

const splitQueues = (q) => (q || '').trim().split(/\s+/).filter(Boolean)
const capitalise = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : '')
const typeOf = (name) =>
  name.includes('Protocol') ? 'protocol' : name.includes('Transform') ? 'transform' : name.includes('Subscriber') ? 'subscriber' : 'inbound'
const typeSeverity = (t) => ({ inbound: 'success', protocol: 'info', transform: 'warn', subscriber: 'secondary' })[t] || 'secondary'
const serverByNumber = (n) => servers.value.find((s) => s.serverNumber === n)

const serverOptions = computed(() => servers.value.map((s) => ({ label: `QR${s.serverNumber} (${s.hostName})`, value: s.serverNumber })))

// RabbitMQ queues, without system / dead-letter queues
const rabbitQueueNames = computed(() =>
  [...new Set(rabbitQueues.value.map((q) => q.name).filter((n) => !n.startsWith('amq.') && n !== 'management' && !n.includes('dlq') && !n.includes('.dead.')))].sort(),
)
const allQueueNames = computed(() => [...new Set([...rabbitQueueNames.value, ...servers.value.flatMap((s) => splitQueues(s.queues))])].sort())
const queueNameOptions = computed(() => allQueueNames.value)

// every queue (from server_ips and RabbitMQ) with the servers it is assigned to
const queueList = computed(() => {
  const map = {}
  for (const name of allQueueNames.value) map[name] = { name, type: typeOf(name), serverAssignments: [] }
  for (const s of servers.value) {
    for (const q of splitQueues(s.queues)) map[q].serverAssignments.push(s.serverNumber)
  }
  return Object.values(map).sort((a, b) => a.name.localeCompare(b.name))
})

const memoryByHost = computed(() => {
  const ret = {}
  for (const i of instances.value) {
    if (i.hostName && i.physicalMemoryDesc && !ret[i.hostName]) ret[i.hostName] = { physical: i.physicalMemoryDesc }
  }
  return ret
})

function memoryForType(type) {
  const d = memoryDefaults.value.find((m) => m.queueType === type)
  if (!d || !d.maxHeap) return null
  return d.maxHeap + ((d.unit || 'MB').toUpperCase() === 'G' ? 'g' : 'm')
}

// ---------------------------------------------------------------- loading

async function refreshServers() {
  serversLoading.value = true
  try {
    servers.value = await queueServerApi.getServers()
  } catch (e) {
    notify.error('Failed to load servers', e)
  } finally {
    serversLoading.value = false
  }
}

async function refreshInstances() {
  instancesLoading.value = true
  try {
    instances.value = await queueReaderApi.getInstances()
  } catch (e) {
    notify.error('Failed to load queue reader instances', e)
  } finally {
    instancesLoading.value = false
  }
}

async function refreshMemoryDefaults() {
  try {
    memoryDefaults.value = await queueReaderApi.getMemoryDefaults()
  } catch (e) {
    notify.error('Failed to load memory defaults', e)
  }
}

async function refreshJarVersions() {
  jarVersionsLoading.value = true
  try {
    jarVersions.value = await queueServerApi.getJarVersions()
  } catch (e) {
    jarVersions.value = []
  } finally {
    jarVersionsLoading.value = false
  }
}

async function findQuickestRabbitNode() {
  try {
    const nodes = await rabbitApi.getRabbitNodes()
    let best = null
    await Promise.all(
      nodes.map(async (n) => {
        try {
          const r = await rabbitApi.pingRabbitNode(n.address)
          if (r && r.ping >= 0 && (!best || r.ping < best.ping)) best = r
        } catch {
          /* node down */
        }
      }),
    )
    quickestNode.value = best
    rabbitError.value = best ? '' : 'RabbitMQ: no node answered'
  } catch (e) {
    rabbitError.value = 'RabbitMQ: ' + errorMessage(e)
  }
}

async function refreshRabbitQueues() {
  if (!quickestNode.value) return
  rabbitQueuesLoading.value = true
  try {
    rabbitQueues.value = (await rabbitApi.getRabbitQueuesFiltered(quickestNode.value.address)) || []
  } catch (e) {
    notify.warning('Could not load queues from RabbitMQ', errorMessage(e))
  } finally {
    rabbitQueuesLoading.value = false
  }
}

async function refreshAll() {
  loading.value = true
  lastRefreshed.value = new Date()
  await Promise.all([refreshServers(), refreshInstances(), refreshMemoryDefaults(), refreshJarVersions(), refreshRabbitQueues()])
  loading.value = false
}

// ---------------------------------------------------------------- actions

function report(result, okText) {
  // the queue server endpoints answer 200 with {success, message|error}
  if (result && result.success === false) {
    actionError.value = true
    actionMessage.value = result.error || 'Failed'
    notify.error(result.error || 'Failed')
    return false
  }
  actionError.value = false
  actionMessage.value = result?.message || okText
  notify.success(result?.message || okText)
  return true
}

async function run(fn, okText) {
  try {
    return report(await fn(), okText)
  } catch (e) {
    actionError.value = true
    actionMessage.value = 'Failed: ' + errorMessage(e)
    notify.error('Failed', e)
    return false
  }
}

async function serverAction(apiName, server, verb, doubleConfirm = false) {
  if (!server) return
  const label = `QR${server.serverNumber}`
  if (!(await confirmAsync(`${verb} ${label}?`, 'Confirm', 'Yes', doubleConfirm))) return
  if (doubleConfirm && !(await confirmAsync(`FINAL CONFIRMATION: ${verb} ${label}.`, 'Are you sure?', 'Yes', true))) return
  if (await run(() => queueServerApi[apiName](server.serverNumber), 'Command sent to ' + label)) {
    setTimeout(refreshInstances, 3000)
  }
}

async function restartEverywhere() {
  if (!(await confirmAsync('Restart all queues on every server?', 'Confirm', 'Yes', true))) return
  for (const s of servers.value) {
    await run(() => queueServerApi.restartAllQueues(s.serverNumber), `Restart sent to QR${s.serverNumber}`)
  }
  setTimeout(refreshInstances, 5000)
}

async function instanceAction(action, instance) {
  if (!(await confirmAsync(`${capitalise(action)} queue reader "${instance.configId}"?`))) return
  try {
    const results = await queueReaderApi[action]([instance.configId])
    const r = (results || [])[0]
    report(r ? { success: r.success, message: r.message, error: r.error } : null, `${capitalise(action)} signal sent`)
    setTimeout(refreshInstances, 3000)
  } catch (e) {
    notify.error('Failed', e)
  }
}

async function saveMemoryDefault(row) {
  if (row.maxHeap === null || row.maxHeap === undefined || isNaN(row.maxHeap)) {
    notify.warning('Please enter a valid number')
    return
  }
  try {
    await queueReaderApi.saveMemoryDefaults(row.queueType, row.maxHeap, row.unit || 'MB')
    notify.success('Memory defaults saved')
  } catch (e) {
    notify.error('Failed to save memory defaults', e)
  }
}

// queue assignment: the server list for a queue is saved as a whole
async function saveAssignments(queue, serverNumbers) {
  if (await run(() => queueServerApi.saveQueueAssignments(queue.name, queue.type, null, serverNumbers), `Assignments for ${queue.name} saved`)) {
    await refreshServers()
  }
}

const newQueue = reactive({ type: 'inbound', name: '', servers: [] })
async function addQueue() {
  const name = (newQueue.name || '').trim()
  if (!name || !newQueue.servers.length) {
    notify.warning('Enter a queue name and choose at least one server')
    return
  }
  if (await run(() => queueServerApi.saveQueueAssignments(name, newQueue.type, null, newQueue.servers), `Queue ${name} assigned`)) {
    newQueue.name = ''
    newQueue.servers = []
    await refreshServers()
  }
}

const start = reactive({ queue: null, servers: [], memory: '' })
async function startQueue() {
  const memory = start.memory || memoryForType(typeOf(start.queue)) || null
  if (await run(() => queueServerApi.startQueuesOnMultipleServers(start.queue, memory, start.servers), 'Start command sent')) {
    setTimeout(refreshInstances, 3000)
  }
}

const single = reactive({ queue: null, server: null })
async function singleQueueAction(apiName) {
  const verb = apiName === 'stopQueue' ? 'Stop' : 'Restart'
  if (!(await confirmAsync(`${verb} ${single.queue} on QR${single.server}?`))) return
  if (await run(() => queueServerApi[apiName](single.server, single.queue), `${verb} command sent`)) {
    setTimeout(refreshInstances, 3000)
  }
}

const allServer = ref(null)

// server editor
const serverEditor = reactive({ visible: false, editing: false, form: {} })
function openServerEditor(server) {
  serverEditor.editing = !!server
  serverEditor.form = server
    ? { serverNumber: server.serverNumber, ipAddress: server.ipAddress, awsInstanceId: server.awsInstanceId || '', selectedQueues: splitQueues(server.queues) }
    : { serverNumber: '', ipAddress: '', awsInstanceId: '', selectedQueues: [] }
  serverEditor.visible = true
}
async function saveServer() {
  const f = serverEditor.form
  if (!f.serverNumber || !f.ipAddress) {
    notify.warning('Server number and IP address are required')
    return
  }
  const ok = await run(
    () => queueServerApi.saveServer({ serverNumber: String(f.serverNumber).trim(), ipAddress: f.ipAddress.trim(), awsInstanceId: (f.awsInstanceId || '').trim(), queues: f.selectedQueues.join(' ') }),
    'Server saved',
  )
  if (ok) {
    serverEditor.visible = false
    refreshServers()
  }
}
async function deleteServer(server) {
  if (!(await confirmAsync(`Delete QR${server.serverNumber} (${server.hostName}) from the server configuration?`, 'Delete Server', 'Delete', true))) return
  if (await run(() => queueServerApi.deleteServer(server.serverNumber), 'Server deleted')) refreshServers()
}

// per-instance memory override. "Use defaults" saves an empty override; the dds-ui port called
// DELETE /queueReader/config/{id} here, which deletes the queue reader's whole configuration.
const memoryEditor = reactive({ visible: false, configId: '', queueType: '', useDefaults: true, maxHeap: null, unit: 'MB', defaultDesc: '' })
async function openMemoryEditor(instance) {
  Object.assign(memoryEditor, { visible: true, configId: instance.configId, queueType: instance.queueType, useDefaults: true, maxHeap: null, unit: 'MB', defaultDesc: '' })
  try {
    const cfg = await queueReaderApi.getConfig(instance.configId)
    const m = cfg?.memory || {}
    if (m.defaultMaxHeap) memoryEditor.defaultDesc = `${m.defaultMaxHeap} ${m.defaultUnit || 'MB'}`
    if (m.maxHeap) {
      memoryEditor.useDefaults = false
      memoryEditor.maxHeap = m.maxHeap
      memoryEditor.unit = m.unit || 'MB'
    }
  } catch (e) {
    notify.error('Failed to load queue reader config', e)
  }
}
async function saveMemoryOverride() {
  const useDefaults = memoryEditor.useDefaults
  if (!useDefaults && (!memoryEditor.maxHeap || isNaN(memoryEditor.maxHeap))) {
    notify.warning('Please enter a valid number')
    return
  }
  try {
    await queueReaderApi.saveMemoryOverride(memoryEditor.configId, useDefaults ? null : memoryEditor.maxHeap, useDefaults ? null : memoryEditor.unit)
    notify.success('Memory configuration saved (takes effect when the queue reader restarts)')
    memoryEditor.visible = false
  } catch (e) {
    notify.error('Failed to save memory configuration', e)
  }
}

// deploy: the backend runs the deploy script per server, so the selected instances map to their hosts
const deploy = reactive({ visible: false, selected: [] })
const deployOptions = computed(() => instances.value.map((i) => ({ label: `${i.configId} - ${i.queueName || '?'} (${i.hostName})`, value: i.configId })))
const deployHosts = computed(() => [...new Set(instances.value.filter((i) => deploy.selected.includes(i.configId)).map((i) => i.hostName))].sort())
function openDeploy(instance) {
  deploy.selected = instance ? [instance.configId] : instances.value.map((i) => i.configId)
  deploy.visible = true
}
async function runDeploy() {
  const hosts = deployHosts.value
  if (!(await confirmAsync(`Run the deploy script on ${hosts.join(', ')}?`, 'Deploy'))) return
  try {
    await queueReaderApi.deploy(hosts)
    notify.success('Deploy command sent')
    deploy.visible = false
    setTimeout(refreshInstances, 5000)
  } catch (e) {
    notify.error('Deploy failed', e)
  }
}

// jar upload (sent as plain base64)
const jar = reactive({ server: null, file: null, uploading: false })
async function uploadJar() {
  if (!jar.file.name.toLowerCase().endsWith('.jar')) {
    notify.warning('Please choose a .jar file')
    return
  }
  jar.uploading = true
  try {
    const base64 = await new Promise((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result).replace(/^data:[^,]*,/, ''))
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(jar.file)
    })
    const server = serverByNumber(jar.server)
    if (await run(() => queueServerApi.uploadJar(jar.server, server?.hostName || '', jar.file.name, base64), 'JAR uploaded')) {
      refreshJarVersions()
    }
  } catch (e) {
    notify.error('Upload failed', e)
  } finally {
    jar.uploading = false
  }
}

// init
;(async () => {
  await findQuickestRabbitNode()
  await refreshAll()
})()
</script>
