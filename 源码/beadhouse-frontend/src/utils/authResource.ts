import store from "@/store";
import http, { isSameOriginApi } from "@/utils/http";
import { matchesPersistedRechargeSession } from "@/utils/rechargeIntent";
import type { AxiosRequestConfig, AxiosRequestTransformer } from "axios";

export interface ResourceSession {
  staffId: string;
  token: string;
}

export interface ResourceImage {
  resourceUrl: string;
  previewUrl: string;
  release: () => void;
}

export const XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export function isResourceSessionCurrent(session: ResourceSession): boolean {
  return String(store.state.app.userPeofile?.userid ?? "") === session.staffId
    && store.state.app.token === session.token && matchesPersistedRechargeSession(session);
}

function assertSession(session: ResourceSession): void {
  if (!isResourceSessionCurrent(session)) throw new Error("登录状态已变化，请刷新页面后再操作");
}

export function captureResourceSession(): ResourceSession {
  const session = { staffId: String(store.state.app.userPeofile?.userid ?? ""), token: store.state.app.token };
  assertSession(session);
  return session;
}

export function resourceSessionConfig(session: ResourceSession): AxiosRequestConfig {
  const captured = { ...session };
  const bind: AxiosRequestTransformer = function (data, headers) {
    assertSession(captured);
    if (!isSameOriginApi(this)) throw new Error("无法访问该资源地址");
    headers.set("token", captured.token);
    return data;
  };
  const defaults = http.defaults.transformRequest;
  return { transformRequest: [...(Array.isArray(defaults) ? defaults : defaults ? [defaults] : []), bind] };
}

// These entry points also guard synchronous invocation before Axios queues interceptors.
export function checkedResourceSessionConfig(session: ResourceSession): AxiosRequestConfig {
  assertSession(session);
  return resourceSessionConfig(session);
}

export function canonicalResourceUrl(value: unknown): string | null {
  if (typeof value !== "string" || !value) return null;
  try {
    const url = new URL(value, window.location.origin);
    return url.origin === window.location.origin && /^https?:$/.test(url.protocol)
      && /^\/api\/file\/[1-9]\d*\/content$/.test(url.pathname) && !url.search && !url.hash
      ? url.pathname : null;
  } catch { return null; }
}

function metadataUrl(response: any): string {
  const url = canonicalResourceUrl(response?.data?.url);
  if (response?.code !== 200 || !url || String(response.data.id) !== url.split("/")[3]) {
    throw new Error(response?.msg || "图片信息无效，请重新上传");
  }
  return url;
}

export async function fetchResourceImage(value: string, session: ResourceSession): Promise<ResourceImage> {
  assertSession(session);
  let resourceUrl = canonicalResourceUrl(value);
  if (!resourceUrl) {
    if (!value || /^(?:blob|data|javascript):/i.test(value)) throw new Error("图片暂不可查看");
    const resolved = await http.get("/api/file/resolve", {
      params: { url: value }, ...resourceSessionConfig(session)
    });
    assertSession(session);
    resourceUrl = metadataUrl(resolved);
  }
  const blob: any = await http.get(resourceUrl, { responseType: "blob", ...resourceSessionConfig(session) });
  assertSession(session);
  if (!(blob instanceof Blob) || !blob.size || !/^image\/(png|jpeg)$/i.test(blob.type.split(";")[0])) {
    throw new Error("图片暂不可预览，请重新上传 PNG 或 JPG 图片");
  }
  const previewUrl = URL.createObjectURL(blob);
  let released = false;
  return { resourceUrl, previewUrl, release: () => {
    if (!released) URL.revokeObjectURL(previewUrl);
    released = true;
  } };
}

export async function uploadResourceImage(file: File, session: ResourceSession): Promise<{ id: string; url: string }> {
  assertSession(session);
  const data = new FormData();
  data.append("file", file);
  const response: any = await http.post("/api/file/uploadImg", data, resourceSessionConfig(session));
  assertSession(session);
  const url = metadataUrl(response);
  return { id: String(response.data.id), url };
}

export async function requireWorkbookBlob(value: unknown): Promise<Blob> {
  if (!(value instanceof Blob) || value.type.split(";")[0].toLowerCase() !== XLSX_MIME || value.size < 4) {
    throw new Error("未收到有效的导出文件，请重试");
  }
  const prefix = new Uint8Array(await value.slice(0, 4).arrayBuffer());
  if (prefix[0] !== 0x50 || prefix[1] !== 0x4b || prefix[2] !== 0x03 || prefix[3] !== 0x04) {
    throw new Error("导出文件内容无效，请重试");
  }
  return value;
}
