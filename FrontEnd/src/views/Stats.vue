<template>
  <div>
    <div class="page-header">
      <h1>Data Service Statistics</h1>
      <div class="actions">
        <Button label="Refresh Stats" icon="fa-solid fa-rotate" severity="success" size="small" :disabled="!services.length" @click="refresh" />
        <Button label="Download Patient Counts" severity="success" size="small" :loading="downloading" @click="downloadPatientCounts" />
      </div>
    </div>

    <div class="card">
      <h2>Filter by Services <Button label="Edit" size="small" severity="success" text @click="pickerVisible = true" /></h2>
      <div v-if="services.length" class="chips">
        <Tag v-for="s in services" :key="s.uuid" :value="s.name" severity="secondary" />
      </div>
      <div v-else class="muted">Choose the services to show statistics for.</div>
    </div>

    <div v-if="loading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading Statistics...</div>

    <template v-if="stats">
      <Panel header="Patients" toggleable class="mb-1">
        <div class="two">
          <table class="simple">
            <thead><tr><th>Organisation</th><th>Total Patients</th><th>Active Patients</th><th>Inactive Patients</th></tr></thead>
            <tbody>
              <tr v-for="s in stats" :key="s.serviceId + s.systemId">
                <td>{{ serviceName(s.serviceId) }}</td>
                <td>{{ formatNumber(s.patientStatistics?.totalCount) }}</td>
                <td>{{ formatNumber(s.patientStatistics?.activeCount) }}</td>
                <td>{{ formatNumber((s.patientStatistics?.totalCount || 0) - (s.patientStatistics?.activeCount || 0)) }}</td>
              </tr>
            </tbody>
          </table>
          <!-- simple bar chart (the old page used a jQuery highcharts table) -->
          <div class="bars">
            <div v-for="s in stats" :key="s.serviceId + s.systemId" class="bar-row">
              <span class="bar-label">{{ serviceName(s.serviceId) }}</span>
              <span class="bar-track">
                <span class="bar total" :style="{ width: pct(s.patientStatistics?.totalCount, maxPatients) }" v-tooltip="'Total ' + formatNumber(s.patientStatistics?.totalCount)"></span>
                <span class="bar active" :style="{ width: pct(s.patientStatistics?.activeCount, maxPatients) }" v-tooltip="'Active ' + formatNumber(s.patientStatistics?.activeCount)"></span>
              </span>
            </div>
            <div class="muted legend"><span class="sw total"></span> total <span class="sw active"></span> active</div>
          </div>
        </div>
      </Panel>

      <Panel header="Clinical Resources" toggleable>
        <div style="overflow-x: auto">
          <table class="simple">
            <thead>
              <tr><th>Organisation</th><th v-for="r in resourceNames" :key="r">{{ r }}</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in stats" :key="s.serviceId + s.systemId">
                <td>{{ serviceName(s.serviceId) }}</td>
                <td v-for="(r, i) in s.resourceStatistics || []" :key="i">{{ formatNumber(r.totalCount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </Panel>
    </template>

    <ServicePickerDialog v-model:visible="pickerVisible" :services="services" @ok="onServicesPicked" />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { statsApi } from '@/services/api'
import { saveFile } from '@/services/http'
import { formatNumber } from '@/utils/format'
import { useNotify } from '@/utils/ui'
import ServicePickerDialog from '@/components/ServicePickerDialog.vue'

const notify = useNotify()
const services = ref([])
const stats = ref(null)
const loading = ref(false)
const downloading = ref(false)
const pickerVisible = ref(false)

const serviceName = (id) => services.value.find((s) => s.uuid === id)?.name || id
const resourceNames = computed(() => (stats.value?.[0]?.resourceStatistics || []).map((r) => r.resourceName))
const maxPatients = computed(() => Math.max(1, ...(stats.value || []).map((s) => s.patientStatistics?.totalCount || 0)))
const pct = (n, max) => Math.round(((n || 0) / max) * 100) + '%'

function onServicesPicked(list) {
  services.value = list
  refresh()
}

async function refresh() {
  if (!services.value.length) return
  loading.value = true
  stats.value = null
  try {
    stats.value = await statsApi.getStorageStatistics(services.value)
  } catch (e) {
    notify.error('Failed to load statistics', e)
  } finally {
    loading.value = false
  }
}

async function downloadPatientCounts() {
  downloading.value = true
  try {
    saveFile(await statsApi.downloadPatientCounts(), 'PatientCounts.csv', 'text/plain')
  } catch (e) {
    notify.error('Failed get patient counts', e)
  } finally {
    downloading.value = false
  }
}
</script>

<style scoped>
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.3rem;
}

.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

.bar-row {
  display: grid;
  grid-template-columns: 12rem 1fr;
  gap: 0.5rem;
  align-items: center;
  margin-bottom: 0.3rem;
}

.bar-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  position: relative;
  height: 14px;
  background: #f0f2f5;
}

.bar {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
}

.total,
.sw.total {
  background: #9ec5e8;
}

.active,
.sw.active {
  background: #0b6aa2;
}

.sw {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-left: 0.6rem;
}

@media (max-width: 1000px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
