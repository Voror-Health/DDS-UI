<template>
  <div class="layout" :class="{ collapsed }">
    <aside class="sidebar">
      <div class="brand">
        <button class="collapse-btn" :title="collapsed ? 'Expand menu' : 'Collapse menu'" @click="toggleCollapsed">
          <i class="fa-solid fa-bars"></i>
        </button>
        <span class="brand-text">Data Service Manager</span>
      </div>
      <nav>
        <RouterLink v-for="item in menu" :key="item.route" :to="item.route" class="menu-item"
                    :class="{ active: isActive(item.route) }" :title="item.caption">
          <i :class="item.icon"></i>
          <span>{{ item.caption }}</span>
        </RouterLink>
      </nav>
    </aside>

    <div class="main">
      <header class="topbar">
        <span class="title">DDS UI</span>
        <span class="spacer"></span>
        <span class="user" v-if="userName">
          <i class="fa-solid fa-user"></i> {{ userName }}
          <Tag v-if="isAdmin" value="admin" severity="secondary" class="ml-1" />
        </span>
        <Button label="Logout" icon="fa-solid fa-right-from-bracket" text size="small" @click="logout" />
      </header>
      <main class="content">
        <RouterView :key="$route.fullPath" />
      </main>
    </div>

    <Toast position="top-right" />
    <ConfirmDialog />
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import Toast from 'primevue/toast'
import ConfirmDialog from 'primevue/confirmdialog'
import menu from './menu'
import Casdoor from './services/casdoor'

const route = useRoute()
const userName = Casdoor.userName()
const isAdmin = Casdoor.isAdmin()

function readCollapsed() {
  try {
    return localStorage.getItem('dds_ui_menu_collapsed') === '1'
  } catch {
    return false
  }
}
const collapsed = ref(readCollapsed())

function toggleCollapsed() {
  collapsed.value = !collapsed.value
  try {
    localStorage.setItem('dds_ui_menu_collapsed', collapsed.value ? '1' : '0')
  } catch {
    /* ignore */
  }
}

// sub-pages highlight their parent menu entry
const parents = {
  '/serviceEdit': '/services',
  '/exchangeAudit': '/services',
  '/systemEdit': '/systems',
}
const activeRoot = computed(() => {
  const p = route.path
  for (const [prefix, parent] of Object.entries(parents)) {
    if (p.startsWith(prefix)) return parent
  }
  return p
})

function isActive(r) {
  return activeRoot.value === r || activeRoot.value.startsWith(r + '/')
}

function logout() {
  Casdoor.logout()
}
</script>
