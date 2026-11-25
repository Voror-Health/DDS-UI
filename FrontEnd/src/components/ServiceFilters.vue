<template>
  <!-- the publisher filters shared by the Publishers and Publisher Errors pages (state in serviceStore) -->
  <div class="card">
    <div class="filters">
      <div class="field" style="width: 16rem">
        <label>
          Name/ID
          <span class="ml-1">
            <Checkbox v-model="f.serviceNameSearchIncludeTags" binary inputId="matchTags" />
            <label for="matchTags" class="ml-1">Match tags</label>
          </span>
        </label>
        <InputText v-model="f.serviceNameFilter" placeholder="Regex" />
        <Select v-if="f.serviceNameSearchIncludeTags" v-model="f.serviceNameSearchSpecificTag"
                :options="[{ label: 'All Tags', value: '' }, ...tagNames.map((t) => ({ label: t, value: t }))]"
                optionLabel="label" optionValue="value" filter />
      </div>

      <div class="field" style="width: 16rem">
        <label>
          Parent
          <span class="ml-1">
            <Checkbox v-model="f.serviceCcgCodeFilterIsRegex" binary inputId="parentRegex" />
            <label for="parentRegex" class="ml-1">Regex</label>
          </span>
        </label>
        <Select v-if="!f.serviceCcgCodeFilterIsRegex" v-model="f.serviceCcgCodeFilterStr" :options="ccgOptions"
                optionLabel="label" optionValue="value" showClear filter placeholder=" " />
        <InputText v-else v-model="f.serviceCcgCodeFilterRegex" placeholder="Regex" />
      </div>

      <div class="field" style="width: 12rem">
        <label>Publisher DB</label>
        <Select v-model="f.servicePublisherConfigFilter" :options="publisherConfigOptions" optionLabel="label"
                optionValue="value" showClear placeholder=" " />
      </div>

      <div class="field" style="width: 9rem">
        <label>Latest Cutoff</label>
        <Select v-model="f.serviceLastDataFilter" :options="lastDataOptions" optionLabel="label" optionValue="value"
                showClear placeholder=" " />
      </div>

      <div class="field" style="width: 10rem">
        <label>Publisher Mode</label>
        <Select v-model="f.servicePublisherModeFilter" :options="PUBLISHER_MODES" optionLabel="label"
                optionValue="value" showClear placeholder=" " />
      </div>

      <div class="field" style="width: 9rem">
        <label>Status</label>
        <!-- the errors page only lists errors, so its status filter is fixed (as in eds-ui) -->
        <Select v-if="errorsView" :modelValue="'Error'" :options="[{ value: 'Error', label: 'Error' }]"
                optionLabel="label" optionValue="value" disabled />
        <Select v-else v-model="f.serviceStatusFilter" :options="statusOptions" optionLabel="label" optionValue="value"
                showClear placeholder=" " />
      </div>

      <div class="field" style="width: 12rem">
        <label>Sorting</label>
        <Select v-model="f.sortFilter" :options="sortOptions" optionLabel="label" optionValue="value" />
      </div>

      <div class="field">
        <span class="field-inline">
          <Checkbox v-model="f.serviceHideClosedFilter" binary inputId="hideClosed" />
          <label for="hideClosed">Hide Closed</label>
        </span>
        <span class="field-inline" v-if="!errorsView">
          <Checkbox v-model="f.serviceShowDateFilter" binary inputId="showDates" />
          <label for="showDates">Show Dates</label>
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  filters as f, getTagNames, getCcgCodes, getCcgName, getPublisherConfigNames, PUBLISHER_MODES,
} from '@/services/serviceStore'

defineProps({ errorsView: Boolean })

const tagNames = computed(() => getTagNames())
const ccgOptions = computed(() => getCcgCodes().map((c) => ({ value: c, label: `${c} ${getCcgName(c)}` })))
const publisherConfigOptions = computed(() => [
  ...getPublisherConfigNames().map((n) => ({ value: n, label: n })),
  { value: 'NoPublisher', label: 'No publisher set' },
])

const lastDataOptions = [
  { value: '1d', label: '≤1d' },
  { value: '2d', label: '≤2d' },
  { value: '1wk', label: '≤1wk' },
  { value: '2wk', label: '≤2wk' },
  { value: 'older', label: '>2wk' },
]
const statusOptions = [
  { value: 'Error', label: 'Error' },
  { value: 'Behind', label: 'Behind' },
  { value: 'OK', label: 'OK' },
  { value: 'NoData', label: 'No Data' },
  { value: 'NoStatus', label: 'No Status' },
]
const sortOptions = [
  ['NameAsc', 'Name'], ['NameDesc', 'Name (D)'], ['IDAsc', 'ID'], ['IDDesc', 'ID (D)'],
  ['ParentAsc', 'Parent'], ['ParentDesc', 'Parent (D)'], ['PublisherConfigAsc', 'Publisher DB'],
  ['PublisherConfigDesc', 'Publisher DB (D)'], ['LastDataAsc', 'Latest Cutoff'], ['LastDataDesc', 'Latest Cutoff (D)'],
  ['StatusAsc', 'Status'], ['StatusDesc', 'Status (D)'],
].map(([value, label]) => ({ value, label }))
</script>
