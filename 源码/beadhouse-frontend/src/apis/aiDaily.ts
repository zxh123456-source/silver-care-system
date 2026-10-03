import { http } from "@/utils";

export function getDailyOverview(date: string) {
  return http.get("/api/ai/daily/overview", { params: { date } });
}
