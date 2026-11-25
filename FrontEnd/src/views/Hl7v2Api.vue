<template>
  <div>
    <div class="page-header">
      <h1>HL7v2 API</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refresh" />
      </div>
    </div>

    <div v-if="!status && refreshing" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>

    <div class="card" v-for="(channel, i) in status || []" :key="i">
      <h2><b>{{ channel.name }}</b> <span class="muted">({{ channel.odsCode }})</span></h2>
      <div class="two">
        <div>
          <h3>Hospital &rarr; HL7v2 API</h3>
          <template v-if="channel.lastReceivedMessageId">
            <div>
              Last Message Received: {{ formatYYYYMMDDHHMMSS(channel.lastReceivedTimestamp) }}
              <Tag v-if="isTooOld(channel.lastReceivedTimestamp)" severity="danger" value="Warning" v-tooltip="'No recent messages received'" />
            </div>
            <div>Last Message ID: {{ channel.lastReceivedMessageId }} (#{{ channel.lastReceivedId }})</div>
          </template>
          <template v-else>
            <div>Last Message Received: NEVER <Tag severity="danger" value="Warning" v-tooltip="'No messages received'" /></div>
            <div>Last Message ID: n/a</div>
          </template>
        </div>
        <div>
          <h3>HL7v2 API &rarr; Messaging API</h3>
          <div>
            Status:
            <template v-if="applicationStatus">
              <Tag v-if="isApplicationDead(applicationStatus)" severity="danger" v-tooltip="applicationStatus.isBusyDetail">
                <i class="fa-solid fa-triangle-exclamation"></i>&nbsp;{{ getDateDiffDesc(new Date(applicationStatus.timestmp), statusLastRefreshed, 2) }}
              </Tag>
              <i v-else-if="applicationStatus.isBusy" class="fa-solid fa-play" v-tooltip="applicationStatus.isBusyDetail"></i>
              <i v-else class="fa-solid fa-pause" v-tooltip="'Application running but idle'"></i>
            </template>
            <span v-else>Unknown</span>
          </div>
          <div>Queue Size: {{ channel.queueSize }}</div>
          <template v-if="channel.queueSize > 0">
            <p class="bold mt-1">First Message in Queue:</p>
            <div>
              Received: {{ formatYYYYMMDDHHMMSS(channel.queueOldestTimestamp) }}
              <Tag v-if="isTooOld(channel.queueOldestTimestamp)" severity="danger" value="Warning" v-tooltip="'Message being processed from >5 minutes ago'" />
            </div>
            <div>ID: {{ channel.queueMessageId }} (#{{ channel.queueId }})</div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { hl7v2Api, queueReaderApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS, getDateDiffDesc, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify } from '@/utils/ui'

const notify = useNotify()
const status = ref(null)
const applicationStatus = ref(null)
const refreshing = ref(false)
const statusLastRefreshed = ref(new Date())

async function refresh() {
  refreshing.value = true
  statusLastRefreshed.value = new Date()
  queueReaderApi
    .getStatus('hl7v2_msg_sender')
    .then((r) => (applicationStatus.value = r?.[0] || null))
    .catch((e) => notify.error('Failed get application status', e))
  try {
    status.value = await hl7v2Api.getStatus()
  } catch (e) {
    notify.error('Failed get HL7v2 API status', e)
  } finally {
    refreshing.value = false
  }
}

// more than five minutes old means something is wrong
const isTooOld = (ts) => ts < statusLastRefreshed.value.getTime() - 1000 * 60 * 5
const isApplicationDead = (s) => s.timestmp < statusLastRefreshed.value.getTime() - 1000 * 60 * 2

refresh()
</script>

<style scoped>
.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 1.5rem;
}

h3 {
  font-size: 0.95rem;
  margin: 0 0 0.4rem;
}

@media (max-width: 900px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
