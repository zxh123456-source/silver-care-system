import type { ResourceSession } from "@/utils/authResource";
import { captureResourceSession, checkedResourceSessionConfig } from "@/utils/authResource";
import { http } from "@/utils";

export function addMedicationPlan(data: any, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/medication/plan", data, checkedResourceSessionConfig(session));
}

export function getMedicationDay(elderId: number, date: string, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/medication/day", { ...checkedResourceSessionConfig(session), params: { elderId, date } });
}

export function recordMedicationExecution(data: any, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/medication/execute", data, checkedResourceSessionConfig(session));
}

export function disableMedicationPlan(planId: number, session: ResourceSession = captureResourceSession()) {
  return http.put("/api/ai/medication/plan/disable", null, { ...checkedResourceSessionConfig(session), params: { planId } });
}

export function pageSearchMedicationElder(data: { pageNum: number; pageSize: number; name?: string; phone?: string }, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/medication/elders", { ...checkedResourceSessionConfig(session), params: data });
}
