import { http } from "@/utils";

export function importPolicy(data: { title: string; source: string; content: string }) {
  return http.post("/api/ai/policy/import", data);
}

export function queryPolicy(question: string) {
  return http.post("/api/ai/policy/query", { question });
}

export function listPolicyDocuments() {
  return http.get("/api/ai/policy/documents");
}

export function deletePolicyDocument(title: string, source: string) {
  return http.delete("/api/ai/policy/document", { params: { title, source } });
}

export function uploadPolicy(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return http.post("/api/ai/policy/upload", formData, { headers: { "Content-Type": "multipart/form-data" } });
}

export function getPolicyOcrStatus() {
  return http.get("/api/ai/policy/ocr/status");
}

export function reindexPolicies() {
  return http.post("/api/ai/policy/reindex");
}
