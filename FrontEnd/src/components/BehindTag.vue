<template>
  <!-- eds-ui showed "Behind" in red when more than a day behind, amber when up to a day behind -->
  <Tag v-if="days > 1" severity="danger" value="Behind" v-tooltip="text" />
  <Tag v-else-if="days > 0" severity="warn" value="Behind" v-tooltip="text" />
</template>

<script setup>
defineProps({ days: Number, text: String })
</script>
