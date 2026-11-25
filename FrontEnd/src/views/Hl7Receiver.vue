<template>
  <div>
    <div class="page-header">
      <h1>HL7 Receiver</h1>
      <span class="sub">{{ getDateDiffSimpleFromNow(statusLastRefreshed) }}</span>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="success" size="small" :loading="refreshing" @click="refresh" />
      </div>
    </div>

    <div v-if="!channels && refreshing" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>

    <div class="card" v-for="channel in channels || []" :key="channel.id">
      <h2><b>{{ channel.name }}</b> <span class="muted">(ID {{ channel.id }})</span></h2>
      <div class="two">
        <div>
          <h3>Hospital &rarr; HL7 Receiver</h3>
          <template v-if="channel.lastMessageId">
            <div>
              <Tag v-if="isLastMessageReceivedTooOld(channel)" severity="danger" value="Warning" v-tooltip="'No recent messages received'" />
              Last Message Received: {{ formatYYYYMMDDHHMMSS(channel.lastMessageReceived) }}
            </div>
            <div>Last Message ID: {{ channel.lastMessageId }}</div>
          </template>
          <template v-else>
            <div><Tag severity="danger" value="Warning" v-tooltip="'No messages received'" /> Last Message Received: NEVER</div>
            <div>Last Message ID: n/a</div>
          </template>
        </div>

        <div>
          <h3>HL7 Receiver &rarr; FHIR &rarr; Messaging API</h3>
          <template v-if="channel.errorMessageId">
            <div><Tag severity="danger" value="Warning" v-tooltip="'Message in error'" /> Status: ERROR ({{ channel.transformQueueSize }} messages in queue)</div>
            <p class="bold mt-1">Message in Error:</p>
            <div>Received: {{ formatYYYYMMDDHHMMSS(channel.errorMessageReceived) }}</div>
            <div>Type: {{ channel.errorMessageType }}</div>
            <div>ID: {{ channel.errorMessageId }}</div>
            <pre class="block">{{ channel.errorMessage }}</pre>
          </template>
          <template v-else-if="channel.transformQueueSize > 0">
            <div>Status: {{ channel.paused ? 'PAUSED' : 'WORKING' }} ({{ channel.transformQueueSize }} messages in queue)</div>
            <p class="bold mt-1">First Message in Queue:</p>
            <div>
              <Tag v-if="isCurrentMessageTooOld(channel)" severity="danger" value="Warning" v-tooltip="'Message being processed from >1 day ago'" />
              Received: {{ formatYYYYMMDDHHMMSS(channel.transformQueueFirstMessageDate) }}
            </div>
            <div>Type: {{ channel.transformQueueFirstMessageType }}</div>
            <div>ID: {{ channel.transformQueueFirstMessageId }}</div>
          </template>
          <div v-else>Status: OK</div>

          <table v-if="channel.transformQueueSize" class="simple mt-1">
            <thead><tr><th>Date</th><th>Status</th><th>Error</th></tr></thead>
            <tbody>
              <tr v-for="(s, i) in channel.transformQueueFirstStatus || []" :key="i">
                <td class="nowrap">{{ formatYYYYMMDDHHMMSS(s.date) }}</td>
                <td>{{ s.status }}</td>
                <td><pre v-if="s.error" class="block">{{ s.error }}</pre></td>
              </tr>
            </tbody>
          </table>

          <div class="field-inline mt-1">
            <Tag v-if="channel.paused" severity="warn" value="PAUSED!" />
            <Tag v-else severity="success" value="Not Paused" />
            <Button label="Toggle Pause" size="small" severity="secondary" outlined @click="pauseChannel(channel.id, !channel.paused)" />
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { hl7ReceiverApi } from '@/services/api'
import { formatYYYYMMDDHHMMSS, getDateDiffSimpleFromNow } from '@/utils/format'
import { useNotify } from '@/utils/ui'

const notify = useNotify()
const channels = ref(null)
const refreshing = ref(false)
const statusLastRefreshed = ref(new Date())

async function refresh() {
  refreshing.value = true
  statusLastRefreshed.value = new Date()
  try {
    channels.value = await hl7ReceiverApi.getStatus()
  } catch (e) {
    notify.error('Failed get HL7 Receiver status', e)
  } finally {
    refreshing.value = false
  }
}

async function pauseChannel(id, pause) {
  try {
    await hl7ReceiverApi.pauseChannel(id, pause)
    refresh()
  } catch (e) {
    notify.error('Failed to pause/unpause', e)
  }
}

const isLastMessageReceivedTooOld = (c) => c.lastMessageReceived < statusLastRefreshed.value.getTime() - 1000 * 60 * 5
const isCurrentMessageTooOld = (c) => c.transformQueueFirstMessageDate < statusLastRefreshed.value.getTime() - 1000 * 60 * 60 * 24

refresh()
</script>

<style scoped>
.two {
  display: grid;
  grid-template-columns: 1fr 2fr;
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
