<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal :header="title"
          :style="{ width: width }" maximizable>
    <div v-if="loading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
    <pre v-else class="block">{{ text }}</pre>
    <template #footer>
      <Button label="Copy" icon="fa-solid fa-copy" severity="secondary" outlined @click="copy" :disabled="loading" />
      <Button label="Close" @click="$emit('update:visible', false)" />
    </template>
  </Dialog>
</template>

<script setup>
import { useToast } from 'primevue/usetoast'
import { copyToClipboard } from '@/utils/format'

const props = defineProps({
  visible: Boolean,
  title: { type: String, default: '' },
  text: { type: String, default: '' },
  loading: Boolean,
  width: { type: String, default: '70vw' },
})
defineEmits(['update:visible'])
const toast = useToast()

function copy() {
  copyToClipboard(props.text, toast)
}
</script>
