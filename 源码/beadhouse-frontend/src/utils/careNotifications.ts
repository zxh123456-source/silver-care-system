import { computed, onActivated, onBeforeUnmount, onDeactivated, onMounted, ref, watch } from "vue";
import store from "@/store";
import { captureResourceSession, isResourceSessionCurrent, ResourceSession } from "@/utils/authResource";
import { countCareNotifications, listCareNotifications, markCareNotificationRead, getCareScanStatus } from "@/apis/aiDaily";

export interface CareNotification {
  id: number; elderId: number; elderName: string; kind: string; detail: string;
  state: string; noticeRevision: number;
}
interface CareScanStatus {
  enabled: boolean;
  state: "RUNNING" | "RETRY_PENDING" | "IDLE";
  lastCompletedDate?: string | null;
}
const scanStatus = ref<CareScanStatus | null>(null);
const scanStatusText = computed(() => {
  const status = scanStatus.value;
  if (!status) return "后台核对状态暂不可用，可刷新查看";
  if (!status.enabled) return "后台自动核对未启用，可按需手动核对历史用药登记";
  if (status.state === "RETRY_PENDING") return "后台核对待重试，将自动继续处理";
  if (status.state === "RUNNING") return "后台正在核对历史用药登记";
  return status.lastCompletedDate ? `后台核对已处理至 ${status.lastCompletedDate}，后续将继续周期核对` : "后台自动核对已启用，等待执行";
});
const unreadCount = ref<number | null>(null);
const items = ref<CareNotification[]>([]);
const hasMore = ref(false);
const loading = ref(false);
const reading = ref(false);
const error = ref("");
const sessionTick = ref(0);
const consumers = new Map<symbol, boolean>();
let cursor = 0;
let generation = 0;
let timer: number | undefined;
let listening = false;
let stopWatch: (() => void) | undefined;
const allowed = computed(() => {
  void sessionTick.value;
  if (!store.state.app.token || !store.state.app.userPeofile?.userid
    || !store.state.app.userPeofile?.authIdList?.includes(50)) return false;
  try { return isResourceSessionCurrent(captureResourceSession()); } catch { return false; }
});
const detailActive = () => Array.from(consumers.values()).some(Boolean);
const visible = () => document.visibilityState !== "hidden";
const runnable = () => allowed.value && consumers.size > 0 && visible();
function clear() {
  scanStatus.value = null; unreadCount.value = null; items.value = []; cursor = 0; hasMore.value = false; error.value = "";
}
function invalidate() { generation++; loading.value = false; reading.value = false; }
function stopTimer() { if (timer !== undefined) window.clearInterval(timer); timer = undefined; }
function current(ticket: number, session: ResourceSession) {
  return ticket === generation && runnable() && isResourceSessionCurrent(session);
}
async function fetchScanStatus(ticket: number, session: ResourceSession) {
  try {
    const response: any = await getCareScanStatus(session);
    if (!current(ticket, session) || !detailActive()) return;
    const status = response.data;
    scanStatus.value = response.code === 200 && typeof status?.enabled === "boolean"
      && ["RUNNING", "RETRY_PENDING", "IDLE"].includes(status.state) ? status : null;
  } catch {
    if (current(ticket, session) && detailActive()) scanStatus.value = null;
  }
}
async function fetchPage(more = false) {
  if (!runnable() || loading.value || (more && (!detailActive() || !hasMore.value))) return;
  const session = captureResourceSession(), ticket = generation, detail = detailActive();
  loading.value = true; error.value = "";
  const statusRequest = detail && !more ? fetchScanStatus(ticket, session) : Promise.resolve();
  try {
    const response: any = detail ? await listCareNotifications(more ? cursor : 0, session) : await countCareNotifications(session);
    if (!current(ticket, session)) return;
    if (response.code !== 200) throw new Error(response.msg || "提醒暂不可用");
    unreadCount.value = response.data.unreadCount;
    if (detail) {
      const received: CareNotification[] = response.data.items || [];
      const merged = new Map<number, CareNotification>((more ? items.value : []).map(row => [row.id, row]));
      received.forEach(row => merged.set(row.id, row));
      items.value = Array.from(merged.values());
      cursor = response.data.nextCursor || 0; hasMore.value = !!response.data.hasMore;
    }
  } catch {
    if (current(ticket, session)) { clear(); error.value = "提醒暂不可用，请刷新重试"; }
  } finally { await statusRequest; if (ticket === generation) loading.value = false; }
}
async function markRead(row: CareNotification) {
  if (!runnable() || !detailActive() || reading.value) return;
  const session = captureResourceSession(), ticket = generation;
  reading.value = true;
  try {
    const response: any = await markCareNotificationRead({ id: row.id, noticeRevision: row.noticeRevision }, session);
    if (!current(ticket, session)) return;
    if (response.code === 409) {
      invalidate();
      await fetchPage();
      return;
    }
    if (response.code !== 200) throw new Error(response.msg || "未确认已读结果");
    // A previously started list must not restore an already read or superseded notice.
    invalidate();
    await fetchPage();
  } catch (failure: any) {
    if (current(ticket, session)) {
      if (failure?.code === 401 || failure?.code === 403) clear();
      error.value = "未确认已读结果，请刷新核对后重试";
    }
  } finally { if (ticket === generation) reading.value = false; }
}
function reconcile() {
  stopTimer(); invalidate(); sessionTick.value++;
  if (!allowed.value) clear();
  if (!runnable()) return;
  void fetchPage();
  timer = window.setInterval(() => { void fetchPage(); }, 45000);
}
function storageChanged() { clear(); reconcile(); }
function startListening() {
  if (listening) return;
  listening = true;
  window.addEventListener("storage", storageChanged);
  document.addEventListener("visibilitychange", reconcile);
  stopWatch = watch(() => [store.state.app.token, store.state.app.userPeofile?.userid,
    (store.state.app.userPeofile?.authIdList || []).join(",")], storageChanged);
}
function stopListening() {
  stopTimer(); invalidate(); clear();
  window.removeEventListener("storage", storageChanged);
  document.removeEventListener("visibilitychange", reconcile);
  stopWatch?.(); stopWatch = undefined; listening = false;
}

/** Shared polling is active only while at least one authorized visible consumer is mounted. */
export function useCareNotifications(details = false) {
  const key = Symbol("care-notifications");
  const activate = () => {
    if (consumers.has(key)) return;
    consumers.set(key, details); startListening(); reconcile();
  };
  const deactivate = () => {
    if (!consumers.delete(key)) return;
    if (!consumers.size) stopListening();
    else { if (!detailActive()) { items.value = []; scanStatus.value = null; } reconcile(); }
  };
  onMounted(activate); onActivated(activate); onDeactivated(deactivate); onBeforeUnmount(deactivate);
  return { allowed, unreadCount, items, hasMore, loading, reading, error, scanStatus, scanStatusText,
    refresh: () => fetchPage(), loadMore: () => fetchPage(true), markRead };
}
