import { createApp } from 'vue'
import PrimeVue from 'primevue/config'
import Aura from '@primeuix/themes/aura'
import { definePreset } from '@primeuix/themes'
import ToastService from 'primevue/toastservice'
import ConfirmationService from 'primevue/confirmationservice'
import Tooltip from 'primevue/tooltip'

import Button from 'primevue/button'
import DataTable from 'primevue/datatable'
import Column from 'primevue/column'
import Dialog from 'primevue/dialog'
import InputText from 'primevue/inputtext'
import InputNumber from 'primevue/inputnumber'
import Textarea from 'primevue/textarea'
import Select from 'primevue/select'
import MultiSelect from 'primevue/multiselect'
import Checkbox from 'primevue/checkbox'
import RadioButton from 'primevue/radiobutton'
import ToggleSwitch from 'primevue/toggleswitch'
import DatePicker from 'primevue/datepicker'
import Tag from 'primevue/tag'
import Tabs from 'primevue/tabs'
import TabList from 'primevue/tablist'
import Tab from 'primevue/tab'
import TabPanels from 'primevue/tabpanels'
import TabPanel from 'primevue/tabpanel'
import ProgressSpinner from 'primevue/progressspinner'
import Message from 'primevue/message'
import Panel from 'primevue/panel'

import '@fortawesome/fontawesome-free/css/all.min.css'
import './style.css'

import App from './App.vue'
import router from './router'
import Casdoor from './services/casdoor'

// Aura with the DDS navy/blue as the primary colour (the old UI used #04466B / bootstrap blue)
const DdsPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: '{sky.50}',
      100: '{sky.100}',
      200: '{sky.200}',
      300: '{sky.300}',
      400: '{sky.400}',
      500: '{sky.600}',
      600: '{sky.700}',
      700: '{sky.800}',
      800: '{sky.900}',
      900: '{sky.950}',
      950: '{sky.950}',
    },
  },
})

const app = createApp(App)
app.use(PrimeVue, { theme: { preset: DdsPreset, options: { darkModeSelector: '.dds-dark' } } })
app.use(ToastService)
app.use(ConfirmationService)
app.directive('tooltip', Tooltip)

const components = {
  Button, DataTable, Column, Dialog, InputText, InputNumber, Textarea, Select, MultiSelect, Checkbox,
  RadioButton, ToggleSwitch, DatePicker, Tag, Tabs, TabList, Tab, TabPanels, TabPanel, ProgressSpinner,
  Message, Panel,
}
for (const [name, component] of Object.entries(components)) {
  app.component(name, component)
}

app.use(router)

// log in with Casdoor first; only mount once we hold a token
Casdoor.init()
  .then(() => app.mount('#app'))
  .catch((e) => {
    document.getElementById('app').innerHTML =
      '<div style="font-family:sans-serif;padding:3rem;text-align:center"><h2>Unable to log in</h2><p></p>' +
      '<p><a href="">Try again</a></p></div>'
    document.querySelector('#app p').textContent = e?.message || String(e)
  })
