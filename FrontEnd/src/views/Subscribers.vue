<template>
  <div>
    <div class="page-header">
      <h1>Subscriber Status</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button label="Save to CSV" severity="secondary" size="small" :disabled="refreshing || !subscribers" @click="saveToCsv" />
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refresh" />
      </div>
    </div>

    <div class="card">
      <div v-if="!subscribers" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
      <DataTable v-else :value="subscribers" size="small" stripedRows @row-click="open($event.data)" class="clickable-rows">
        <Column header="Subscriber" style="width: 20%">
          <template #body="{ data }">
            <div class="bold">{{ data.name }}</div>
            <div class="muted">{{ data.description }}</div>
          </template>
        </Column>
        <Column>
          <template #header>Details <i class="fa-solid fa-circle-info muted ml-1" v-tooltip="'Internal or Remote / Compass version / PI or not'"></i></template>
          <template #body="{ data }">
            <span class="nowrap">
              <span v-tooltip="remoteIdDesc(data)">{{ locationDesc(data) }}</span> /
              {{ schemaDesc(data) }} / {{ piDesc(data) }}
            </span>
          </template>
        </Column>
        <Column field="numPublishers" header="Publishers" />
        <Column>
          <template #header>Receipt Status <i class="fa-solid fa-circle-info muted ml-1" v-tooltip="'<1d / <2d / 2d+'"></i></template>
          <template #body="{ data }"><Triple :a="data.receivedOneDay" :b="data.receivedTwoDays" :c="data.receivedMoreDays" /></template>
        </Column>
        <Column header="Inbound Err">
          <template #body="{ data }">
            {{ data.inboundError }} <Tag v-if="data.inboundError > 0" severity="danger" value="Error" v-tooltip="'Inbound processing errors'" />
          </template>
        </Column>
        <Column>
          <template #header>Inbound Status <i class="fa-solid fa-circle-info muted ml-1" v-tooltip="'OK / 1d behind received / 2d+ behind received'"></i></template>
          <template #body="{ data }">
            <Triple :a="data.inboundUpToDate" :b="data.inboundOneDay" :c="data.inboundMoreDays" />
            <Tag v-if="data.inboundMoreDays > 0" severity="danger" value="Warning" v-tooltip="'Inbound processing 2d+ behind'" />
          </template>
        </Column>
        <Column>
          <template #header>Outbound Status <i class="fa-solid fa-circle-info muted ml-1" v-tooltip="'OK / 1d behind received / 2d+ behind received'"></i></template>
          <template #body="{ data }">
            <Triple :a="data.outboundUpToDate" :b="data.outboundOneDay" :c="data.outboundMoreDays" />
            <Tag v-if="data.outboundMoreDays > 0" severity="danger" value="Warning" v-tooltip="'Outbound processing 2d+ behind'" />
          </template>
        </Column>
        <Column>
          <template #header>Outbound Write <i class="fa-solid fa-circle-info muted ml-1" v-tooltip="'OK / 1d behind received / 2d+ behind received'"></i></template>
          <template #body="{ data }">
            <Triple :a="data.writeOutUpToDate" :b="data.writeOutOneDay" :c="data.writeOutMoreDays" />
            <Tag v-if="data.writeOutMoreDays > 0" severity="danger" value="Warning" v-tooltip="'Outbound write 2d+ behind'" />
          </template>
        </Column>
        <Column header="Outbound Err">
          <template #body="{ data }">
            {{ data.outboundError }} <Tag v-if="data.outboundError > 0" severity="danger" value="Error" v-tooltip="'Outbound writing errors'" />
          </template>
        </Column>
      </DataTable>
    </div>
  </div>
</template>

<script setup>
import { h, ref } from 'vue'
import { useRouter } from 'vue-router'
import { subscriberApi } from '@/services/api'
import { saveFile } from '@/services/http'
import { getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify, orderBy, csvLine } from '@/utils/ui'
import { locationDesc, schemaDesc, piDesc, remoteIdDesc } from '@/services/subscriberFormat'

// three fixed-width counts side by side, as the old page laid them out
const Triple = (props) =>
  h('span', { class: 'triple mono' }, [h('span', props.a ?? ''), h('span', props.b ?? ''), h('span', props.c ?? '')])
Triple.props = ['a', 'b', 'c']

const router = useRouter()
const notify = useNotify()
const subscribers = ref(null)
const refreshing = ref(false)
const statusLastRefreshed = ref(null)

async function refresh() {
  statusLastRefreshed.value = new Date()
  refreshing.value = true
  try {
    subscribers.value = orderBy(await subscriberApi.getSubscribers(), (s) => s.name)
  } catch (e) {
    notify.error('Failed get subscriber details', e)
  } finally {
    refreshing.value = false
  }
}

function open(sub) {
  router.push(`/subscribers/${encodeURIComponent(sub.name)}`)
}

function saveToCsv() {
  const yn = (b) => (b ? 'Y' : 'N')
  const lines = [
    csvLine([
      'Subscriber Name', 'Description', 'Location', 'Remote Subscriber ID', 'Schema', 'PI', 'Excludes Test Patients',
      'Excludes Blank NHS Number Patients', 'Exclude NHS Number Regex', 'Cohort', 'Subscriber DB', 'Subscriber DB Name',
      'Subscriber Transform DB', 'Subscriber Transform DB Name', 'Publishers', 'Inbound Errors', 'Transform In Up-to-date',
      'Transform In 1d Behind', 'Transform In 2d+ Behind', 'Transform Out Up-to-date', 'Transform Out 1d Behind',
      'Transform Out 2d+ Behind', 'Write Out Up-to-date', 'Write Out 1d Behind', 'Write Out 2d+ Behind', 'Outbound Errors',
    ]),
  ]
  for (const c of subscribers.value) {
    lines.push(
      csvLine([
        c.name, c.description, locationDesc(c), c.remoteSubscriberId || '', schemaDesc(c), c.deidentified ? 'N' : 'Y',
        yn(c.excludeTestPatients), yn(c.excludePatientsWithoutNhsNumber), c.excludeNhsNumberRegex || '', c.cohortDesc,
        c.subscriberDatabase || '', c.subscriberDatabaseName || '', c.subscriberTransformDatabase,
        c.subscriberTransformDatabaseName, c.numPublishers, c.inboundError, c.inboundUpToDate, c.inboundOneDay,
        c.inboundMoreDays, c.outboundUpToDate, c.outboundOneDay, c.outboundMoreDays, c.writeOutUpToDate, c.writeOutOneDay,
        c.writeOutMoreDays, c.outboundError,
      ]),
    )
  }
  saveFile(lines.join('\r\n'), 'subscriber_status.csv', 'text/plain')
}

refresh()
</script>

<style scoped>
:deep(.triple) {
  display: inline-grid;
  grid-template-columns: repeat(3, 2.6rem);
  margin-right: 0.3rem;
}

.clickable-rows :deep(tbody tr) {
  cursor: pointer;
}
</style>
