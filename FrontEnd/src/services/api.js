// One function per backend call, grouped as eds-ui's Angular services were.
import { get, post, del, http } from './http'

export const serviceApi = {
  getAll: () => get('service'),
  get: (uuid) => get('service', { uuid }),
  getForOdsCode: (odsCode) => get('service', { odsCode }),
  search: (searchText) => get('service', { searchText }),
  save: (service) => post('service', service),
  validateSave: (service) => post('service/validateService', service),
  delete: (uuid) => del('service/', { uuid }),
  deleteData: (serviceId) => del('service/data', { serviceId }),
  getSystemsForService: (serviceId) => get('service/systemsForService', { serviceId }),
  getOpenOdsRecord: (odsCode) => get('service/openOdsRecord', { odsCode }),
  getDsmDetails: (odsCode) => get('service/dsmDetails', { odsCode }),
  getOrganisationTypeList: () => get('service/organisationTypeList'),
  getTagNames: () => get('service/tagNames'),
  getPublisherConfigNames: () => get('service/publisherConfigNames'),
  getCcgCodes: () => get('service/ccgCodes'),
  getCcgNames: () => get('service/ccgNames'),
  getDpaHistory: (uuid) => get('service/dpaHistory', { uuid }),
  getSubscriberHistory: (uuid) => get('service/subscriberHistory', { uuid }),
}

const ms = (d) => (d ? new Date(d).getTime() : undefined)

export const exchangeAuditApi = {
  getExchangesByDate: (serviceId, systemId, maxRows, dateFrom, dateTo) =>
    get('exchangeAudit/getExchangesByDate', { serviceId, systemId, maxRows, dateFrom: ms(dateFrom), dateTo: ms(dateTo) }),
  getRecentExchanges: (serviceId, systemId, maxRows) => get('exchangeAudit/getRecentExchanges', { serviceId, systemId, maxRows }),
  getExchangesFromFirstError: (serviceId, systemId, maxRows) =>
    get('exchangeAudit/getExchangesFromFirstError', { serviceId, systemId, maxRows }),
  getExchangeById: (serviceId, systemId, exchangeId) => get('exchangeAudit/getExchangeById', { serviceId, systemId, exchangeId }),
  postToExchange: (request) => post('exchangeAudit/postToExchange', request),
  getTransformErrorSummaries: () => get('exchangeAudit/getTransformErrorSummaries'),
  getInboundTransformAudits: (serviceId, systemId, exchangeId, getAllAuditsAndEvents) =>
    get('exchangeAudit/getInboundTransformAudits', { serviceId, systemId, exchangeId, getAllAuditsAndEvents }),
  getTransformErrorLines: (serviceId, systemId, exchangeId, version) =>
    get('exchangeAudit/getTransformErrorLines', { serviceId, systemId, exchangeId, version }),
  getSubscriberConfigNameList: (serviceId) => get('exchangeAudit/getSubscriberConfigNamesForService', { serviceId }),
  getAllSubscriberConfigNameList: () => get('exchangeAudit/getAllSubscriberConfigNames'),
  rerunAllExchangesInError: (serviceId, systemId) => post('exchangeAudit/rerunAllExchangesInError', { serviceId, systemId }),
  rerunAllExchangesInErrorForServices: (summaries) =>
    post('exchangeAudit/requeueServicesInError', summaries.map((s) => ({ serviceId: s.service.uuid, systemId: s.systemId }))),
  addExchangeEvent: (exchangeId, text) => post('exchangeAudit/addExchangeEvent', { exchangeId, text }),
  makeNonQueueable: (exchangeId) => post('exchangeAudit/makeNonQueueable', { exchangeId }),
}

export const subscriberApi = {
  getSubscribers: () => get('subscribers/subscribers'),
  getSubscriberDetails: (subscriberName) => get('subscribers/subscriberDetail', { subscriberName }),
}

export const remoteFilingApi = {
  getRemoteFilers: () => get('remoteFiling/getRemoteFilers'),
  getRemoteFilerStatus: (subscriberId) => get('remoteFiling/getRemoteFilerStatus', { subscriberId }),
  getQueuedMessage: (queuedMessageId) => get('remoteFiling/getQueuedMessage', { queuedMessageId }),
}

export const rabbitApi = {
  getRoutings: () => get('rabbit/routings'),
  saveRoutings: (routings) => post('rabbit/routings', routings),
  getRabbitNodes: () => get('rabbit/nodes'),
  pingRabbitNode: (address) => get('rabbit/ping', { address }),
  getRabbitQueues: (address) => get('rabbit/queues', { address }),
  getRabbitQueuesFiltered: (address, prefix) => get('rabbit/queues/filtered', { address, prefix }),
  getRabbitExchanges: (address) => get('rabbit/exchanges', { address }),
  getRabbitBindings: (address) => get('rabbit/bindings', { address }),
  // the node address is the text/plain request body
  synchronize: (address) => post('rabbit/synchronize', address, undefined, { headers: { 'Content-Type': 'text/plain' } }),
  getRoutingOverrides: () => get('rabbit/overrides'),
  saveRoutingOverrides: (overrides) => post('rabbit/overrides', overrides),
}

export const queueReaderApi = {
  getStatus: (applicationName) => get('queueReader/status', { applicationName }),
  // whitelisted deploy command, run on the given queue reader servers (all if hostNames is empty)
  deploy: (hostNames) => post('queueReader/queueReaderCommand', { command: 'sudo ./queueReader/deploy.sh', hostNames }),
  getInstances: () => get('queueReader/instances'),
  getConfig: (configId) => get(`queueReader/config/${encodeURIComponent(configId)}`),
  // memory override for one queue reader; null maxHeap/unit = use the queue type defaults
  saveMemoryOverride: (configId, maxHeap, unit) => post('queueReader/config', { configId, maxHeap, unit }),
  stop: (configIds) => post('queueReader/stop', { configIds }),
  restart: (configIds) => post('queueReader/restart', { configIds }),
  getMemoryDefaults: () => get('queueReader/memoryDefaults'),
  saveMemoryDefaults: (queueType, maxHeap, unit) => post('queueReader/memoryDefaults', { queueType, maxHeap, unit }),
}

export const queueServerApi = {
  getServers: () => get('queueServer/servers'),
  getEc2Status: () => get('queueServer/ec2Status'),
  getJarVersions: () => get('queueServer/jarVersions'),
  saveServer: (server) => post('queueServer/saveServer', server),
  deleteServer: (serverNumber) => del(`queueServer/deleteServer/${encodeURIComponent(serverNumber)}`),
  saveQueueAssignments: (queueName, type, memory, serverNumbers) =>
    post('queueServer/saveQueueAssignments', { queueName, type, memory, serverNumbers }),
  startQueue: (serverNumber, queueName, memory) => post('queueServer/startQueue', { serverNumber, queueName, memory }),
  startQueuesOnMultipleServers: (queueName, memory, serverNumbers) =>
    post('queueServer/startQueuesOnMultipleServers', { queueName, memory, serverNumbers }),
  stopQueue: (serverNumber, queueName) => post('queueServer/stopQueue', { serverNumber, queueName }),
  restartQueue: (serverNumber, queueName) => post('queueServer/restartQueue', { serverNumber, queueName }),
  stopAllQueues: (serverNumber) => post('queueServer/stopAllQueues', { serverNumber }),
  restartAllQueues: (serverNumber) => post('queueServer/restartAllQueues', { serverNumber }),
  shutdownServer: (serverNumber) => post('queueServer/shutdownServer', { serverNumber }),
  rebootServer: (serverNumber) => post('queueServer/rebootServer', { serverNumber }),
  ec2Start: (serverNumber) => post('queueServer/ec2Start', { serverNumber }),
  ec2Stop: (serverNumber) => post('queueServer/ec2Stop', { serverNumber }),
  // fileData is plain base64 (no data: URL prefix)
  uploadJar: (serverNumber, serverHostName, file, fileData) =>
    post('queueServer/uploadJar', { serverNumber, serverHostName, file, fileData }),
}

export const frailtyApi = {
  getRecentStats: (minutesBack, groupBy, flagCode) => get('frailtyApi/recentStats', { minutesBack, groupBy, flagCode }),
  downloadMonthlyStats: () => get('frailtyApi/downloadMonthlyStats', undefined, { responseType: 'text' }),
  getSubscriberFlags: () => get('frailtyApi/subscriberFlags'),
}

export const hl7ReceiverApi = {
  getStatus: () => get('hl7Receiver/channelStatus'),
  pauseChannel: (channelId, pause) => post(`hl7Receiver/pause/${channelId}/${pause}`, {}),
}

export const hl7v2Api = {
  getStatus: () => get('hl7v2Api/status'),
}

export const sftpReaderApi = {
  getStatus: (configurationId) => get('sftpReader/status', { configurationId }),
  getInstances: (includeRetired = false) => get('sftpReader/instances', includeRetired ? { includeRetired: true } : undefined),
  getHistory: (configurationId, from, to) => get('sftpReader/history', { configurationId, from: from.getTime(), to: to.getTime() }),
  ignoreBatchSplit: (batchId, batchSplitId, configurationId, reason) =>
    post('sftpReader/ignore', { batchId, batchSplitId, configurationId, reason }),
  togglePause: (configurationId) => post('sftpReader/togglePause', { configurationId }),
  togglePauseAll: () => post('sftpReader/togglePauseAll', {}),
  getInstanceNames: () => get('sftpReader/instanceNames'),
  getInterfaceTypes: () => get('sftpReader/interfaceTypes'),
  addInterfaceType: (body) => post('sftpReader/interfaceTypes', body),
  deleteInterfaceType: (interfaceTypeId) => del('sftpReader/interfaceTypes/' + encodeURIComponent(interfaceTypeId)),
  addFeed: (body) => post('sftpReader/addFeed', body),
  getFeed: (configurationId) => get('sftpReader/feeds/' + encodeURIComponent(configurationId)),
  updateFeed: (configurationId, body) => http.put('sftpReader/feeds/' + encodeURIComponent(configurationId), body).then((r) => r.data),
  retireFeed: (configurationId) => post('sftpReader/feeds/' + encodeURIComponent(configurationId) + '/retire', {}),
  reinstateFeed: (configurationId, instanceName) =>
    post('sftpReader/feeds/' + encodeURIComponent(configurationId) + '/reinstate', { instanceName }),
  deleteFeed: (configurationId) => del('sftpReader/feeds/' + encodeURIComponent(configurationId)),
}

export const scheduledTaskApi = {
  getRules: () => get('scheduledTask/rules'),
  getSummary: () => get('scheduledTask/summary'),
  getHistory: (applicationName, taskName, from, to) =>
    get('scheduledTask/history', { applicationName, taskName, from: from.getTime(), to: to.getTime() }),
}

export const statsApi = {
  // serviceList / systemList are repeated query parameters
  getStorageStatistics: (services) => {
    const params = new URLSearchParams()
    for (const s of services) {
      params.append('serviceList', s.uuid)
      params.append('systemList', s.endpoints?.[0]?.systemUuid || '')
    }
    return http.get('stats/getStorageStatistics?' + params.toString()).then((r) => r.data)
  },
  downloadPatientCounts: () => get('stats/downloadPatientCounts', undefined, { responseType: 'text' }),
}

export const auditApi = {
  getUsers: () => get('audit/users'),
  getUserAudit: (module, userId, month, organisationId) =>
    get('audit', { module, userId, month: month.getTime(), organisationId: organisationId || undefined }),
  getModules: () => get('audit/modules'),
  getSubmodules: (module) => get('audit/submodules', { module }),
  getActions: () => get('audit/actions'),
}

export const loggingApi = {
  getLoggingEvents: (serviceId, level, page) =>
    get('logging/getLoggingEvents', { serviceId: serviceId || undefined, level: level || undefined, page }),
  getStackTrace: (eventId) => get('logging/getStackTrace', { eventId }, { responseType: 'text' }),
}

export const configManagerApi = {
  getRecords: () => get('configManager/records'),
  getHistory: (appId, configId) => get('configManager/history', { appId, configId }),
  saveRecord: (record) => post('configManager/saveRecord', record),
  deleteRecord: (record) => post('configManager/deleteRecord', record),
}

// VHP: the message formats that can be processed (config row global/message_formats; MessageFormatEndpoint)
export const messageFormatApi = {
  getAll: () => get('messageFormats'),
  add: (format) => post('messageFormats', format),
}

export const systemApi = {
  getSystems: () => get('library/getSystems'),
  getSystemList: () => get('library/getSystemList'),
  deleteSystem: (uuid) => del('library/deleteSystem', { uuid }),
  getLibraryItem: (uuid) => get('library/getLibraryItem', { uuid }),
  saveLibraryItem: (item) => post('library/saveLibraryItem', item),
}
