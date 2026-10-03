import { http } from "@/utils";

export function addMedicationPlan(data: any) {
  return http.post("/api/ai/medication/plan", data);
}

export function getMedicationDay(elderId: number, date: string) {
  return http.get("/api/ai/medication/day", { params: { elderId, date } });
}

export function recordMedicationExecution(data: any) {
  return http.post("/api/ai/medication/execute", data);
}

export function disableMedicationPlan(planId: number) {
  return http.put("/api/ai/medication/plan/disable", null, { params: { planId } });
}

export function pageSearchMedicationElder(data: { pageNum: number; pageSize: number; name?: string; phone?: string }) {
  return http.get("/api/ai/medication/elders", { params: data });
}
