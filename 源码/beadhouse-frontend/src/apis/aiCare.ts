import type { ResourceSession } from "@/utils/authResource";
import { captureResourceSession, checkedResourceSessionConfig } from "@/utils/authResource";
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

export function createCareDraft(data: { elderId: number; sourceText: string }, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/care/draft", data, checkedResourceSessionConfig(session));
}

export function saveCareNote(data: AiCareDraft & { staffId?: number; eventTime?: string }, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/care/save", data, checkedResourceSessionConfig(session));
}

export function updateCareNote(data: AiCareDraft & { id: number }, session: ResourceSession = captureResourceSession()) {
  return http.put("/api/ai/care/update", data, checkedResourceSessionConfig(session));
}

export function getHandover(date?: string, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/care/handover", { ...checkedResourceSessionConfig(session), params: date ? { date } : {} });
}

export function completeCareNote(id: number, session: ResourceSession = captureResourceSession()) {
  return http.put("/api/ai/care/complete", null, { ...checkedResourceSessionConfig(session), params: { id } });
}

export function createFamilyReport(data: { elderId: number; startDate: string; endDate: string }, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/care/family-report", data, checkedResourceSessionConfig(session));
}
