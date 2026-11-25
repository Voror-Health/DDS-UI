<template>
  <div>
    <div class="page-header">
      <h1>Systems</h1>
      <div class="actions">
        <Button label="Refresh" icon="fa-solid fa-rotate" severity="secondary" size="small" @click="refresh" />
        <Button v-if="admin" label="Add New" severity="success" size="small" @click="$router.push('/systemEdit/add')" />
      </div>
    </div>
    <div class="card">
      <DataTable :value="systems || []" size="small" stripedRows :loading="!systems">
        <template #empty>
          <span v-if="admin">No systems yet. Use Add New to create the first one.</span>
          <span v-else>No systems yet.</span>
        </template>
        <Column header="Title" style="width: 50%">
          <template #body="{ data }"><a @click="$router.push(`/systemEdit/edit/${data.uuid}`)">{{ data.name }}</a></template>
        </Column>
        <Column header="Last Modified"><template #body="{ data }">{{ formatYYYYMMDDHHMM(data.lastModified) }}</template></Column>
      </DataTable>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { systemApi } from '@/services/api'
import { formatYYYYMMDDHHMM } from '@/utils/format'
import { useNotify, isAdmin } from '@/utils/ui'

const notify = useNotify()
const admin = isAdmin()
const systems = ref(null)

async function refresh() {
  systems.value = null
  try {
    systems.value = await systemApi.getSystemList()
  } catch (e) {
    systems.value = []
    notify.error('Error getting systems', e)
  }
}

refresh()
</script>
