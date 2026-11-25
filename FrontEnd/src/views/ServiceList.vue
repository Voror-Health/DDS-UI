<template>
  <div>
    <div class="page-header">
      <h1>Publishers</h1>
      <span class="sub" v-if="services">(showing {{ filtered.length }} of {{ services.length }})</span>
      <div class="actions">
        <Button label="ODS Search" severity="secondary" size="small" @click="odsVisible = true" />
        <Button label="Save to CSV" severity="secondary" size="small" @click="saveToCsv" :disabled="!services" />
        <Button label="Reset Filters" severity="secondary" size="small" @click="clearFilters" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="secondary" size="small" @click="refresh" />
        <Button label="Add new" severity="success" size="small" @click="$router.push('/serviceEdit/add')" />
      </div>
    </div>

    <ServiceFilters />

    <div class="card">
      <div v-if="!services" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading publishers...</div>
      <DataTable v-else :value="filtered" size="small" stripedRows dataKey="uuid" paginator :rows="100"
                 :rowsPerPageOptions="[50, 100, 250, 1000]" scrollable>
        <Column header="Name" style="width: 25%">
          <template #body="{ data }">
            <a @click="nav.editService(data.uuid)">{{ data.name }}</a>
            <i v-if="data.alias" class="fa-solid fa-circle-info muted ml-1" v-tooltip="data.alias"></i>
          </template>
        </Column>
        <Column field="localIdentifier" header="ID" />
        <Column header="Parent">
          <template #body="{ data }">
            <span v-tooltip="getCcgName(data.ccgCode)">{{ data.ccgCode }}</span>
          </template>
        </Column>
        <Column field="publisherConfigName" header="Publisher DB" />
        <Column header="Latest Cutoff">
          <template #body="{ data }">
            <div v-for="(status, i) in data.systemStatuses || []" :key="i"
                 v-tooltip="formatLatestCutoffTooltip(data, status)">
              {{ f.serviceShowDateFilter ? formatYYYYMMDDHHMM(status.lastReceivedExtractCutoff) : formatLatestCutoff(data, status) }}
            </div>
          </template>
        </Column>
        <Column header="Status">
          <template #body="{ data }">
            <div v-for="(status, i) in data.systemStatuses || []" :key="i"
                 v-tooltip="formatProcessingStatusTooltip(data, status)" :class="statusClass(status)">
              {{ formatProcessingStatus(data, status) }}
              <i v-if="status.processingInError" class="fa-solid fa-triangle-exclamation"></i>
            </div>
          </template>
        </Column>
        <Column header="Tags" style="width: 22%">
          <template #body="{ data }">
            {{ tagPrefix(data) }}
            <i v-if="tagStr(data).length > TAG_LIMIT" class="fa-solid fa-circle-info muted" v-tooltip="tagStr(data)"></i>
          </template>
        </Column>
        <Column header="">
          <template #body="{ data }">
            <Button label="Exchanges" size="small" severity="secondary" outlined class="hover-show"
                    @click="nav.viewExchanges(data)" />
          </template>
        </Column>
      </DataTable>
    </div>

    <OdsSearchDialog v-model:visible="odsVisible" />
    <SystemPickerDialog v-model:visible="nav.pickerVisible.value" :service="nav.pickerService.value"
                        @select="nav.onSystemPicked" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { serviceApi } from '@/services/api'
import { saveFile } from '@/services/http'
import {
  filters as f, clearFilters as resetFilters, applyFiltering, getTagNames, getCcgName, createTagStr,
  formatLatestCutoff, formatLatestCutoffTooltip, formatProcessingStatus, formatProcessingStatusTooltip,
} from '@/services/serviceStore'
import { formatYYYYMMDDHHMM } from '@/utils/format'
import { useNotify, csvLine } from '@/utils/ui'
import { useServiceNavigation } from '@/utils/navigation'
import OdsSearchDialog from '@/components/OdsSearchDialog.vue'
import SystemPickerDialog from '@/components/SystemPickerDialog.vue'
import ServiceFilters from '@/components/ServiceFilters.vue'

const TAG_LIMIT = 35
const notify = useNotify()
const nav = useServiceNavigation()
const services = ref(null)
const odsVisible = ref(false)

const filtered = computed(() => applyFiltering(services.value, false))

const tagStrCache = new Map()
function tagStr(service) {
  if (!tagStrCache.has(service.uuid) || !tagStrCache.get(service.uuid)) {
    tagStrCache.set(service.uuid, createTagStr(service))
  }
  return tagStrCache.get(service.uuid)
}
function tagPrefix(service) {
  const s = tagStr(service)
  return s && s.length > TAG_LIMIT ? s.substring(0, TAG_LIMIT) + '...' : s
}

function statusClass(status) {
  if (status.processingInError) return 'err'
  if (!status.processingUpToDate && status.lastReceivedExtract) return 'warn'
  return ''
}

async function refresh() {
  try {
    services.value = await serviceApi.getAll()
    tagStrCache.clear()
  } catch (e) {
    notify.error('Failed to load services', e)
  }
}

function clearFilters() {
  resetFilters()
}

function saveToCsv() {
  const lines = [csvLine(['Name', 'ID', 'Parent', 'Latest Data Cutoff', 'Status', 'Tags'])]
  for (const service of filtered.value) {
    const cols = [service.name, service.localIdentifier, service.ccgCode, '', '', tagStr(service)]
    if (service.systemStatuses) {
      for (const status of service.systemStatuses) {
        cols[3] = formatLatestCutoff(service, status)
        cols[4] = formatProcessingStatus(service, status)
        lines.push(csvLine(cols))
      }
    } else {
      lines.push(csvLine(cols))
    }
  }
  saveFile(lines.join('\r\n'), 'Services.csv', 'text/plain')
}

onMounted(() => {
  refresh()
  getTagNames()
})
</script>
