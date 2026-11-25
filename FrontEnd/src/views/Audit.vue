<template>
  <div>
    <div class="page-header">
      <h1>Audit Monitor</h1>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refresh" />
      </div>
    </div>

    <div class="card">
      <div class="filters">
        <div class="field" style="width: 14rem">
          <label>Application</label>
          <Select v-model="module" :options="modules || []" placeholder="Select Application..." @change="refresh" />
        </div>
        <div class="field" style="width: 16rem">
          <label>User</label>
          <Select v-model="user" :options="users || []" optionLabel="label" optionValue="uuid" filter placeholder="Select User..." @change="refresh" />
        </div>
        <div class="field" style="width: 11rem">
          <label>Month</label>
          <DatePicker v-model="month" view="month" dateFormat="M-yy" showIcon @update:modelValue="refresh" />
        </div>
        <div class="field" style="width: 14rem" v-if="user && module">
          <label>Domain</label>
          <Select v-model="submodule" :options="['', ...(submodules || [])]" :optionLabel="(o) => o || 'All'" placeholder="All" />
        </div>
        <div class="field" style="width: 12rem" v-if="user && module && submodule">
          <label>Action</label>
          <Select v-model="action" :options="['', ...(actions || [])]" :optionLabel="(o) => o || 'All'" placeholder="All" />
        </div>
      </div>
    </div>

    <div class="card">
      <h2>Recent Audit Events <i v-if="loading" class="fa-solid fa-rotate spin"></i></h2>
      <div v-if="!user || !module" class="muted">Choose an application and a user.</div>
      <DataTable v-else :value="filtered" size="small" stripedRows paginator :rows="100" :loading="loading">
        <Column header="Timestamp"><template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMMSS(data.timestamp) }}</span></template></Column>
        <Column field="module" header="Module" />
        <Column field="subModule" header="Submodule" />
        <Column field="action" header="Action" />
        <Column header="Details">
          <template #body="{ data }">
            <span v-if="!data.data">None</span>
            <a v-else @click="viewData(data)">{{ dataTitle(data.data) }}</a>
          </template>
        </Column>
      </DataTable>
    </div>

    <TextDialog v-model:visible="detailVisible" :title="'Audit event : ' + detailTitle" :text="detailText" width="50rem" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { auditApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS } from '@/utils/format'
import { useNotify, orderBy } from '@/utils/ui'
import TextDialog from '@/components/TextDialog.vue'

// Users come from Casdoor (they used to come from the Keycloak admin API). Note: the old page never
// actually loaded the events (the call was commented out); this one does.
const notify = useNotify()
const modules = ref(null)
const users = ref(null)
const submodules = ref(null)
const actions = ref(null)
const module = ref('')
const user = ref('')
const submodule = ref('')
const action = ref('')
const month = ref(new Date(new Date().getFullYear(), new Date().getMonth(), 1))
const events = ref([])
const loading = ref(false)

const filtered = computed(() =>
  events.value.filter((e) => {
    if (!submodule.value) return true
    if (e.subModule !== submodule.value) return false
    return !action.value || e.action === action.value
  }),
)

async function refresh() {
  events.value = []
  if (!user.value || !module.value || !month.value) return
  auditApi.getSubmodules(module.value).then((r) => (submodules.value = r)).catch((e) => notify.error('Error loading sub modules', e))
  loading.value = true
  try {
    const m = new Date(month.value)
    m.setDate(1)
    m.setHours(0, 0, 0, 0)
    events.value = orderBy(await auditApi.getUserAudit(module.value, user.value, m), (e) => e.timestamp, true)
  } catch (e) {
    notify.error('Error loading audit events', e)
  } finally {
    loading.value = false
  }
}

const dataTitle = (data) => (data ? (data.match(/[^\r\n]+/g) || [''])[0] : 'None')

const detailVisible = ref(false)
const detailTitle = ref('')
const detailText = ref('')
function viewData(event) {
  detailTitle.value = dataTitle(event.data)
  let text = event.data
  try {
    text = JSON.stringify(JSON.parse(event.data), null, 2)
  } catch {
    /* not JSON: show as is */
  }
  detailText.value = text
  detailVisible.value = true
}

auditApi.getModules().then((r) => (modules.value = r)).catch((e) => notify.error('Error loading modules', e))
auditApi.getActions().then((r) => (actions.value = r)).catch((e) => notify.error('Error loading actions', e))
auditApi
  .getUsers()
  .then((r) => (users.value = orderBy(r, (u) => (u.username || '').toLowerCase()).map((u) => ({ ...u, label: u.displayName && u.displayName !== u.username ? `${u.displayName} (${u.username})` : u.username }))))
  .catch((e) => notify.error('Error loading users', e))
</script>
