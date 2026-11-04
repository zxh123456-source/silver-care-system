import type { ResourceSession } from "@/utils/authResource";
import { captureResourceSession, checkedResourceSessionConfig } from "@/utils/authResource";
import { http } from "@/utils";

export function pageAccessibleElders(data: { pageNum: number; pageSize: number; name?: string; phone?: string }, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/scope/elders", { ...checkedResourceSessionConfig(session), params: data });
}

export function listElderAssignments() {
  return http.get("/api/ai/scope/assignments");
}

export function listScopeStaff() {
  return http.get("/api/ai/scope/staff-options");
}

export function assignElder(data: { elderId: number; staffId: number }) {
  return http.post("/api/ai/scope/assignment", data);
}

export function revokeElderAssignment(elderId: number, staffId: number) {
  return http.delete("/api/ai/scope/assignment", { params: { elderId, staffId } });
}
