<template>
  <div class="card">
    <h2>Interface Types</h2>
    <p class="muted">
      Reference rows in the SFTP Reader database (<code>interface_type</code> and <code>interface_file_type</code>).
      Every feed needs one, and its file types are what the SFTP Reader checks received files against.
    </p>
    <div class="layout-it">
      <div class="table-it">
        <div v-if="types === null" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
        <DataTable v-else :value="types" size="small" stripedRows>
          <template #empty>
            No interface types yet.
            <span v-if="admin">Add one here, or copy the rows from an existing SFTP Reader database.</span>
          </template>
          <Column field="interfaceTypeId" header="ID" style="width: 4rem" />
          <Column field="interfaceTypeName" header="Name" />
          <Column field="dataFrequencyDays" header="Data Frequency (days)" style="width: 10rem" />
          <Column header="File Types">
            <template #body="{ data }">
              <Tag v-if="!data.fileTypes.length" severity="danger" value="No file types: feeds can't use it" />
              <span v-else class="muted">{{ data.fileTypes.join(', ') }}</span>
            </template>
          </Column>
          <Column field="configurationCount" header="Feeds" style="width: 5rem" />
          <Column v-if="admin" style="width: 6rem">
            <template #body="{ data }">
              <span v-tooltip="data.configurationCount ? 'Used by ' + data.configurationCount + ' feed(s), so it can\'t be deleted' : null">
                <Button label="Delete" size="small" severity="danger" outlined :disabled="data.configurationCount > 0"
                        :loading="deleting === data.interfaceTypeId" @click="remove(data)" />
              </span>
            </template>
          </Column>
        </DataTable>
      </div>

      <form v-if="admin" class="form-it" @submit.prevent="add">
        <h3>Add Interface Type</h3>
        <div class="field">
          <label for="it-name">Name *</label>
          <InputText id="it-name" v-model="form.interfaceTypeName" maxlength="1000" />
        </div>
        <div class="field">
          <label for="it-days">Data frequency (days) *</label>
          <InputNumber inputId="it-days" v-model="form.dataFrequencyDays" :min="1" :useGrouping="false" />
        </div>
        <div class="field">
          <label for="it-files">File type identifiers * (one per line)</label>
          <Textarea id="it-files" v-model="form.fileTypes" rows="6" class="mono" spellcheck="false" />
          <small class="muted">Must match what the SFTP Reader's implementation for this system expects. Copying the rows from an existing SFTP Reader database is the safest route.</small>
        </div>
        <div class="field">
          <label for="it-id">ID (blank for the next free one)</label>
          <InputNumber inputId="it-id" v-model="form.interfaceTypeId" :min="1" :useGrouping="false" />
        </div>
        <Message v-if="formError" severity="error" size="small">{{ formError }}</Message>
        <div class="mt-1"><Button type="submit" label="Add" :loading="saving" /></div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { sftpReaderApi } from '@/services/api'
import { isAdmin, useConfirmAsync, useNotify } from '@/utils/ui'

// VHP: the interface_type rows, with admin-only add and delete (the backend also enforces vhp_ui_admin)
defineProps({ types: { type: Array, default: null } })
const emit = defineEmits(['added', 'deleted'])

const admin = isAdmin()
const notify = useNotify()
const confirmAsync = useConfirmAsync()
const deleting = ref(null)

async function remove(t) {
  const ok = await confirmAsync(
    `Delete interface type ${t.interfaceTypeId} (${t.interfaceTypeName}) and its ${t.fileTypes.length} file type(s)? This can't be undone.`,
    'Delete interface type', 'Delete', true)
  if (!ok) return
  deleting.value = t.interfaceTypeId
  try {
    await sftpReaderApi.deleteInterfaceType(t.interfaceTypeId)
    notify.success('Interface type deleted', `${t.interfaceTypeName} (id ${t.interfaceTypeId})`)
    emit('deleted', t)
  } catch (e) {
    notify.error('Failed to delete interface type', e)
    emit('deleted', null) // refresh anyway: a 409/404 means the list was out of date
  } finally {
    deleting.value = null
  }
}
const saving = ref(false)
const formError = ref('')
const blank = () => ({ interfaceTypeName: '', dataFrequencyDays: 1, fileTypes: '', interfaceTypeId: null })
const form = reactive(blank())

const fileTypeList = () => form.fileTypes.split(/\r?\n/).map((s) => s.trim()).filter((s) => s)

// same checks as the backend's InterfaceTypeValidator
function validate() {
  if (!form.interfaceTypeName.trim()) return 'Name is required'
  if (!form.dataFrequencyDays || form.dataFrequencyDays < 1) return 'Data frequency must be 1 or more days'
  const files = fileTypeList()
  if (!files.length) return 'At least one file type identifier is required'
  const lower = files.map((f) => f.toLowerCase())
  if (new Set(lower).size !== lower.length) return 'A file type identifier is listed twice'
  if (form.interfaceTypeId != null && form.interfaceTypeId < 1) return 'ID must be 1 or more'
  return ''
}

async function add() {
  formError.value = validate()
  if (formError.value) return
  saving.value = true
  try {
    const created = await sftpReaderApi.addInterfaceType({
      interfaceTypeName: form.interfaceTypeName.trim(),
      dataFrequencyDays: form.dataFrequencyDays,
      fileTypes: fileTypeList(),
      interfaceTypeId: form.interfaceTypeId ?? undefined,
    })
    notify.success('Interface type added', `${created.interfaceTypeName} (id ${created.interfaceTypeId})`)
    Object.assign(form, blank())
    emit('added', created)
  } catch (e) {
    notify.error('Failed to add interface type', e)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.layout-it {
  display: flex;
  flex-wrap: wrap;
  gap: 1rem 2rem;
  align-items: flex-start;
}

.table-it {
  flex: 2 1 30rem;
  min-width: 0;
}

.form-it {
  flex: 1 1 18rem;
  max-width: 28rem;
}

.form-it h3 {
  margin: 0 0 0.5rem;
  font-size: 1rem;
}
</style>
