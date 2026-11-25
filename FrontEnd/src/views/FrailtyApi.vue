<template>
  <div>
    <div class="page-header">
      <h1>Subscriber API Status</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button label="Download Monthly Frailty Stats" severity="success" size="small" :loading="downloading" @click="downloadMonthlyStats" />
      </div>
    </div>

    <div class="cols">
      <div class="card">
        <div class="field mb-1">
          <label>Flag Codes</label>
          <Select v-model="flagCode" :options="flagOptions" optionLabel="label" optionValue="value" filter />
        </div>
        <div class="field mb-1">
          <label>Minutes Back</label>
          <InputNumber v-model="minutesBack" :min="1" />
        </div>
        <div class="field mb-1">
          <label>Group By</label>
          <Select v-model="groupBy" :options="groupByOptions" optionLabel="label" optionValue="value" />
        </div>
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refresh" />
      </div>

      <div class="card">
        <DataTable :value="stats || []" size="small" stripedRows :loading="refreshing">
          <Column header="Time">
            <template #body="{ data }"><span class="nowrap">{{ formatYYYYMMDDHHMM(data.dFrom) }} - {{ formatHHMM(data.dTo) }}</span></template>
          </Column>
          <Column field="matchedAndFrail" header="Matched &amp; Flagged" />
          <Column field="matchedAndNotFrail" header="Matched &amp; Not Flagged" />
          <Column field="errorNotMatched" header="Not Matched" />
          <Column header="Other Error">
            <template #body="{ data }">
              {{ data.errorOther }} <Tag v-if="data.errorOther > 0" severity="danger" value="Warning" v-tooltip="'Unexpected error(s)'" />
            </template>
          </Column>
          <Column header="Total">
            <template #body="{ data }">
              {{ data.total }} <Tag v-if="isSustainedOutage(data)" severity="danger" value="Warning" v-tooltip="'>=5 min with zero traffic'" />
            </template>
          </Column>
        </DataTable>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { frailtyApi } from '@/services/api'
import { saveFile } from '@/services/http'
import { formatYYYYMMDDHHMM, formatHHMM, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify } from '@/utils/ui'

const notify = useNotify()
const minutesBack = ref(20)
const groupBy = ref('minute')
const flagCode = ref('')
const flags = ref([])
const stats = ref(null)
const refreshing = ref(false)
const downloading = ref(false)
const statusLastRefreshed = ref(null)

const groupByOptions = [
  ['second', 'Second'], ['minute', 'Minute'], ['5minute', '5 Minutes'], ['10minute', '10 Minutes'],
  ['30minute', '30 Minutes'], ['hour', 'Hour'],
].map(([value, label]) => ({ value, label }))
const flagOptions = computed(() => [{ value: '', label: 'All' }, ...flags.value.map((f) => ({ value: f.code, label: `${f.term} (${f.code})` }))])

async function refresh() {
  statusLastRefreshed.value = new Date()
  refreshing.value = true
  try {
    stats.value = await frailtyApi.getRecentStats(minutesBack.value, groupBy.value, flagCode.value)
  } catch (e) {
    notify.error('Failed get Frailty API stats', e)
  } finally {
    refreshing.value = false
  }
}

async function downloadMonthlyStats() {
  downloading.value = true
  try {
    saveFile(await frailtyApi.downloadMonthlyStats(), 'FrailtyStats.csv', 'text/plain')
  } catch (e) {
    notify.error('Failed get monthly Frailty API stats', e)
  } finally {
    downloading.value = false
  }
}

// one quiet minute is normal; five minutes in a row with no traffic means something is wrong
function isSustainedOutage(queryStat) {
  if (queryStat.total > 0) return false
  const threshold = 5 * 60 * 1000
  let cumulative = 0
  let matched = false
  for (const stat of stats.value || []) {
    if (stat.total == 0) {
      if (stat === queryStat) matched = true
      cumulative += stat.dTo - stat.dFrom
      if (cumulative >= threshold && matched) return true
    } else {
      cumulative = 0
      matched = false
    }
  }
  return false
}

frailtyApi.getSubscriberFlags().then((r) => (flags.value = r || [])).catch(() => {})
refresh()
</script>

<style scoped>
.cols {
  display: grid;
  grid-template-columns: 18rem 1fr;
  gap: 1rem;
  align-items: start;
}

@media (max-width: 900px) {
  .cols {
    grid-template-columns: 1fr;
  }
}
</style>
