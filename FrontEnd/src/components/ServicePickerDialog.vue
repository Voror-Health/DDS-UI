<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal header="Service" :style="{ width: '36rem' }">
    <div class="field mb-1">
      <label>Search Criteria</label>
      <div class="field-inline">
        <InputText v-model="searchData" placeholder="Search term" class="w-full" autofocus @keyup.enter="search" />
        <Button icon="fa-solid fa-magnifying-glass" @click="search" />
      </div>
    </div>
    <div class="field mb-1">
      <label>Matches</label>
      <div class="box">
        <div v-for="m in searchResults" :key="m.uuid" class="item">
          {{ m.name }}
          <Button icon="fa-solid fa-plus" size="small" severity="success" text @click="add(m)" />
        </div>
      </div>
    </div>
    <div class="field">
      <label>Selection</label>
      <div class="box">
        <div v-for="s in selection" :key="s.uuid" class="item">
          {{ s.name }}
          <Button icon="fa-solid fa-xmark" size="small" severity="danger" text @click="remove(s)" />
        </div>
      </div>
    </div>
    <template #footer>
      <Button label="Cancel" severity="secondary" @click="$emit('update:visible', false)" />
      <Button label="Ok" @click="ok" />
    </template>
  </Dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { serviceApi } from '@/services/api'
import { useNotify } from '@/utils/ui'

// eds-ui services/ServicePickerDialog: search for services and build a selection
const props = defineProps({ visible: Boolean, services: { type: Array, default: () => [] } })
const emit = defineEmits(['update:visible', 'ok'])
const notify = useNotify()
const searchData = ref('')
const searchResults = ref([])
const selection = ref([])

watch(() => props.visible, (v) => {
  if (v) selection.value = [...props.services]
})

async function search() {
  try {
    searchResults.value = await serviceApi.search(searchData.value)
  } catch (e) {
    notify.error('Search failed', e)
  }
}

function add(m) {
  if (!selection.value.some((s) => s.uuid === m.uuid)) selection.value.push(m)
}

function remove(s) {
  selection.value = selection.value.filter((x) => x !== s)
}

function ok() {
  emit('ok', selection.value)
  emit('update:visible', false)
}
</script>

<style scoped>
.box {
  border: 1px solid var(--dds-border);
  border-radius: 4px;
  height: 150px;
  overflow-y: auto;
  padding: 0.25rem;
}

.item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 0.25rem;
}

.item:nth-child(odd) {
  background: #f7f8fa;
}
</style>
