<template>
  <TextDialog :visible="visible" @update:visible="$emit('update:visible', $event)" title="Transform Errors"
              :text="lines.join('\n')" :loading="loading" width="80vw" />
</template>

<script setup>
import { ref, watch } from 'vue'
import TextDialog from './TextDialog.vue'
import { exchangeAuditApi } from '@/services/api'
import { useNotify } from '@/utils/ui'

// eds-ui exchangeAudit/TransformErrorsDialog: the error text of one transform audit
const props = defineProps({
  visible: Boolean,
  serviceId: String,
  systemId: String,
  exchangeId: String,
  version: String,
})
defineEmits(['update:visible'])
const notify = useNotify()
const lines = ref([])
const loading = ref(false)

watch(
  () => [props.visible, props.exchangeId, props.version],
  async ([visible]) => {
    if (!visible) return
    lines.value = []
    loading.value = true
    try {
      lines.value = (await exchangeAuditApi.getTransformErrorLines(props.serviceId, props.systemId, props.exchangeId, props.version)) || []
    } catch (e) {
      notify.error('Failed to retrieve error lines', e)
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)
</script>
