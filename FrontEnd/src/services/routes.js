// Top-level SPA route segments. Used to work out the deployed context path (e.g. "/vhp-dds-ui/")
// from window.location, for the API base URL, the Casdoor callback URL and the router.
// Keep in sync with router/index.js.
export const KNOWN_ROUTES = [
  'services', 'serviceEdit', 'exchangeAudit', 'transformErrors', 'subscribers', 'remoteFiling',
  'queueReaders', 'queueServerAdmin', 'subscriberApi', 'hl7Receiver', 'hl7v2Api', 'sftpReader',
  'scheduledTasks', 'stats', 'audit', 'monitoring', 'routingConfig', 'systems', 'systemEdit',
  'configManager', 'callback',
]

export function getAppBasePath() {
  const path = window.location.pathname
  let routeIndex = -1
  for (const route of KNOWN_ROUTES) {
    const idx = path.indexOf('/' + route)
    if (idx > 0 && (routeIndex === -1 || idx < routeIndex)) {
      routeIndex = idx
    }
  }
  if (routeIndex > 0) {
    return path.substring(0, routeIndex) + '/'
  }
  const parts = path.split('/').filter((p) => p)
  if (parts.length > 0 && !KNOWN_ROUTES.includes(parts[0]) && !parts[0].includes('.')) {
    return '/' + parts[0] + '/'
  }
  return '/'
}
