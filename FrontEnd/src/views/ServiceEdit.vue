<template>
  <div>
    <div class="page-header">
      <h1>Service Details</h1>
      <span class="sub" v-if="service.name">{{ service.name }}</span>
      <div class="actions">
        <Button label="ODS Search" severity="secondary" size="small" @click="odsVisible = true" />
        <Button v-if="service.uuid" label="Exchanges" severity="secondary" size="small" @click="nav.viewExchanges(service)" />
        <Button v-if="service.uuid" label="Delete All Data" severity="danger" size="small" @click="deleteData" />
        <Button v-if="service.uuid" label="Delete Service" severity="danger" size="small" @click="deleteService" />
        <Button label="Save" severity="success" size="small" @click="save(false)" :loading="saving" />
        <Button label="Save & Close" severity="warn" size="small" @click="save(true)" :loading="saving" />
        <Button label="Cancel" severity="secondary" size="small" @click="close" />
      </div>
    </div>

    <div v-if="loading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>

    <template v-else>
      <div class="card">
        <div class="grid-2">
          <div class="field">
            <label>ODS Code / Local Identifier
              <Button label="Auto-fill from ODS" size="small" severity="success" text @click="autoFillDetailsFromOds" />
            </label>
            <InputText v-model="service.localIdentifier" />
          </div>
          <div class="field">
            <label>CCG/Parent Code</label>
            <InputText v-model="service.ccgCode" />
          </div>
          <div class="field">
            <label>Name</label>
            <InputText v-model="service.name" />
          </div>
          <div class="field">
            <label>Postcode</label>
            <InputText v-model="service.postcode" />
          </div>
          <div class="field">
            <label>Alias</label>
            <InputText v-model="service.alias" placeholder="Also known as" />
          </div>
          <div class="field">
            <label>Organisation type</label>
            <Select v-model="service.organisationTypeCode" :options="organisationTypeOptions" optionLabel="label"
                    optionValue="value" showClear filter placeholder=" " />
          </div>

          <div class="field">
            <label>Tags</label>
            <div class="field-inline">
              <Select v-model="comboSelectedTagName" :options="tagNameList" placeholder="Select a tag" filter
                      style="min-width: 14rem" />
              <Button label="Add Tag" size="small" severity="success" @click="addTag" />
              <Button label="New Tag" size="small" severity="success" outlined @click="addNewTag" />
            </div>
            <div class="scroll-200">
              <table class="simple">
                <tbody>
                  <tr v-for="tag in tags" :key="tag.name">
                    <td style="width: 30%">{{ tag.name }}</td>
                    <td><InputText v-model="tag.value" placeholder="Tag value" size="small" class="w-full" /></td>
                    <td style="width: 3rem">
                      <Button icon="fa-solid fa-xmark" severity="danger" size="small" text class="hover-show"
                              @click="removeTag(tag)" />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="field">
            <label>Publisher DB Name
              <Button label="New Config" size="small" severity="success" text @click="addNewConfigName" />
            </label>
            <Select v-model="service.publisherConfigName" :options="publisherConfigList" showClear filter
                    placeholder=" " />
          </div>
        </div>
      </div>

      <div class="card">
        <h2>Publisher Systems
          <Button label="Add Publisher" size="small" severity="success" text @click="addEndpoint" />
        </h2>
        <div class="grid-2">
          <div>
            <table class="simple">
              <thead>
                <tr><th>System</th><th>Publisher Status</th><th></th></tr>
              </thead>
              <tbody>
                <tr v-for="(item, i) in service.endpoints" :key="i" @click="selectedEndpoint = item"
                    :class="{ 'row-selected': item === selectedEndpoint }" style="cursor: pointer">
                  <td>{{ getSystem(item.systemUuid)?.name }}</td>
                  <td>{{ item.endpoint }}</td>
                  <td style="width: 3rem">
                    <Button icon="fa-solid fa-xmark" severity="danger" size="small" text class="hover-show"
                            @click.stop="removeEndpoint(i)" />
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="selectedEndpoint">
            <div class="field mb-1">
              <label>System</label>
              <Select v-model="selectedEndpoint.systemUuid" :options="systems || []" optionLabel="name"
                      optionValue="uuid" @change="systemSelected" filter />
            </div>
            <div class="field">
              <label>Publisher Status
                <i class="fa-solid fa-circle-info muted"
                   v-tooltip="'Draft = Messaging API will reject; Auto-fail = Inbound QR will fail transforms; Bulk = Exchanges routed to bulk queues'"></i>
              </label>
              <Select v-model="selectedEndpoint.endpoint" :options="PUBLISHER_MODES" optionLabel="label" optionValue="value" />
            </div>
          </div>
        </div>
      </div>

      <div class="card" v-if="service.uuid">
        <div style="display: grid; grid-template-columns: 1fr 3fr; gap: 1.5rem">
          <div>
            <h2>DPA Status</h2>
            <table class="simple">
              <thead><tr><th>When</th><th>Has DPA</th></tr></thead>
              <tbody>
                <tr><td>Current</td><td>{{ currentHasDpa }}</td></tr>
                <tr v-for="(h, i) in historyHasDpa" :key="i">
                  <td>{{ formatYYYYMMDDHHMM(h.date) }}*</td><td>{{ h.hasDpa }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div>
            <h2>Subscriber Status</h2>
            <table class="simple">
              <thead><tr><th style="width: 12rem">When</th><th>Subscriber Configs</th></tr></thead>
              <tbody>
                <tr><td>Current</td><td>{{ joinArr(currentSubscribers) }}</td></tr>
                <tr v-for="(h, i) in historySubscribers" :key="i">
                  <td>{{ formatYYYYMMDDHHMM(h.date) }}*</td><td>{{ joinArr(h.subscribers) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <p class="muted mt-1">* history is based on change detection by Messaging API and Protocol Queue Readers, not
          audit trails from DSM. Current values come from DataSteward.</p>
        <Button :label="showDsm ? 'Hide DataSteward details' : 'Show DataSteward details'" size="small" text
                @click="showDsm = !showDsm" />
        <pre v-if="showDsm" class="block">{{ dsmDetailsJson }}</pre>
      </div>
    </template>

    <OdsSearchDialog v-model:visible="odsVisible" />
    <SystemPickerDialog v-model:visible="nav.pickerVisible.value" :service="nav.pickerService.value"
                        @select="nav.onSystemPicked" />

    <Dialog v-model:visible="promptVisible" modal :header="promptTitle" :style="{ width: '24rem' }">
      <InputText v-model="promptValue" class="w-full" autofocus @keyup.enter="promptOk" />
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="promptVisible = false" />
        <Button label="OK" @click="promptOk" />
      </template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { serviceApi, systemApi } from '@/services/api'
import { caches, getTagNames, getPublisherConfigNames, PUBLISHER_MODES } from '@/services/serviceStore'
import { formatYYYYMMDDHHMM } from '@/utils/format'
import { useNotify, useConfirmAsync, orderBy } from '@/utils/ui'
import { useServiceNavigation } from '@/utils/navigation'
import OdsSearchDialog from '@/components/OdsSearchDialog.vue'
import SystemPickerDialog from '@/components/SystemPickerDialog.vue'

const route = useRoute()
const router = useRouter()
const notify = useNotify()
const confirmAsync = useConfirmAsync()
const nav = useServiceNavigation()

const service = ref({ uuid: null, name: '', endpoints: [], tags: {} })
const tags = ref([])
const systems = ref(null)
const organisationTypes = ref(null)
const selectedEndpoint = ref(null)
const comboSelectedTagName = ref('')
const loading = ref(false)
const saving = ref(false)
const odsVisible = ref(false)
const showDsm = ref(false)

const dsmDetailsJson = ref('')
const currentHasDpa = ref(null)
const currentSubscribers = ref([])
const historyHasDpa = ref([])
const historySubscribers = ref([])

const tagNameList = computed(() => getTagNames())
const publisherConfigList = computed(() => {
  const list = [...getPublisherConfigNames()]
  if (service.value.publisherConfigName && !list.includes(service.value.publisherConfigName)) {
    list.push(service.value.publisherConfigName)
  }
  return list
})
const organisationTypeOptions = computed(() => {
  if (!organisationTypes.value) {
    return service.value.organisationTypeCode
      ? [{ value: service.value.organisationTypeCode, label: service.value.organisationTypeDesc }]
      : []
  }
  return organisationTypes.value.map((o) => ({ value: o.code, label: o.description }))
})

function getSystem(uuid) {
  return (systems.value || []).find((s) => s.uuid === uuid) || null
}

async function load(uuid) {
  loading.value = true
  try {
    service.value = await serviceApi.get(uuid)
    if (!service.value.endpoints) service.value.endpoints = []
    if (!service.value.tags) service.value.tags = {}
    populateTags()
    populateDsmDetails()
    serviceApi.getDpaHistory(uuid).then((r) => (historyHasDpa.value = r.reverse())).catch((e) => notify.error('Error getting DPA history', e))
    serviceApi.getSubscriberHistory(uuid).then((r) => (historySubscribers.value = r.reverse())).catch((e) => notify.error('Error getting subscriber history', e))
  } catch (e) {
    notify.error('Error loading', e)
  } finally {
    loading.value = false
  }
}

function populateTags() {
  tags.value = Object.entries(service.value.tags || {}).map(([name, value]) => ({ name, value }))
}

function saveTags() {
  const obj = {}
  for (const tag of tags.value) obj[tag.name] = tag.value
  service.value.tags = obj
}

async function populateDsmDetails() {
  const odsCode = service.value.localIdentifier
  if (!odsCode) return
  try {
    const result = await serviceApi.getDsmDetails(odsCode)
    if (result) {
      dsmDetailsJson.value = JSON.stringify(result, null, 2)
      currentHasDpa.value = result.hasDPA
      currentSubscribers.value = (result.distributionProjects || [])
        .map((p) => p.configName)
        .filter(Boolean)
        .sort()
    } else {
      dsmDetailsJson.value = 'no DSM details found'
    }
  } catch (e) {
    notify.error('Error getting DSM data', e)
  }
}

async function save(close) {
  saveTags()
  saving.value = true
  try {
    const warning = await serviceApi.validateSave(service.value)
    if (warning) {
      notify.warning(warning)
      return
    }
    const saved = await serviceApi.save(service.value)
    const isNew = !service.value.uuid
    service.value.uuid = saved.uuid
    notify.success('Item saved')
    if (close) {
      router.back()
    } else if (isNew) {
      router.replace(`/serviceEdit/edit/${saved.uuid}`)
    }
  } catch (e) {
    notify.error('Error saving', e)
  } finally {
    saving.value = false
  }
}

function close() {
  router.back()
}

function addEndpoint() {
  const e = { endpoint: 'Publisher_Bulk', systemUuid: null, technicalInterfaceUuid: null }
  service.value.endpoints.push(e)
  selectedEndpoint.value = e
}

function removeEndpoint(i) {
  const removed = service.value.endpoints.splice(i, 1)[0]
  if (selectedEndpoint.value === removed) selectedEndpoint.value = null
}

// we only have one technical interface per system, so it is set automatically with the system
function systemSelected() {
  const e = selectedEndpoint.value
  const system = getSystem(e.systemUuid)
  e.technicalInterfaceUuid = system?.technicalInterface?.[0]?.uuid || null
}

async function deleteService() {
  if (!(await confirmAsync('Are you sure you want to delete the Service?', 'Delete Service', 'Yes', true))) return
  try {
    const result = await serviceApi.delete(service.value.uuid)
    if (result) {
      notify.error(result)
    } else {
      notify.success('Service deleted')
      router.push('/services')
    }
  } catch (e) {
    notify.error('Failed to delete Service', e)
  }
}

async function deleteData() {
  if (!(await confirmAsync('Are you sure you want to delete all data for this Service?', 'Delete Data', 'Yes', true))) return
  try {
    await serviceApi.deleteData(service.value.uuid)
    notify.success('Data deletion queued up')
    router.back()
  } catch (e) {
    notify.error('Failed to delete data', e)
  }
}

async function autoFillDetailsFromOds() {
  const odsCode = service.value.localIdentifier
  if (!odsCode) {
    notify.error('No ODS code entered')
    return
  }
  try {
    const result = await serviceApi.getOpenOdsRecord(odsCode)
    if (!result || !result.organisationName) {
      notify.error('No ODS record found for ' + odsCode)
      return
    }
    const s = service.value
    s.name = result.organisationName || ''
    s.postcode = result.postcode || ''
    const parents = result.parents
    if (parents) {
      const keys = Object.keys(parents)
      s.ccgCode = ''
      if (keys.length === 1) {
        s.ccgCode = keys[0]
      } else {
        for (const key of keys) {
          const val = String(parents[key]).toUpperCase()
          if (val.indexOf('STRATEGIC HEALTH AUTHORITY') === -1 && val.indexOf('GENOMIC') === -1) {
            s.ccgCode = key
          }
        }
      }
    }
    const orgTypeName = result.organisationType
    if (orgTypeName) {
      s.organisationTypeCode = ''
      s.organisationTypeDesc = ''
      const t = (organisationTypes.value || []).find((o) => o.name === orgTypeName)
      if (t) {
        s.organisationTypeCode = t.code
        s.organisationTypeDesc = t.description
      }
    }
    populateDsmDetails()
  } catch (e) {
    notify.error('Failed to find ODS record', e)
  }
}

// simple prompt dialog (the old UI used window.prompt)
const promptVisible = ref(false)
const promptTitle = ref('')
const promptValue = ref('')
let promptCallback = null
function prompt(title, cb) {
  promptTitle.value = title
  promptValue.value = ''
  promptCallback = cb
  promptVisible.value = true
}
function promptOk() {
  promptVisible.value = false
  const v = (promptValue.value || '').trim()
  if (v && promptCallback) promptCallback(v)
}

function addNewConfigName() {
  prompt('Config name', (name) => {
    const list = caches.publisherConfigNames || []
    if (list.some((c) => c.toLowerCase() === name.toLowerCase())) {
      notify.warning('Config name already used')
      return
    }
    caches.publisherConfigNames = [...list, name]
    service.value.publisherConfigName = name
  })
}

function addNewTag() {
  prompt('Tag name', (name) => {
    const list = caches.tagNames || []
    if (list.some((t) => t.toLowerCase() === name.toLowerCase())) {
      notify.warning('Tag name already used')
      return
    }
    caches.tagNames = [...list, name]
    comboSelectedTagName.value = name
    addTag()
  })
}

function addTag() {
  if (!comboSelectedTagName.value) {
    notify.warning('No tag selected')
    return
  }
  if (tags.value.some((t) => t.name === comboSelectedTagName.value)) {
    notify.warning('Tag already added')
    return
  }
  tags.value = orderBy([...tags.value, { name: comboSelectedTagName.value, value: '' }], (t) => t.name.toLowerCase())
}

function removeTag(tag) {
  tags.value = tags.value.filter((t) => t !== tag)
}

function joinArr(arr) {
  return arr ? arr.join(', ') : '' + arr
}

// init
serviceApi.getOrganisationTypeList().then((r) => (organisationTypes.value = r)).catch(() => notify.error('Failed to retrieve organisation type list'))
systemApi.getSystems()
  .then((r) => (systems.value = orderBy(r, (s) => (s.name || '').toLowerCase())))
  .catch((e) => notify.error('Failed to load systems. Ensure Systems are configured.', e))
getTagNames()
getPublisherConfigNames()

if (route.params.itemAction === 'edit' && route.params.itemUuid) {
  load(route.params.itemUuid)
} else {
  tags.value = []
}
</script>
