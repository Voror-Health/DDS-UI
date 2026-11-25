<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal
          :header="publisher ? `${publisher.odsCode || ''} ${publisher.name}` : ''" :style="{ width: '60rem' }">
    <template v-if="publisher">
      <div v-for="tagName in tagNames" :key="tagName">{{ tagName }} {{ publisher.tags[tagName] || '' }}</div>

      <div v-for="(system, i) in publisher.systemStatus" :key="i" class="sys" :class="panelClass(system)">
        <div class="sys-head">{{ system.name }}</div>
        <div class="sys-body">
          <div v-if="system.processingInError" class="mb-1">
            Inbound transform error: <b>{{ system.processingInErrorMessage }}</b> <Tag severity="danger" value="Error" />
          </div>
          <table class="simple">
            <thead><tr><th>Event</th><th>Last Timestamp</th><th>Last Extract Cut-off</th></tr></thead>
            <tbody>
              <tr>
                <td>Extract received</td>
                <td>{{ d(system.lastReceivedTimestamp) }}</td>
                <td>{{ d(system.lastReceivedExtractCutoff) }}</td>
              </tr>
              <tr>
                <td>Inbound transform completed</td>
                <td>{{ d(system.lastProcessedInTimestamp) }}</td>
                <td>{{ d(system.lastProcessedInExtractCutoff) }} <BehindTag :days="system.inboundBehindDays" :text="system.inboundBehindWarning" /></td>
              </tr>
              <tr>
                <td>Outbound transform completed</td>
                <td>{{ d(system.lastProcessedOutTimestamp) }}</td>
                <td>{{ d(system.lastProcessedOutExtractCutoff) }} <BehindTag :days="system.outboundBehindDays" :text="system.outboundBehindWarning" /></td>
              </tr>
              <tr v-if="system.writeOutError">
                <td>Outbound write error</td>
                <td colspan="2">{{ system.writeOutErrorMessage }} <Tag severity="danger" value="Error" /></td>
              </tr>
              <tr>
                <td>Outbound writing completed</td>
                <td>{{ d(system.lastWriteOutTimestamp) }}</td>
                <td>{{ d(system.lastWriteOutExtractCutoff) }} <BehindTag :days="system.outboundWriteBehindDays" :text="system.outboundWriteBehindWarning" /></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </template>
    <template #footer>
      <Button label="Close" @click="$emit('update:visible', false)" />
    </template>
  </Dialog>
</template>

<script setup>
import { computed } from 'vue'
import { formatYYYYMMDDHHMM } from '@/utils/format'
import { getTagNames } from '@/services/serviceStore'
import BehindTag from './BehindTag.vue'

// eds-ui subscribers/PublisherStatusDialog
const props = defineProps({ visible: Boolean, publisher: Object })
defineEmits(['update:visible'])

const tagNames = computed(() => {
  const tags = props.publisher?.tags
  if (!tags) return []
  return getTagNames().filter((t) => Object.prototype.hasOwnProperty.call(tags, t))
})

const d = (ms) => (ms ? formatYYYYMMDDHHMM(ms) : 'Never')

function panelClass(s) {
  if (!s.lastReceivedExtractCutoff) return 'info'
  if (s.processingInError || !s.lastProcessedInExtractCutoff || !s.lastProcessedOutExtractCutoff || s.writeOutError) return 'danger'
  if (s.inboundBehindDays > 1 || s.outboundBehindDays > 1 || s.outboundWriteBehindDays > 1) return 'danger'
  if (s.inboundBehindDays > 0 || s.outboundBehindDays > 0 || s.outboundWriteBehindDays > 0) return 'warning'
  return 'success'
}
</script>

<style scoped>
.sys {
  border: 1px solid var(--dds-border);
  border-radius: 4px;
  margin-top: 0.75rem;
}

.sys-head {
  padding: 0.4rem 0.6rem;
  font-weight: 600;
}

.sys-body {
  padding: 0.5rem 0.6rem;
}

.sys.info .sys-head {
  background: #e3f0fa;
}

.sys.danger .sys-head {
  background: #fdecea;
}

.sys.warning .sys-head {
  background: #fff6e0;
}

.sys.success .sys-head {
  background: #e7f6ea;
}
</style>
