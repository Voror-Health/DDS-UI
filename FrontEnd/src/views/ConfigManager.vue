<template>
  <div>
    <div class="page-header">
      <h1>Config Manager</h1>
      <div class="actions" v-if="admin">
        <Button label="Add Record" severity="secondary" size="small" :disabled="refreshing" @click="newRecord(null)" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refreshWithValidation" />
      </div>
    </div>

    <Message v-if="!admin" severity="warn">The Config Manager shows every configuration record, including passwords and client secrets, so it needs the vhp_ui_admin role.</Message>

    <div v-else-if="records" class="cols">
      <div class="card">
        <div class="field mb-1">
          <label>App ID</label>
          <div class="field-inline">
            <Select v-model="f.filterAppId" :options="appIds" showClear filter placeholder="All" style="min-width: 12rem" />
            <Checkbox v-model="f.filterAppIdIncludeGlobal" binary inputId="incGlobal" /><label for="incGlobal">Include Global</label>
          </div>
        </div>
        <div class="field mb-1">
          <label>Search</label>
          <InputText v-model="f.filterSearchText" placeholder="Regex" />
          <div class="field-inline">
            <Checkbox v-model="f.filterSearchOnAppId" binary inputId="sApp" /><label for="sApp">App ID</label>
            <Checkbox v-model="f.filterSearchOnConfigId" binary inputId="sCfg" /><label for="sCfg">Config ID</label>
            <Checkbox v-model="f.filterSearchOnData" binary inputId="sData" /><label for="sData">Data</label>
          </div>
        </div>
        <DataTable :value="filtered" size="small" stripedRows scrollable scrollHeight="55vh"
                   @row-click="selectRecord($event.data)" :rowClass="(r) => (r === selected ? 'row-selected' : '')" class="clickable-rows">
          <Column field="appId" header="App ID" />
          <Column header="Config ID">
            <template #body="{ data }">
              <i v-if="newRecords.has(data) || changedRecords.has(data)" class="fa-solid fa-floppy-disk warn" v-tooltip="'Unsaved changes'"></i>
              {{ data.configId }}
            </template>
          </Column>
        </DataTable>
        <div class="muted mt-1">{{ records.length }} records found ({{ filtered.length }} shown)</div>
      </div>

      <div class="card" v-if="selected">
        <div class="grid-2 mb-1">
          <div class="field">
            <label>App ID</label>
            <InputText v-model="selected.appId" :disabled="!newRecords.has(selected)" />
          </div>
          <div class="field">
            <label>Config ID</label>
            <InputText v-model="selected.configId" :disabled="!newRecords.has(selected)" />
          </div>
        </div>
        <Textarea v-model="selected.configData" rows="22" class="w-full mono editor" wrap="off" spellcheck="false" @input="changed(selected)" />
        <Message v-if="validMessage" severity="success" class="mt-1">{{ validMessage }}</Message>
        <Message v-if="invalidMessage" severity="error" class="mt-1">{{ invalidMessage }}</Message>
        <div class="field-inline mt-1" style="flex-wrap: wrap">
          <Button label="Validate JSON" size="small" severity="secondary" outlined @click="validateJson" />
          <Button label="Validate XML" size="small" severity="secondary" outlined @click="validateXml" />
          <Button label="View History" size="small" severity="secondary" outlined :disabled="!!history[key(selected)]" @click="loadHistory(selected)" />
          <Button label="Copy Record" size="small" severity="secondary" outlined @click="newRecord(selected)" />
          <Button label="Save Record" size="small" severity="success" @click="saveRecord" />
          <Button label="Delete Record" size="small" severity="danger" @click="deleteRecord" />
        </div>

        <div v-if="history[key(selected)]" class="history mt-1">
          <div class="scroll-400">
            <table class="simple">
              <thead><tr><th>Changed</th></tr></thead>
              <tbody>
                <tr v-for="(h, i) in history[key(selected)]" :key="i" @click="selectedHistory = h"
                    :class="{ 'row-selected': h === selectedHistory }" style="cursor: pointer">
                  <td class="nowrap">{{ formatYYYYMMDDHHMMSS(h.dtChanged) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="selectedHistory" class="grid-2">
            <div class="field"><label>Changed From</label><Textarea :modelValue="selectedHistory.changedFrom" rows="12" disabled class="mono editor" wrap="off" /></div>
            <div class="field"><label>Changed To</label><Textarea :modelValue="selectedHistory.changedTo" rows="12" disabled class="mono editor" wrap="off" /></div>
          </div>
        </div>
      </div>
    </div>
    <div v-else class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { configManagerApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS } from '@/utils/format'
import { useNotify, useConfirmAsync, orderBy, isAdmin } from '@/utils/ui'

const f = reactive(
  (window.__ddsConfigManagerFilters ||= {
    filterAppId: null, filterAppIdIncludeGlobal: false, filterSearchText: '',
    filterSearchOnAppId: true, filterSearchOnConfigId: true, filterSearchOnData: false,
  }),
)
const notify = useNotify()
const confirmAsync = useConfirmAsync()
const admin = isAdmin()

const records = ref(null)
const selected = ref(null)
const newRecords = reactive(new Set())
const changedRecords = reactive(new Set())
const history = reactive({})
const selectedHistory = ref(null)
const validMessage = ref('')
const invalidMessage = ref('')
const refreshing = ref(false)

const key = (r) => r.appId + '_' + r.configId
const appIds = computed(() => [...new Set((records.value || []).map((r) => r.appId))])

const filtered = computed(() => {
  let regex
  if (f.filterSearchText) {
    try {
      new RegExp(f.filterSearchText)
      regex = f.filterSearchText.toLowerCase()
    } catch {
      /* ignore invalid regex */
    }
  }
  return (records.value || []).filter((r) => {
    // unsaved new records always show
    if (newRecords.has(r)) return true
    if (f.filterAppId && f.filterAppId !== r.appId && (r.appId !== 'global' || !f.filterAppIdIncludeGlobal)) return false
    if (regex) {
      const m = (on, v) => on && v && v.toLowerCase().match(regex)
      if (!(m(f.filterSearchOnAppId, r.appId) || m(f.filterSearchOnConfigId, r.configId) || m(f.filterSearchOnData, r.configData))) return false
    }
    return true
  })
})

async function refresh() {
  refreshing.value = true
  newRecords.clear()
  changedRecords.clear()
  selected.value = null
  selectedHistory.value = null
  for (const k of Object.keys(history)) delete history[k]
  clearMessages()
  try {
    const result = await configManagerApi.getRecords()
    records.value = orderBy(orderBy(result, (r) => (r.configId || '').toLowerCase()), (r) => (r.appId || '').toLowerCase())
  } catch (e) {
    notify.error('Failed get config records', e)
  } finally {
    refreshing.value = false
  }
}

async function refreshWithValidation() {
  if (newRecords.size || changedRecords.size) {
    if (!(await confirmAsync('Refreshing will lose unsaved changes. Continue?', 'Refresh'))) return
  }
  refresh()
}

function clearMessages() {
  validMessage.value = ''
  invalidMessage.value = ''
}

function selectRecord(r) {
  selected.value = r
  selectedHistory.value = null
  clearMessages()
}

function changed(r) {
  clearMessages()
  changedRecords.add(r)
}

function newRecord(copyFrom) {
  const r = copyFrom
    ? { appId: copyFrom.appId, configId: copyFrom.configId + ' (copy)', configData: copyFrom.configData }
    : { appId: '<app_id>', configId: '<config_id>', configData: '' }
  records.value.push(r)
  newRecords.add(r)
  selectRecord(records.value[records.value.length - 1])
}

async function saveRecord() {
  const r = selected.value
  if (!r.appId || r.appId === '<app_id>') return notify.error('App ID not set')
  if (!r.configId || r.configId === '<config_id>') return notify.error('Config ID not set')
  if (!r.configData) return notify.error('Config data not set')
  if (records.value.some((o) => o !== r && o.appId === r.appId && o.configId === r.configId)) return notify.error('Duplicate record')
  try {
    await configManagerApi.saveRecord({ appId: r.appId, configId: r.configId, configData: r.configData })
    notify.success('Saved config record')
    newRecords.delete(r)
    changedRecords.delete(r)
  } catch (e) {
    notify.error('Failed to save config record', e)
  }
}

async function deleteRecord() {
  const r = selected.value
  if (!(await confirmAsync('Are you sure you want to delete this config record?', 'Delete Record', 'Yes', true))) return
  if (newRecords.has(r)) {
    records.value = records.value.filter((x) => x !== r)
    newRecords.delete(r)
    selected.value = null
    return
  }
  try {
    await configManagerApi.deleteRecord({ appId: r.appId, configId: r.configId, configData: r.configData })
    notify.success('Deleted config record')
    refresh()
  } catch (e) {
    notify.error('Failed to delete config record', e)
  }
}

function validateJson() {
  clearMessages()
  const str = selected.value.configData
  try {
    const formatted = JSON.stringify(JSON.parse(str), null, 4)
    if (formatted === str) {
      validMessage.value = 'Valid JSON'
    } else {
      selected.value.configData = formatted
      changedRecords.add(selected.value)
      validMessage.value = 'Valid JSON (reformatted)'
    }
  } catch (e) {
    invalidMessage.value = 'Invalid JSON: ' + e.message
  }
}

function validateXml() {
  clearMessages()
  const dom = new DOMParser().parseFromString(selected.value.configData || '', 'text/xml')
  const err = dom.getElementsByTagName('parsererror')
  if (err.length > 0) invalidMessage.value = err[0].textContent
  else validMessage.value = 'Valid XML'
}

async function loadHistory(r) {
  try {
    history[key(r)] = (await configManagerApi.getHistory(r.appId, r.configId)).reverse() // most recent first
  } catch (e) {
    notify.error('Failed get config history', e)
  }
}

if (admin) refresh()
</script>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) 2fr;
  gap: 1rem;
  align-items: start;
}

.editor {
  font-size: 0.8rem;
  white-space: pre;
  overflow: auto;
}

.history {
  display: grid;
  grid-template-columns: 12rem 1fr;
  gap: 1rem;
}

.clickable-rows :deep(tbody tr) {
  cursor: pointer;
}

@media (max-width: 1100px) {
  .cols,
  .history {
    grid-template-columns: 1fr;
  }
}
</style>
