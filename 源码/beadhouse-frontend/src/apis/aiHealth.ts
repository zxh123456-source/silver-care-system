import { http } from "@/utils";

export interface HealthMeasurement {
  elderId: number;
  measureTime: string;
  weight?: number;
  temperature?: number;
  heartRate?: number;
  systolicBloodPressure?: number;
  diastolicBloodPressure?: number;
  bloodOxygenSaturation?: number;
  fastingBloodGlucose?: number;
  postprandialBloodGlucose?: number;
  remarks?: string;
}

export function addHealthMeasurement(data: HealthMeasurement) {
  return http.post("/api/ai/health/measurement", data);
}

export function getHealthTrend(elderId: number, limit = 30) {
  return http.get("/api/ai/health/trend", { params: { elderId, limit } });
}

export function pageSearchHealthElder(data: { pageNum: number; pageSize: number; name?: string; phone?: string }) {
  return http.get("/api/ai/health/elders", { params: data });
}
