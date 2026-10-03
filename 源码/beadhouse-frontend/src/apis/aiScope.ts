import { http } from "@/utils";

export function pageAccessibleElders(data: { pageNum: number; pageSize: number; name?: string; phone?: string }) {
  return http.get("/api/ai/scope/elders", { params: data });
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
