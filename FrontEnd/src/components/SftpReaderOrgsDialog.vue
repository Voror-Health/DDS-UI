<template>
  <Dialog :visible="visible" @update:visible="$emit('update:visible', $event)" modal header="Organisation List" :style="{ width: '60rem' }">
    <DataTable :value="contents" size="small" stripedRows scrollable scrollHeight="55vh">
      <Column v-if="admin" style="width: 2.5rem">
        <template #body="{ data }"><Checkbox v-if="!data.notified" v-model="data.selected" binary /></template>
      </Column>
      <Column field="orgId" header="ODS" />
      <Column header="Bulk"><template #body="{ data }">{{ data.isBulk ? 'Y' : '' }}</template></Column>
      <Column header="Status"><template #body="{ data }"><span :class="data.notified ? 'ok' : 'err'">{{ data.notified ? 'OK' : 'Error' }}</span></template></Column>
      <Column header="Message"><template #body="{ data }">{{ data.notified ? data.result : data.error }}</template></Column>
    </DataTable>
    <template #footer>
      <Button label="ODS Search" severity="secondary" outlined @click="odsVisible = true" />
      <Button v-if="admin" label="Select All Errors" severity="secondary" outlined @click="selectAllErrors" />
      <Button v-if="admin" label="Ignore Selected Errors" severity="warn" @click="startIgnore" />
      <Button label="Close" @click="$emit('update:visible', false)" />
    </template>
  </Dialog>

  <Dialog v-model:visible="reasonVisible" modal header="Enter reason" :style="{ width: '28rem' }">
    <InputText v-model="reason" class="w-full" autofocus @keyup.enter="ignoreSelected" />
    <template #footer>
      <Button label="Cancel" severity="secondary" @click="reasonVisible = false" />
      <Button label="Ignore" :disabled="!reason" @click="ignoreSelected" />
    </template>
  </Dialog>

  <OdsSearchDialog v-model:visible="odsVisible" />
</template>

<script setup>
import { ref } from 'vue'
import { sftpReaderApi } from '@/services/api'
import { useNotify, isAdmin } from '@/utils/ui'
import OdsSearchDialog from './OdsSearchDialog.vue'

// eds-ui sftpReader/SftpReaderOrgsDialog: the organisations in a batch, with "ignore" for errors
const props = defineProps({ visible: Boolean, configurationId: String, batchId: Number, contents: { type: Array, default: () => [] } })
defineEmits(['update:visible'])
const notify = useNotify()
const admin = isAdmin() // VHP: ignoring errors needs vhp_ui_admin (the API enforces it)
const odsVisible = ref(false)
const reasonVisible = ref(false)
const reason = ref('')

function selectAllErrors() {
  for (const c of props.contents) if (!c.notified) c.selected = true
}

function startIgnore() {
  if (!props.contents.some((c) => c.selected)) {
    notify.warning('No errors selected')
    return
  }
  reason.value = ''
  reasonVisible.value = true
}

async function ignoreSelected() {
  reasonVisible.value = false
  const text = 'Manually ignored: ' + reason.value // prefix so it's clear where it came from
  for (const c of props.contents.filter((x) => x.selected)) {
    try {
      await sftpReaderApi.ignoreBatchSplit(props.batchId, c.batchSplitId, props.configurationId, text)
      c.error = null
      c.result = text
      c.notified = true
      c.selected = false
    } catch (e) {
      notify.error('Failed to ignore batch split', e)
    }
  }
}
</script>
