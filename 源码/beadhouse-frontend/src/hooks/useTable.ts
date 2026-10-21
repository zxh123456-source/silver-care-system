import { Table } from './interface'
import { reactive, computed, toRefs, ref, watch, onBeforeUnmount } from 'vue'
import store from '@/store'
import { captureResourceSession, isResourceSessionCurrent } from '@/utils/authResource'
import type { ResourceSession } from '@/utils/authResource'
import { createAsyncScope } from '@/utils/asyncScope'

export const useTable = (
  api: (params: any, session?: ResourceSession) => Promise<any>,
  initParam: object = {},
  isPageable = true,
  dataCallBack?: (data: any) => any
) => {
  const state = reactive<Table.TableStateProps>({
    tableData: [], pageable: { pageNum: 1, pageSize: 10, total: 0 },
    searchParam: {}, searchInitParam: {}, totalParam: {}
  })
  const loading = ref(false)
  const error = ref('')
  const requests = createAsyncScope(isResourceSessionCurrent)
  let displayedSession: ResourceSession | undefined
  const pageParam = computed(() => ({ pageNum: state.pageable.pageNum, pageSize: state.pageable.pageSize }))

  const clearPreviousAccount = () => {
    requests.invalidate()
    state.tableData = []
    state.pageable.total = 0
    loading.value = false
    error.value = ''
  }
  const sessionChanged = () => {
    if (displayedSession && !isResourceSessionCurrent(displayedSession)) clearPreviousAccount()
  }
  const stopSessionWatch = watch(() => [store.state.app.token, store.state.app.userPeofile?.userid],
    sessionChanged, { flush: 'sync' })
  window.addEventListener('storage', sessionChanged)
  onBeforeUnmount(() => {
    requests.dispose()
    window.removeEventListener('storage', sessionChanged)
    stopSessionWatch()
  })

  const getTableList = async () => {
    let ticket: ReturnType<typeof requests.begin> | undefined
    try {
      const session = captureResourceSession()
      ticket = requests.begin(session)
      displayedSession = ticket.session
      loading.value = true
      error.value = ''
      Object.assign(state.totalParam, initParam, isPageable ? pageParam.value : {})
      const params: any = { ...state.searchInitParam, ...state.totalParam }
      if (params.consultDate) {
        params.startTime = params.consultDate[0]
        params.endTime = params.consultDate[1]
        delete params.consultDate
      }
      const response = await api(params, ticket.session)
      if (!requests.isCurrent(ticket)) return
      if (response?.code !== undefined && response.code !== 200) throw new Error(response.msg || '列表加载失败，请重试')
      let data = response?.data
      if (dataCallBack) data = dataCallBack(data)
      const rows = isPageable ? data?.list : data
      if (!Array.isArray(rows)) throw new Error('列表响应无效，请重试')
      state.tableData = rows
      if (isPageable) updatePageable({
        pageNum: data.pageNum ?? state.pageable.pageNum,
        pageSize: data.pageSize ?? state.pageable.pageSize,
        total: data.total ?? 0
      })
    } catch (failure: any) {
      if (!ticket) {
        clearPreviousAccount()
        error.value = failure?.msg || failure?.message || '列表加载失败，请重试'
      } else if (requests.isCurrent(ticket)) error.value = failure?.msg || failure?.message || '列表加载失败，请重试'
    } finally {
      if (ticket && requests.isCurrent(ticket)) loading.value = false
    }
  }
  const updatedTotalParam = () => {
    const nowSearchParam: { [key: string]: any } = {}
    for (const key in state.searchParam) {
      const value = state.searchParam[key]
      if (value || value === false || value === 0) nowSearchParam[key] = value
    }
    state.totalParam = { ...nowSearchParam, ...(isPageable ? pageParam.value : {}) }
  }
  const updatePageable = (resPageable: Table.Pageable) => Object.assign(state.pageable, resPageable)
  const search = () => {
    state.pageable.pageNum = 1
    updatedTotalParam()
    return getTableList()
  }
  const reset = () => {
    state.pageable.pageNum = 1
    state.searchParam = { ...state.searchInitParam }
    updatedTotalParam()
    return getTableList()
  }
  const handleSizeChange = (val: number) => {
    state.pageable.pageNum = 1
    state.pageable.pageSize = val
    return getTableList()
  }
  const handleCurrentChange = (val: number) => {
    state.pageable.pageNum = val
    return getTableList()
  }
  return { ...toRefs(state), loading, error, getTableList, search, reset, handleSizeChange,
    handleCurrentChange, updatedTotalParam }
}
