<template>
  <div>
    <div class="page-header">
      <h1>System</h1>
      <span class="sub">{{ item.name }}</span>
      <div class="actions" v-if="admin">
        <Button v-if="item.uuid" label="Delete System" severity="danger" size="small" @click="deleteSystem" />
        <Button label="Save" severity="success" size="small" :loading="saving" :disabled="loadFailed" @click="save(false)" />
        <Button label="Save & Close" severity="warn" size="small" :loading="saving" :disabled="loadFailed" @click="save(true)" />
        <Button label="Cancel" severity="secondary" size="small" @click="close" />
      </div>
      <div class="actions" v-else>
        <Button label="Close" severity="secondary" size="small" @click="close" />
      </div>
    </div>

    <div v-if="loading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
    <div v-else class="card">
      <Message v-if="formatsWarning" severity="warn" size="small">{{ formatsWarning }}</Message>
      <Message v-if="formatNotListed" severity="error" size="small">
        This system's message format <b>{{ iface.messageFormat }}</b> is not in the message format list, so the Messaging API
        can't transform its data. Choose a listed format<span v-if="admin"> (or add it with New Format if a transform for it has been deployed)</span>.
      </Message>
      <div class="grid-2">
        <div class="field">
          <label>System Name</label>
          <InputText v-model="item.name" placeholder="Please enter a system name" :disabled="!admin" />
        </div>
        <div class="field">
          <label>System Description</label>
          <Textarea v-model="item.description" rows="2" placeholder="Please enter a description for this system" :disabled="!admin" />
        </div>
        <div class="field">
          <label>Transaction Frequency</label>
          <Select v-model="iface.frequency" :options="FREQUENCIES" :disabled="!admin" />
        </div>
        <div class="field">
          <label>Message Type</label>
          <Select v-model="iface.messageType" :options="TYPES" :disabled="!admin" />
        </div>
        <div class="field">
          <label>Message Format
            <Button v-if="admin" label="New Format" size="small" severity="secondary" text @click="formatPromptVisible = true" />
          </label>
          <Select v-model="iface.messageFormat" :options="formatOptions" optionLabel="label" optionValue="value" filter
                  placeholder="Choose a message format" :disabled="!admin" />
          <small class="muted">Must equal the software content type the sender uses (e.g. the SFTP Reader feed's).</small>
        </div>
        <div class="field">
          <label>Message Format Version</label>
          <InputText v-model="iface.messageFormatVersion" placeholder="Please enter a message format version" :disabled="!admin" />
          <small class="muted">A regular expression that must match the whole version the sender uses, e.g. <code>5\.1</code> or <code>5\..*</code>.</small>
        </div>
      </div>
    </div>

    <Dialog v-model:visible="formatPromptVisible" modal header="New message format" :style="{ width: '32rem' }">
      <Message severity="info" size="small">
        Only add a format once the code that processes it has been deployed: a new format needs a transform in the
        Messaging API / queue reader and, for SFTP feeds, an implementation in the SFTP Reader. The format is added to the
        shared list (config row <code>global/message_formats</code>); edit or remove entries on the Config Manager page.
      </Message>
      <div class="field">
        <label for="nf-format">Format *</label>
        <InputText id="nf-format" :modelValue="newFormat.format" maxlength="100" autofocus placeholder="e.g. EMISCSV"
                   @update:modelValue="(v) => (newFormat.format = (v || '').toUpperCase())" />
      </div>
      <div class="field">
        <label for="nf-desc">Description</label>
        <InputText id="nf-desc" v-model="newFormat.description" maxlength="200" />
      </div>
      <div class="field-inline mt-1">
        <Checkbox v-model="newFormat.transform" binary inputId="nf-transform" />
        <label for="nf-transform">The inbound pipeline has a transform for it (offered for Systems)</label>
      </div>
      <div class="field-inline mt-1">
        <Checkbox v-model="newFormat.sftpReader" binary inputId="nf-sftp" />
        <label for="nf-sftp">The SFTP Reader can read feeds of it (offered for SFTP feeds)</label>
      </div>
      <template #footer>
        <Button label="Cancel" severity="secondary" @click="formatPromptVisible = false" />
        <Button label="Add" :loading="addingFormat" @click="addFormat" />
      </template>
    </Dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { messageFormatApi, systemApi } from '@/services/api'
import { useNotify, useConfirmAsync, isAdmin } from '@/utils/ui'

// A "system" is a library item holding one technical interface (eds-ui SystemEditComponent).
const TYPES = ['Patient Record', 'Demographics', 'Appointments', 'Summary', 'Discharge', 'Episode']
const FREQUENCIES = ['Transactional', 'Hourly', 'Daily']

const route = useRoute()
const router = useRouter()
const notify = useNotify()
const confirmAsync = useConfirmAsync()
const admin = isAdmin()

const item = ref({ uuid: null, name: '', description: '', folderUuid: null, system: { uuid: null, name: '', technicalInterface: [] } })
// VHP: the formats that can be processed (config row global/message_formats); only transformable ones suit a System
const formats = ref([])
const formatsWarning = ref('')
const formatsLoaded = ref(false)
const loading = ref(false)
// if loading an existing system fails, the form holds a blank item: saving it would create a new, duplicate system
const loadFailed = ref(false)
const saving = ref(false)

// a system always has (at least) one technical interface; the page edits the first
function ensureInterface() {
  const it = item.value
  it.system ||= { uuid: null, name: '', technicalInterface: [] }
  it.system.technicalInterface ||= []
  if (!it.system.technicalInterface.length) {
    it.system.technicalInterface.push({ uuid: null, name: 'New interface', frequency: '', messageType: '', messageFormat: '', messageFormatVersion: '' })
  }
}
ensureInterface()
const iface = computed(() => item.value.system.technicalInterface[0])

async function load(uuid) {
  loading.value = true
  try {
    const loaded = await systemApi.getLibraryItem(uuid)
    item.value = loaded
    ensureInterface()
  } catch (e) {
    loadFailed.value = true
    if (e?.response?.status === 404) {
      // e.g. a stale link after the admin database was emptied: go back to the (possibly empty) list
      notify.warning('System not found', 'It may have been deleted. Use Add New to create a system.')
      router.replace('/systems')
    } else notify.error('Error loading', e)
  } finally {
    loading.value = false
  }
}

async function save(close) {
  if (loadFailed.value) return
  if (formatsLoaded.value && (!iface.value.messageFormat || formatNotListed.value)) {
    return notify.error('Choose a message format from the list', 'Data for a system with any other format cannot be processed')
  }
  iface.value.name = item.value.name
  saving.value = true
  try {
    const saved = await systemApi.saveLibraryItem(item.value)
    const isNew = !item.value.uuid
    item.value.uuid = saved.uuid
    notify.success('Item saved')
    if (close) router.push('/systems')
    else if (isNew) router.replace(`/systemEdit/edit/${saved.uuid}`)
  } catch (e) {
    notify.error('Error saving', e)
  } finally {
    saving.value = false
  }
}

function close() {
  router.push('/systems')
}

async function deleteSystem() {
  if (!(await confirmAsync('Are you sure you want to delete the System?', 'Delete System', 'Yes', true))) return
  try {
    const result = await systemApi.deleteSystem(item.value.uuid)
    if (result) notify.error(result)
    else {
      notify.success('System deleted')
      close()
    }
  } catch (e) {
    notify.error('Failed to delete System', e)
  }
}

const transformFormats = computed(() => formats.value.filter((f) => f.transform))
const formatNotListed = computed(() =>
  formatsLoaded.value && !!iface.value.messageFormat &&
  !transformFormats.value.some((f) => f.format === iface.value.messageFormat))
// a stored format that is not listed is still shown, so the page doesn't silently blank it
const formatOptions = computed(() => {
  const opts = transformFormats.value.map((f) => ({ value: f.format, label: f.description ? `${f.format} - ${f.description}` : f.format }))
  if (formatNotListed.value) opts.unshift({ value: iface.value.messageFormat, label: `${iface.value.messageFormat} (not in the list)` })
  return opts
})

async function loadFormats() {
  try {
    const r = await messageFormatApi.getAll()
    formats.value = r.formats || []
    formatsWarning.value = r.source === 'default' ? r.warning || 'Showing the built-in message format list' : ''
    formatsLoaded.value = true
  } catch (e) {
    notify.error('Failed to retrieve message formats', e)
  }
}

const formatPromptVisible = ref(false)
const addingFormat = ref(false)
const blankFormat = () => ({ format: '', description: '', transform: true, sftpReader: false })
const newFormat = ref(blankFormat())
watch(formatPromptVisible, (v) => {
  if (v) newFormat.value = blankFormat()
})
async function addFormat() {
  const f = (newFormat.value.format || '').trim().toUpperCase()
  if (!f) return notify.error('Format cannot be empty')
  if (!/^[A-Z0-9_]+$/.test(f)) return notify.error('A format may only contain upper-case letters, digits and _')
  if (formats.value.some((x) => x.format === f)) return notify.error('Format ' + f + ' is already listed')
  if (!newFormat.value.transform && !newFormat.value.sftpReader) return notify.error('Tick at least one of the two boxes')
  addingFormat.value = true
  try {
    const r = await messageFormatApi.add({ ...newFormat.value, format: f })
    formats.value = r.formats || []
    formatsWarning.value = ''
    formatsLoaded.value = true
    formatPromptVisible.value = false
    if (newFormat.value.transform) iface.value.messageFormat = f
    notify.success('Message format ' + f + ' added')
  } catch (e) {
    notify.error('Failed to add message format', e)
  } finally {
    addingFormat.value = false
  }
}

loadFormats()

if (route.params.itemAction === 'edit' && route.params.itemUuid) load(route.params.itemUuid)
</script>
