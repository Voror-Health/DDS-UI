// Same order and captions as eds-ui's dataServiceManager.menu.ts, plus the pages added by the dds-ui
// Vue port (Queue Server Admin). Icons are Font Awesome 6.
export default [
  { caption: 'Publishers', icon: 'fa-solid fa-circle-up', route: '/services' },
  { caption: 'Publisher Errors', icon: 'fa-solid fa-right-left', route: '/transformErrors' },
  { caption: 'Subscribers', icon: 'fa-solid fa-circle-down', route: '/subscribers' },
  { caption: 'Remote Filing', icon: 'fa-solid fa-square-up-right', route: '/remoteFiling' },
  { caption: 'Queue Readers', icon: 'fa-solid fa-gauge-high', route: '/queueReaders' },
  { caption: 'Queue Server Admin', icon: 'fa-solid fa-server', route: '/queueServerAdmin' },
  { caption: 'Subscriber API', icon: 'fa-solid fa-flask', route: '/subscriberApi' },
  { caption: 'HL7 Receiver', icon: 'fa-solid fa-fire', route: '/hl7Receiver' },
  { caption: 'HL7v2 API', icon: 'fa-solid fa-fire-flame-curved', route: '/hl7v2Api' },
  { caption: 'SFTP Reader', icon: 'fa-solid fa-table', route: '/sftpReader' },
  { caption: 'Scheduled Tasks', icon: 'fa-solid fa-clock', route: '/scheduledTasks' },
  { caption: 'Statistics', icon: 'fa-solid fa-chart-line', route: '/stats' },
  { caption: 'Audit', icon: 'fa-solid fa-list-ul', route: '/audit' },
  { caption: 'Monitoring', icon: 'fa-solid fa-rectangle-list', route: '/monitoring' },
  { caption: 'Routing Config', icon: 'fa-solid fa-signs-post', route: '/routingConfig' },
  { caption: 'System Config', icon: 'fa-solid fa-building', route: '/systems' },
  { caption: 'Config Manager', icon: 'fa-solid fa-umbrella', route: '/configManager' },
]
