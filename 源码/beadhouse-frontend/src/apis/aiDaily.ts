import { http } from "@/utils";
import { captureResourceSession, checkedResourceSessionConfig, resourceSessionConfig, ResourceSession } from "@/utils/authResource";
export function listCareAlerts(state: string, session: ResourceSession = captureResourceSession()) { return http.get("/api/ai/daily/alerts", { ...checkedResourceSessionConfig(session), params: { state } }); }
export function scanCareAlerts(date: string, session: ResourceSession = captureResourceSession()) { return http.post("/api/ai/daily/alerts/scan", null, { ...checkedResourceSessionConfig(session), params: { date } }); }
export function actCareAlert(action: "ack" | "resolve", data: { id: number; revision: number; note?: string }, session: ResourceSession = captureResourceSession()) {
  return http.post(`/api/ai/daily/alerts/${action}`, data, checkedResourceSessionConfig(session));
}

export function listDailyTasks(date: string, state: string, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/daily/tasks", { ...checkedResourceSessionConfig(session), params: { date, state } });
}
export function syncDailyTasks(date: string, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/daily/tasks/sync", null, { ...checkedResourceSessionConfig(session), params: { date }, timeout: 150000 });
}
export function dailyTaskOwners(id: number, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/daily/tasks/owners", { ...checkedResourceSessionConfig(session), params: { id } });
}
export function actDailyTask(action: "claim" | "transfer" | "complete", data: {
  id: number; revision: number; targetStaffId?: number; note?: string;
}, session: ResourceSession = captureResourceSession()) { return http.post(`/api/ai/daily/tasks/${action}`, data, checkedResourceSessionConfig(session)); }

export function getDailyOverview(date: string, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/daily/overview", { ...checkedResourceSessionConfig(session), params: { date } });
}

// Notification reads are personal receipts; they never acknowledge or resolve the care alert.
export function listCareNotifications(afterId: number, session: ResourceSession) {
  return http.get("/api/ai/daily/notifications", { params: { afterId, limit: 20 }, ...resourceSessionConfig(session) });
}
export function countCareNotifications(session: ResourceSession) {
  return http.get("/api/ai/daily/notification-count", resourceSessionConfig(session));
}
export function markCareNotificationRead(data: { id: number; noticeRevision: number }, session: ResourceSession) {
  return http.post("/api/ai/daily/notifications/read", data, resourceSessionConfig(session));
}

export function getCareScanStatus(session: ResourceSession) {
  return http.get("/api/ai/daily/scan-status", resourceSessionConfig(session));
}
