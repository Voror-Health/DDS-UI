<template>
  <div>
    <div class="page-header">
      <h1>Publisher Errors</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <span class="sub" v-if="summaries">(showing {{ filtered.length }} of {{ summaries.length }})</span>
      <div class="actions">
        <Button label="Reset Filters" severity="secondary" size="small" @click="clearFilters" />
        <Button label="Check All" severity="secondary" size="small" @click="checkAll" />
        <Button :label="`Re-queue Checked (${checkedCount} selected)`" severity="success" size="small"
                :loading="busy" @click="requeueChecked" :disabled="!admin" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" @click="refreshSummaries" />
      </div>
    </div>

    <ServiceFilters errorsView />

    <div class="cols">
      <div class="card">
        <div v-if="!summaries" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
        <DataTable v-else :value="filtered" size="small" stripedRows @row-click="selectSummary($event.data)"
                   :rowClass="(r) => (r === selected ? 'row-selected' : '')">
          <Column style="width: 2.5rem">
            <template #body="{ data }"><Checkbox v-model="data.checked" binary @click.stop /></template>
          </Column>
          <Column header="Name">
            <template #body="{ data }">
              <a @click.stop="$router.push(`/serviceEdit/edit/${data.service.uuid}`)">{{ data.service.name }}</a>
            </template>
          </Column>
          <Column header="ID"><template #body="{ data }">{{ data.service.localIdentifier }}</template></Column>
          <Column header="Tags">
            <template #body="{ data }">
              {{ tagPrefix(data.service) }}
              <i v-if="tagStr(data.service).length > TAG_LIMIT" class="fa-solid fa-circle-info muted"
                 v-tooltip="tagStr(data.service)"></i>
            </template>
          </Column>
          <Column field="systemName" header="System" />
          <Column field="countExchanges" header="Exchanges in Error" />
        </DataTable>
      </div>

      <div class="card" v-if="selected">
        <h2>
          Errors for {{ selected.service.name }} {{ selected.service.localIdentifier }}
          <span class="mono muted">{{ selected.service.uuid }}</span>
        </h2>
        <div class="mb-1">
          <Button label="Exchanges" size="small" severity="secondary" outlined @click="nav.viewExchanges(selected.service)" />
          <Button label="Re-queue" size="small" severity="success" class="ml-1" :loading="busy" @click="rerunAll(selected)" :disabled="!admin" />
        </div>
        <div v-if="selected.service.notes" class="mb-1">{{ selected.service.notes }}</div>

        <div class="field-inline mb-1">
          <Button icon="fa-solid fa-angles-left" text size="small" :disabled="index <= 1" @click="go(1)" />
          <Button icon="fa-solid fa-angle-left" text size="small" :disabled="index <= 1" @click="go(index - 1)" />
          <span>Exchange {{ index }} of {{ selected.countExchanges }}</span>
          <Button icon="fa-solid fa-angle-right" text size="small" :disabled="index >= selected.countExchanges" @click="go(index + 1)" />
          <Button icon="fa-solid fa-angles-right" text size="small" :disabled="index >= selected.countExchanges" @click="go(selected.countExchanges)" />
        </div>

        <div v-if="detail">
          <h3 class="mono" style="font-size: 0.95rem">Exchange {{ detail.exchangeId }}</h3>
          <div v-if="detail.resubmitted" class="bold mb-1">Has been resubmitted, so should be in inbound queue</div>
          <pre v-if="detail.lines && detail.lines.length > 0" class="block">{{ detail.lines.join('\n') }}</pre>
          <div v-if="(!detail.lines || detail.lines.length === 0) && !detail.transformEnd" class="muted">
            <div>Previous errors not shown once the inbound transform has started</div>
            <div>To view original errors, use the Publishers page</div>
            <div>Transform started at {{ formatYYYYMMDDHHMMSS(detail.transformStart) }}</div>
          </div>
        </div>
      </div>
    </div>

    <SystemPickerDialog v-model:visible="nav.pickerVisible.value" :service="nav.pickerService.value"
                        @select="nav.onSystemPicked" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { exchangeAuditApi } from '@/services/api'
import { applyFiltering, clearFilters as resetFilters, createTagStr, getTagNames } from '@/services/serviceStore'
import { formatYYYYMMDDHHMMSS, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify, orderBy, isAdmin } from '@/utils/ui'
import { useServiceNavigation } from '@/utils/navigation'
import ServiceFilters from '@/components/ServiceFilters.vue'
import SystemPickerDialog from '@/components/SystemPickerDialog.vue'

const TAG_LIMIT = 10
const notify = useNotify()
const nav = useServiceNavigation()
const admin = isAdmin()

const summaries = ref(null)
const selected = ref(null)
const index = ref(1)
const detail = ref(null)
const busy = ref(false)
const statusLastRefreshed = ref(null)

// filter on the services, then list every error summary of each matching service (eds-ui applyFiltering)
const filtered = computed(() => {
  if (!summaries.value) return []
  const services = []
  const byService = new Map()
  for (const summary of summaries.value) {
    if (!byService.has(summary.service.uuid)) {
      byService.set(summary.service.uuid, [])
      services.push(summary.service)
    }
    byService.get(summary.service.uuid).push(summary)
  }
  return applyFiltering(services, true).flatMap((s) => byService.get(s.uuid))
})

const checkedCount = computed(() => filtered.value.filter((s) => s.checked).length)

const tagCache = new Map()
const tagStr = (service) => {
  if (!tagCache.get(service.uuid)) tagCache.set(service.uuid, createTagStr(service))
  return tagCache.get(service.uuid)
}
const tagPrefix = (service) => {
  const str = tagStr(service)
  return str && str.length > TAG_LIMIT ? str.substring(0, TAG_LIMIT) + '...' : str
}

async function refreshSummaries() {
  statusLastRefreshed.value = new Date()
  summaries.value = null
  selected.value = null
  try {
    const result = await exchangeAuditApi.getTransformErrorSummaries()
    summaries.value = orderBy(result, (s) => (s.service.name || '').toLowerCase()).map((s) => ({ ...s, checked: false }))
    if (result.length === 0) notify.success('No transform errors found')
  } catch (e) {
    summaries.value = []
    notify.error('Failed to load transform errors', e)
  }
}

function selectSummary(summary) {
  selected.value = summary
  go(1)
}

async function go(i) {
  index.value = i
  detail.value = null
  const s = selected.value
  const exchangeId = s.exchangeIds[i - 1]
  try {
    const data = await exchangeAuditApi.getInboundTransformAudits(s.service.uuid, s.systemId, exchangeId, false)
    detail.value = data[0]
  } catch (e) {
    notify.error('Failed to retrieve transform audit', e)
  }
}

async function rerunAll(summary) {
  busy.value = true
  try {
    await exchangeAuditApi.rerunAllExchangesInError(summary.service.uuid, summary.systemId)
    notify.success('Successfully posted to exchange')
  } catch (e) {
    notify.error('Failed to post to exchange', e)
  } finally {
    busy.value = false
  }
}

function clearFilters() {
  resetFilters()
}

function checkAll() {
  for (const s of summaries.value || []) s.checked = true
}

async function requeueChecked() {
  const checked = filtered.value.filter((s) => s.checked)
  if (checked.length === 0) {
    notify.error('No services checked')
    return
  }
  busy.value = true
  try {
    await exchangeAuditApi.rerunAllExchangesInErrorForServices(checked)
    notify.success('Successfully posted to exchange')
  } catch (e) {
    notify.error('Failed to post to exchange', e)
  } finally {
    busy.value = false
  }
}

getTagNames()
refreshSummaries()
</script>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1rem;
  align-items: start;
}

@media (max-width: 1100px) {
  .cols {
    grid-template-columns: 1fr;
  }
}
</style>
