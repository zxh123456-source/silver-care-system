import { http } from "@/utils";

export function getAiAuditPage(pageNum = 1, pageSize = 20) {
  return http.get("/api/ai/audit/page", { params: { pageNum, pageSize } });
}

export function getAiMetricsSummary(days = 7) {
  return http.get("/api/ai/audit/metrics/summary", { params: { days } });
}
