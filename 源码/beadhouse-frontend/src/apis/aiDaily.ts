import { http } from "@/utils";

export function listDailyTasks(date: string, state: string) {
  return http.get("/api/ai/daily/tasks", { params: { date, state } });
}
export function syncDailyTasks(date: string) {
  return http.post("/api/ai/daily/tasks/sync", null, { params: { date } });
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
