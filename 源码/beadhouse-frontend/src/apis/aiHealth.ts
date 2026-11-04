import type { ResourceSession } from "@/utils/authResource";
import { captureResourceSession, checkedResourceSessionConfig } from "@/utils/authResource";
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

export function addHealthMeasurement(data: HealthMeasurement, session: ResourceSession = captureResourceSession()) {
  return http.post("/api/ai/health/measurement", data, checkedResourceSessionConfig(session));
}

export function getHealthTrend(elderId: number, limit = 30, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/health/trend", { ...checkedResourceSessionConfig(session), params: { elderId, limit } });
}

export function pageSearchHealthElder(data: { pageNum: number; pageSize: number; name?: string; phone?: string }, session: ResourceSession = captureResourceSession()) {
  return http.get("/api/ai/health/elders", { ...checkedResourceSessionConfig(session), params: data });
}
