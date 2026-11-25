<template>
  <Dialog :visible="visible" @update:visible="close" modal :header="isEdit ? 'Edit Feed ' + editConfigurationId : 'Add Feed'"
          :style="{ width: '62rem' }" :breakpoints="{ '960px': '95vw' }">
    <div v-if="loading" class="loading"><i class="fa-solid fa-spinner spin"></i> Loading...</div>
    <template v-else>
      <Message v-if="!isEdit && !usableTypes.length" severity="warn">
        There are no interface types with file types yet, so a feed can't be added. Add one in the
        <b>Interface Types</b> section of this page first (or copy the rows from an existing SFTP Reader database).
      </Message>
      <Message v-if="isEdit" severity="info" size="small">
        Changes take effect when the SFTP Reader instance is restarted (it reads its feeds at start-up).
        <span v-if="feed?.retired"> This feed is retired; use Reinstate on the feed list to give it an instance again.</span>
      </Message>

      <form id="add-feed-form" @submit.prevent="submit">
        <h3>Feed</h3>
        <div class="grid-3">
          <div class="field">
            <label for="af-instance">Instance *</label>
            <InputText v-if="isEdit && feed?.retired" id="af-instance" :modelValue="form.instanceName" disabled />
            <InputText v-else-if="!isEdit && form.createInstance" id="af-instance" v-model="form.instanceName" maxlength="100" />
            <Select v-else inputId="af-instance" v-model="form.instanceName" :options="instanceNames" placeholder="Choose an instance" />
            <span v-if="!isEdit" class="field-inline mt-1">
              <Checkbox v-model="form.createInstance" binary inputId="af-new" @change="form.instanceName = ''" />
              <label for="af-new">New instance</label>
            </span>
            <small v-if="!isEdit && form.createInstance" class="muted">Must match the vhp-sftpreader webapp's instance name, or nothing will run this feed.</small>
            <small v-if="moving" class="warn">Moving the feed: restart both {{ feed.instanceName }} and {{ form.instanceName }}.</small>
          </div>
          <div class="field">
            <label for="af-config">Configuration id *</label>
            <InputText id="af-config" v-model="form.configurationId" :maxlength="MAX_CONFIGURATION_ID" :disabled="isEdit" />
            <small v-if="isEdit" class="muted">Can't be changed: add a new feed and retire this one instead.</small>
          </div>
          <div class="field">
            <label for="af-friendly">Friendly name *</label>
            <InputText id="af-friendly" v-model="form.friendlyName" maxlength="100" />
          </div>
          <div class="field">
            <label for="af-type">Interface type *</label>
            <Select inputId="af-type" v-model="form.interfaceTypeId" :options="typeOptions" optionLabel="label" optionValue="value"
                    optionDisabled="disabled" placeholder="Choose a type" :disabled="lockedByBatches" />
            <small v-if="lockedByBatches" class="muted">Locked: this feed has received batches.</small>
          </div>
          <div class="field">
            <label for="af-poll">Poll frequency (seconds) *</label>
            <InputNumber inputId="af-poll" v-model="form.pollFrequencySeconds" :min="1" :useGrouping="false" />
          </div>
          <div class="field">
            <label for="af-root">Local root path *</label>
            <InputText id="af-root" v-model="form.localRootPath" maxlength="1000" :disabled="lockedByBatches" />
            <small v-if="lockedByBatches" class="muted">Locked: stored batches are found through this path.</small>
          </div>
          <div class="field">
            <label for="af-ct">Software content type *</label>
            <Select inputId="af-ct" v-model="form.softwareContentType" :options="contentTypeOptions" optionLabel="label"
                    optionValue="value" placeholder="Choose" />
            <small v-if="contentTypeNotListed" class="warn">{{ form.softwareContentType }} is not an SFTP Reader format in the
              message format list; the feed can't be saved with it.</small>
          </div>
          <div class="field">
            <label for="af-ver">Software version *</label>
            <InputText id="af-ver" v-model="form.softwareVersion" maxlength="100" placeholder="e.g. 5.1" />
          </div>
        </div>
        <small class="muted">Content type and version must match the publisher system's message format and version in the admin library.</small>

        <h3>SFTP</h3>
        <div class="grid-3">
          <div class="field"><label for="af-host">Hostname *</label><InputText id="af-host" v-model="form.hostname" maxlength="100" /></div>
          <div class="field"><label for="af-port">Port *</label><InputNumber inputId="af-port" v-model="form.port" :min="1" :max="65535" :useGrouping="false" /></div>
          <div class="field"><label for="af-user">Username *</label><InputText id="af-user" v-model="form.username" maxlength="100" autocomplete="off" /></div>
        </div>
        <div class="field"><label for="af-remote">Remote path *</label><InputText id="af-remote" v-model="form.remotePath" maxlength="1000" /></div>
        <div class="grid-2">
          <div class="field"><label for="af-cpub">Client public key</label><Textarea id="af-cpub" v-model="form.clientPublicKey" rows="3" class="mono" spellcheck="false" /></div>
          <div class="field">
            <label for="af-cpriv">Client private key {{ secretMark('clientPrivateKeySet') }}</label>
            <Textarea id="af-cpriv" v-model="form.clientPrivateKey" rows="3" class="mono" spellcheck="false" :placeholder="secretHint('clientPrivateKeySet')" />
          </div>
          <div class="field">
            <label for="af-cpw">Client private key password {{ secretMark('clientPrivateKeyPasswordSet') }}</label>
            <InputText id="af-cpw" type="password" v-model="form.clientPrivateKeyPassword" maxlength="1000" autocomplete="new-password" :placeholder="secretHint('clientPrivateKeyPasswordSet')" />
          </div>
          <div class="field"><label for="af-hpub">Host public key *</label><Textarea id="af-hpub" v-model="form.hostPublicKey" rows="3" class="mono" spellcheck="false" /></div>
        </div>

        <h3>PGP</h3>
        <div class="grid-3">
          <div class="field"><label for="af-ext">File extension filter *</label><InputText id="af-ext" v-model="form.fileExtensionFilter" maxlength="100" /></div>
        </div>
        <div class="grid-2">
          <div class="field"><label for="af-spub">Sender public key *</label><Textarea id="af-spub" v-model="form.senderPublicKey" rows="3" class="mono" spellcheck="false" /></div>
          <div class="field"><label for="af-rpub">Recipient public key *</label><Textarea id="af-rpub" v-model="form.recipientPublicKey" rows="3" class="mono" spellcheck="false" /></div>
          <div class="field">
            <label for="af-rpriv">Recipient private key {{ secretMark('recipientPrivateKeySet') }}</label>
            <Textarea id="af-rpriv" v-model="form.recipientPrivateKey" rows="3" class="mono" spellcheck="false" :placeholder="secretHint('recipientPrivateKeySet')" />
          </div>
          <div class="field">
            <label for="af-rpw">Recipient private key password {{ secretMark('recipientPrivateKeyPasswordSet') }}</label>
            <InputText id="af-rpw" type="password" v-model="form.recipientPrivateKeyPassword" maxlength="1000" autocomplete="new-password" :placeholder="secretHint('recipientPrivateKeyPasswordSet')" />
          </div>
        </div>

        <h3>Optional</h3>
        <div class="grid-2">
          <div class="field">
            <label for="af-guid">Sharing agreement GUID (EMIS)</label>
            <InputText id="af-guid" v-model="form.sharingAgreementGuid" maxlength="1000" />
            <small v-if="isEdit" class="muted">Clear it to remove the value.</small>
          </div>
        </div>

        <Message v-if="formError" severity="error" size="small" class="mt-1">{{ formError }}</Message>
      </form>
    </template>

    <template #footer>
      <Button label="Cancel" severity="secondary" @click="close(false)" />
      <Button type="submit" form="add-feed-form" :label="isEdit ? 'Save' : 'Add Feed'" :loading="saving"
              :disabled="loading || (!isEdit && !usableTypes.length) || (isEdit && !feed)" />
    </template>
  </Dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { messageFormatApi, sftpReaderApi } from '@/services/api'
import { useNotify } from '@/utils/ui'

// VHP: adds a feed (the six rows of the deployment guide's "SFTP Reader instance" step, POST sftpReader/addFeed), or,
// with editConfigurationId, edits one (GET/PUT sftpReader/feeds/{id}). Private keys and passwords are never loaded:
// in an edit they are blank, and left blank they keep the stored value.
const props = defineProps({
  visible: Boolean,
  interfaceTypes: { type: Array, default: () => [] },
  instanceNames: { type: Array, default: () => [] },
  editConfigurationId: { type: String, default: null },
})
const emit = defineEmits(['update:visible', 'added', 'saved'])
const notify = useNotify()

// VHP: the values vhp-sftpreader's ImplementationActivator accepts: the message format list's sftpReader formats
// (config row global/message_formats; FeedValidator checks the same list)
const contentTypes = ref([])
messageFormatApi
  .getAll()
  .then((r) => (contentTypes.value = (r.formats || []).filter((f) => f.sftpReader)))
  .catch((e) => notify.error('Failed to retrieve message formats', e))
const contentTypeNotListed = computed(() =>
  !!form.softwareContentType && contentTypes.value.length > 0 &&
  !contentTypes.value.some((f) => f.format === form.softwareContentType))
// an edited feed's stored type stays visible even if it is not listed
const contentTypeOptions = computed(() => {
  const opts = contentTypes.value.map((f) => ({ value: f.format, label: f.description ? `${f.format} - ${f.description}` : f.format }))
  if (contentTypeNotListed.value) opts.unshift({ value: form.softwareContentType, label: `${form.softwareContentType} (not in the list)` })
  return opts
})
// the reader's lock name 'SftpReader-<id>' must fit MySQL's 64 characters (FeedValidator.MAX_CONFIGURATION_ID)
const MAX_CONFIGURATION_ID = 53
const SECRETS = ['clientPrivateKey', 'clientPrivateKeyPassword', 'recipientPrivateKey', 'recipientPrivateKeyPassword']

const blank = () => ({
  instanceName: '', createInstance: false, configurationId: '', friendlyName: '', interfaceTypeId: null,
  pollFrequencySeconds: 300, localRootPath: '', softwareContentType: null, softwareVersion: '',
  hostname: '', port: 22, remotePath: '', username: '',
  clientPublicKey: '', clientPrivateKey: '', clientPrivateKeyPassword: '', hostPublicKey: '',
  fileExtensionFilter: '.gpg', senderPublicKey: '', recipientPublicKey: '', recipientPrivateKey: '', recipientPrivateKeyPassword: '',
  sharingAgreementGuid: '',
})
const form = reactive(blank())
const saving = ref(false)
const loading = ref(false)
const formError = ref('')
const feed = ref(null) // the stored feed in an edit (no secrets; flags, version, hasBatches, retired)

const isEdit = computed(() => !!props.editConfigurationId)
const lockedByBatches = computed(() => isEdit.value && !!feed.value?.hasBatches)
const moving = computed(() => isEdit.value && feed.value && !feed.value.retired && feed.value.instanceName &&
  form.instanceName && form.instanceName !== feed.value.instanceName)

// keys and passwords are cleared whenever the dialog closes; an edit loads the feed when it opens
watch(() => props.visible, async (v) => {
  Object.assign(form, blank())
  formError.value = ''
  feed.value = null
  if (v && isEdit.value) {
    loading.value = true
    try {
      const f = await sftpReaderApi.getFeed(props.editConfigurationId)
      feed.value = f
      for (const k of Object.keys(form)) {
        if (k in f && !SECRETS.includes(k)) form[k] = f[k] ?? ''
      }
      form.port = f.port ?? 22
      form.createInstance = false
    } catch (e) {
      notify.error('Failed to load feed ' + props.editConfigurationId, e)
      emit('update:visible', false)
    } finally {
      loading.value = false
    }
  }
})

const usableTypes = computed(() => (props.interfaceTypes || []).filter((t) => t.fileTypes && t.fileTypes.length))
const typeOptions = computed(() =>
  (props.interfaceTypes || []).map((t) => ({
    value: t.interfaceTypeId,
    label: `${t.interfaceTypeId}: ${t.interfaceTypeName}` + (t.fileTypes?.length ? '' : ' (no file types)'),
    disabled: !t.fileTypes?.length && t.interfaceTypeId !== feed.value?.interfaceTypeId,
  })),
)

// in an edit, a stored secret may be left blank; in an add, or with nothing stored, it is required
const secretOptional = (flag) => isEdit.value && !!feed.value?.[flag]
const secretMark = (flag) => (secretOptional(flag) ? '' : '*')
const secretHint = (flag) => (secretOptional(flag) ? 'Set - leave blank to keep the stored value' : '')

function close(v) {
  if (!v) emit('update:visible', false)
}

// mirrors the backend's FeedValidator; the backend checks again
const SAFE_ID = /^[A-Za-z0-9_.-]+$/
const required = [
  ['instanceName', 'Instance'], ['configurationId', 'Configuration id'], ['friendlyName', 'Friendly name'],
  ['localRootPath', 'Local root path'], ['softwareContentType', 'Software content type'], ['softwareVersion', 'Software version'],
  ['hostname', 'SFTP hostname'], ['remotePath', 'SFTP remote path'], ['username', 'SFTP username'],
  ['hostPublicKey', 'Host public key'],
  ['fileExtensionFilter', 'File extension filter'], ['senderPublicKey', 'Sender public key'],
  ['recipientPublicKey', 'Recipient public key'],
]
const secretLabels = [
  ['clientPrivateKey', 'clientPrivateKeySet', 'Client private key'],
  ['clientPrivateKeyPassword', 'clientPrivateKeyPasswordSet', 'Client private key password'],
  ['recipientPrivateKey', 'recipientPrivateKeySet', 'Recipient private key'],
  ['recipientPrivateKeyPassword', 'recipientPrivateKeyPasswordSet', 'Recipient private key password'],
]
function validate() {
  for (const [k, label] of required) {
    if (!String(form[k] ?? '').trim()) return `${label} is required`
  }
  for (const [k, flag, label] of secretLabels) {
    if (!form[k] && !secretOptional(flag)) return `${label} is required`
  }
  if (!SAFE_ID.test(form.instanceName.trim())) return "Instance may only contain letters, digits, '.', '_' and '-'"
  if (!isEdit.value && form.instanceName.trim().toUpperCase() === 'NOT_USED') return "NOT_USED is where retired feeds are kept; choose another instance"
  if (!SAFE_ID.test(form.configurationId.trim())) return "Configuration id may only contain letters, digits, '.', '_' and '-'"
  if (form.configurationId.trim().length > MAX_CONFIGURATION_ID) return `Configuration id must be at most ${MAX_CONFIGURATION_ID} characters`
  if (!form.interfaceTypeId) return 'Interface type is required'
  if (!form.pollFrequencySeconds || form.pollFrequencySeconds < 1) return 'Poll frequency must be 1 or more seconds'
  if (!form.port || form.port < 1 || form.port > 65535) return 'SFTP port must be between 1 and 65535'
  return ''
}

async function submit() {
  formError.value = validate()
  if (formError.value) return
  saving.value = true
  try {
    const body = { ...form, sharingAgreementGuid: form.sharingAgreementGuid.trim() || undefined }
    if (isEdit.value) {
      for (const k of SECRETS) if (!body[k]) delete body[k]
      const r = await sftpReaderApi.updateFeed(props.editConfigurationId, { ...body, version: feed.value.version })
      if (!r.changedFields.length) {
        notify.info('No changes', 'Nothing was changed')
      } else {
        notify.success('Feed saved', `${r.configurationId}: ${r.changedFields.length} field(s) changed`)
        if (r.restartRequired) {
          notify.warning('Restart the SFTP Reader', `Restart ${r.restartInstances.map((i) => 'vhp-sftpreader#' + i).join(' and ')} for the change to take effect.`)
        }
      }
      emit('saved', r)
    } else {
      const r = await sftpReaderApi.addFeed(body)
      notify.success('Feed added', `${r.configurationId} on instance ${r.instanceName}`)
      notify.warning('Restart the SFTP Reader', `The vhp-sftpreader#${r.instanceName} webapp only reads its feeds at start-up; restart it to start polling ${r.configurationId}.`)
      emit('added', r)
    }
    emit('update:visible', false)
  } catch (e) {
    notify.error(isEdit.value ? 'Failed to save feed' : 'Failed to add feed', e)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
h3 {
  margin: 1rem 0 0.5rem;
  font-size: 1rem;
}

form > h3:first-child {
  margin-top: 0.5rem;
}
</style>
