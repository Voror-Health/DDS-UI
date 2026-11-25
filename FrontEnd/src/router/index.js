import { createRouter, createWebHistory } from 'vue-router'
import { getAppBasePath } from '@/services/routes'

// Paths follow eds-ui's ui-router states (dataServiceManager.app.ts). Keep services/routes.js in sync.
const routes = [
  { path: '/', redirect: '/services' },
  { path: '/callback', redirect: '/services' },
  { path: '/services', component: () => import('@/views/ServiceList.vue'), meta: { title: 'Publishers' } },
  { path: '/serviceEdit/:itemAction/:itemUuid?', component: () => import('@/views/ServiceEdit.vue'), meta: { title: 'Publisher' } },
  { path: '/exchangeAudit/:serviceId/:systemId', component: () => import('@/views/ExchangeAudit.vue'), meta: { title: 'Exchanges' } },
  { path: '/transformErrors', component: () => import('@/views/TransformErrors.vue'), meta: { title: 'Publisher Errors' } },
  { path: '/subscribers', component: () => import('@/views/Subscribers.vue'), meta: { title: 'Subscribers' } },
  { path: '/subscribers/:subscriberName', component: () => import('@/views/SubscriberDetail.vue'), meta: { title: 'Subscriber' } },
  { path: '/remoteFiling', component: () => import('@/views/RemoteFiling.vue'), meta: { title: 'Remote Filing' } },
  { path: '/queueReaders', component: () => import('@/views/QueueReaders.vue'), meta: { title: 'Queue Readers' } },
  { path: '/queueServerAdmin', component: () => import('@/views/QueueServerAdmin.vue'), meta: { title: 'Queue Server Admin' } },
  { path: '/subscriberApi', component: () => import('@/views/FrailtyApi.vue'), meta: { title: 'Subscriber API' } },
  { path: '/hl7Receiver', component: () => import('@/views/Hl7Receiver.vue'), meta: { title: 'HL7 Receiver' } },
  { path: '/hl7v2Api', component: () => import('@/views/Hl7v2Api.vue'), meta: { title: 'HL7v2 API' } },
  { path: '/sftpReader', component: () => import('@/views/SftpReader.vue'), meta: { title: 'SFTP Reader' } },
  { path: '/scheduledTasks', component: () => import('@/views/ScheduledTasks.vue'), meta: { title: 'Scheduled Tasks' } },
  { path: '/stats', component: () => import('@/views/Stats.vue'), meta: { title: 'Statistics' } },
  { path: '/audit', component: () => import('@/views/Audit.vue'), meta: { title: 'Audit' } },
  { path: '/monitoring', component: () => import('@/views/Logging.vue'), meta: { title: 'Monitoring' } },
  { path: '/routingConfig', component: () => import('@/views/RoutingConfig.vue'), meta: { title: 'Routing Config' } },
  { path: '/systems', component: () => import('@/views/SystemList.vue'), meta: { title: 'System Config' } },
  { path: '/systemEdit/:itemAction/:itemUuid?', component: () => import('@/views/SystemEdit.vue'), meta: { title: 'System' } },
  { path: '/configManager', component: () => import('@/views/ConfigManager.vue'), meta: { title: 'Config Manager' } },
  { path: '/:pathMatch(.*)*', redirect: '/services' },
]

const router = createRouter({
  history: createWebHistory(getAppBasePath()),
  routes,
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - DDS UI` : 'DDS UI'
})

export default router
