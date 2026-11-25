import { useToast } from 'primevue/usetoast'
import { useConfirm } from 'primevue/useconfirm'
import { errorMessage } from '@/services/http'
import Casdoor from '@/services/casdoor'

/** Toast helpers with the same meaning as eds-ui's LoggerService (success / info / warning / error). */
export function useNotify() {
  const toast = useToast()
  return {
    toast,
    success: (summary, detail) => toast.add({ severity: 'success', summary, detail, life: 3000 }),
    info: (summary, detail) => toast.add({ severity: 'info', summary, detail, life: 3000 }),
    warning: (summary, detail) => toast.add({ severity: 'warn', summary, detail, life: 5000 }),
    error: (summary, err) =>
      toast.add({ severity: 'error', summary, detail: err ? errorMessage(err) : undefined, life: 8000 }),
  }
}

/** Promise-based yes/no confirmation (eds-ui's MessageBoxDialog). Resolves true on Yes. */
export function useConfirmAsync() {
  const confirm = useConfirm()
  return (message, header = 'Confirm', acceptLabel = 'Yes', danger = false) =>
    new Promise((resolve) => {
      confirm.require({
        message,
        header,
        icon: 'fa-solid fa-triangle-exclamation',
        acceptProps: { label: acceptLabel, severity: danger ? 'danger' : undefined },
        rejectProps: { label: 'No', severity: 'secondary', outlined: true },
        accept: () => resolve(true),
        reject: () => resolve(false),
        onHide: () => resolve(false),
      })
    })
}

/** True when the token holds vhp_ui_admin; used to hide admin-only actions (the API also enforces it). */
export const isAdmin = () => Casdoor.isAdmin()

/** Sorts a copy of an array by a key function (eds-ui used linq OrderBy). */
export function orderBy(arr, keyFn, desc = false) {
  const copy = [...(arr || [])]
  copy.sort((a, b) => {
    const ka = keyFn(a)
    const kb = keyFn(b)
    if (ka < kb) return desc ? 1 : -1
    if (ka > kb) return desc ? -1 : 1
    return 0
  })
  return copy
}

/** Builds a CSV line, quoting every value as eds-ui did. */
export function csvLine(cols) {
  return cols.map((c) => '"' + String(c ?? '').replace(/"/g, '""') + '"').join(',')
}
