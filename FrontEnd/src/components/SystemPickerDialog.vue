<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal header="Select Data Feed"
          :style="{ width: '24rem' }">
    <div v-if="!systems" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
    <div v-else class="picker">
      <Button v-for="s in systems" :key="s.uuid" :label="s.name" severity="success" size="small"
              @click="$emit('select', s.uuid)" />
    </div>
    <template #footer>
      <Button label="Close" severity="secondary" @click="$emit('update:visible', false)" />
    </template>
  </Dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { systemApi } from '@/services/api'
import { useNotify } from '@/utils/ui'

// eds-ui SystemPickerDialog: choose which of a service's systems (data feeds) to look at
const props = defineProps({ visible: Boolean, service: Object })
defineEmits(['update:visible', 'select'])
const notify = useNotify()
const systems = ref(null)

watch(
  () => [props.visible, props.service],
  async ([visible, service]) => {
    if (!visible || !service) return
    systems.value = null
    try {
      const all = await systemApi.getSystems()
      const ids = new Set((service.endpoints || []).map((e) => e.systemUuid))
      systems.value = all.filter((s) => ids.has(s.uuid))
    } catch (e) {
      notify.error('Failed to load systems', e)
      systems.value = []
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.picker {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
</style>
