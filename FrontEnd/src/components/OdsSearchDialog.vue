<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal header="ODS Search"
          :style="{ width: '50rem' }">
    <div class="field-inline mb-1">
      <label class="field-label" for="odsSearchCode">ODS Code</label>
      <InputText id="odsSearchCode" v-model="odsCode" autofocus @keyup.enter="search(true)" />
      <Button label="Search ODS" @click="search(true)" />
      <Button label="Search DSM" severity="secondary" @click="search(false)" />
      <i v-if="searching" class="fa-solid fa-rotate spin"></i>
    </div>
    <pre class="block" v-if="resultStr">{{ resultStr }}</pre>
    <template #footer>
      <Button label="Close" @click="$emit('update:visible', false)" />
    </template>
  </Dialog>
</template>

<script setup>
import { ref } from 'vue'
import { serviceApi } from '@/services/api'
import { useNotify } from '@/utils/ui'

defineProps({ visible: Boolean })
defineEmits(['update:visible'])

const notify = useNotify()
const odsCode = ref('')
const resultStr = ref('')
const searching = ref(false)

// odsSearch=true looks up Open ODS, false the Data Sharing Manager details (now from DataSteward)
async function search(odsSearch) {
  if (!odsCode.value) {
    notify.error('Enter an ODS code')
    return
  }
  odsCode.value = odsCode.value.trim()
  searching.value = true
  resultStr.value = 'searching...'
  try {
    const result = odsSearch
      ? await serviceApi.getOpenOdsRecord(odsCode.value)
      : await serviceApi.getDsmDetails(odsCode.value)
    resultStr.value = result ? JSON.stringify(result, null, 2) : 'no match found'
  } catch (e) {
    resultStr.value = ''
    notify.error('Error searching', e)
  } finally {
    searching.value = false
  }
}
</script>
