// eds-ui SubscribersComponent / SubscriberDetailComponent display helpers

export function locationDesc(sub) {
  const l = sub.subscriberLocation
  if (!l) return '?'
  if (l === 'Internal') return 'I'
  if (l === 'Remote') return 'R'
  if (l === 'InternalAndRemote') return 'IR'
  return 'Unknown location ' + l
}

export function schemaDesc(sub) {
  if (!sub.schema) return '?'
  if (sub.schema === 'CompassV1') return 'v1'
  if (sub.schema === 'CompassV2') return 'v2'
  return 'Unknown schema ' + sub.schema
}

export function piDesc(sub) {
  return sub.deidentified ? 'pseudo' : 'PI'
}

export function remoteIdDesc(sub) {
  return sub.remoteSubscriberId ? 'Remote subscriber ID ' + sub.remoteSubscriberId : null
}
