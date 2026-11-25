import { ref } from 'vue'
import { useRouter } from 'vue-router'

/**
 * eds-ui ServiceListComponent.viewExchanges / editService: open the exchange audit for a service, asking
 * which system (data feed) first if it has more than one. Use with <SystemPickerDialog>.
 */
export function useServiceNavigation() {
  const router = useRouter()
  const pickerVisible = ref(false)
  const pickerService = ref(null)

  function viewExchanges(service) {
    const endpoints = service?.endpoints || []
    if (endpoints.length === 0) return
    if (endpoints.length === 1) {
      router.push(`/exchangeAudit/${service.uuid}/${endpoints[0].systemUuid}`)
    } else {
      pickerService.value = service
      pickerVisible.value = true
    }
  }

  function onSystemPicked(systemId) {
    pickerVisible.value = false
    router.push(`/exchangeAudit/${pickerService.value.uuid}/${systemId}`)
  }

  function editService(serviceUuid) {
    router.push(`/serviceEdit/edit/${serviceUuid}`)
  }

  return { pickerVisible, pickerService, viewExchanges, onSystemPicked, editService }
}
