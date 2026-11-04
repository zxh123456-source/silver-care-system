import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

export function useMobileNavigation() {
  const media = window.matchMedia('(max-width: 767px)')
  const isMobile = ref(media.matches)
  const navigationOpen = ref(false)
  const route = useRoute()
  const closeNavigation = () => { navigationOpen.value = false }
  const updateViewport = () => {
    isMobile.value = media.matches
    closeNavigation()
  }
  media.addEventListener('change', updateViewport)
  watch(() => route.fullPath, closeNavigation)
  onBeforeUnmount(() => media.removeEventListener('change', updateViewport))
  return { isMobile, navigationOpen, closeNavigation }
}
