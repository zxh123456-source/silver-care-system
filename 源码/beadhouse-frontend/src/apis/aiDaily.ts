import { http } from "@/utils";
import { resourceSessionConfig, ResourceSession } from "@/utils/authResource";
export function listCareAlerts(state: string) { return http.get("/api/ai/daily/alerts", { params: { state } }); }
export function scanCareAlerts(date: string) { return http.post("/api/ai/daily/alerts/scan", null, { params: { date } }); }
export function actCareAlert(action: "ack" | "resolve", data: { id: number; revision: number; note?: string }) {
  return http.post(`/api/ai/daily/alerts/${action}`, data);
}

export function listDailyTasks(date: string, state: string) {
  return http.get("/api/ai/daily/tasks", { params: { date, state } });
}
export function syncDailyTasks(date: string) {
  return http.post("/api/ai/daily/tasks/sync", null, { params: { date }, timeout: 150000 });
}
export function dailyTaskOwners(id: number) {
  return http.get("/api/ai/daily/tasks/owners", { params: { id } });
}
export function actDailyTask(action: "claim" | "transfer" | "complete", data: {
  id: number; revision: number; targetStaffId?: number; note?: string;
}) { return http.post(`/api/ai/daily/tasks/${action}`, data); }

export function getDailyOverview(date: string) {
  return http.get("/api/ai/daily/overview", { params: { date } });
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
