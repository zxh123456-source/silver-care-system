import { http } from "@/utils";

export interface AiCareDraft {
  elderId: number;
  sourceText: string;
  observation?: string;
  actionTaken?: string;
  followUp?: string;
  appetite?: string;
  sleep?: string;
  medicine?: string;
  activity?: string;
  modelUsed?: boolean;
  warning?: string;
}

export function createCareDraft(data: { elderId: number; sourceText: string }) {
  return http.post("/api/ai/care/draft", data);
}

export function saveCareNote(data: AiCareDraft & { staffId?: number; eventTime?: string }) {
  return http.post("/api/ai/care/save", data);
}

export function updateCareNote(data: AiCareDraft & { id: number }) {
  return http.put("/api/ai/care/update", data);
}

export function getHandover(date?: string) {
  return http.get("/api/ai/care/handover", { params: date ? { date } : {} });
}

export function completeCareNote(id: number) {
  return http.put("/api/ai/care/complete", null, { params: { id } });
}

export function createFamilyReport(data: { elderId: number; startDate: string; endDate: string }) {
  return http.post("/api/ai/care/family-report", data);
}
