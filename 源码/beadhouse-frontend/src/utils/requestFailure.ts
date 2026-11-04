import { isAxiosError, isCancel } from 'axios'

// HTTP already reports transport/API failures; cancellation intentionally has no toast.
// Keep programmer errors and unsupported response shapes visible to the caller.
export function handleRequestFailure(error: unknown): void {
  if (isAxiosError(error) || isCancel(error)) return
  if (Object.prototype.toString.call(error) === '[object Object]') {
    const result = error as { code?: unknown; msg?: unknown }
    if (typeof result.code === 'number' && Number.isInteger(result.code)
      && result.code >= 400 && result.code <= 599 && typeof result.msg === 'string') return
  }
  throw error
}
