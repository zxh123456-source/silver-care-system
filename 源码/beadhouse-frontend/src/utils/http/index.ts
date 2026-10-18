import store from "@/store";
import baseAxios, { AxiosRequestConfig, AxiosResponse } from "axios";
import { ElMessage } from "element-plus";

export const baseUrl = "http://127.0.0.1:9001/";

const axios = baseAxios.create({
  baseURL: "",
  timeout: 5000,
  headers: {}
});

const publicAuthPaths = new Set(["/api/account/login", "/api/account/sendCode", "/api/account/forget"]);
let redirectingToLogin = false;
function requestPath(config?: AxiosRequestConfig): string {
  return new URL(config?.url || "", window.location.origin).pathname;
}
function storedToken(): string | undefined {
  try {
    const token = JSON.parse(localStorage.getItem("stateData") || "null")?.app?.token;
    return typeof token === "string" && token ? token : undefined;
  } catch { return undefined; }
}
function handleAuthFailure(data: any, status: number, config?: AxiosRequestConfig): boolean {
  const expired = status === 401 || data?.code === 401 || (data?.code === 500 && data?.msg === "令牌无效");
  const forbidden = status === 403 || data?.code === 403;
  if (!expired && !forbidden) return false;
  if (expired && publicAuthPaths.has(requestPath(config))) {
    ElMessage.error(data?.msg || "账号或密码错误");
    return true;
  }
  const current = storedToken();
  const sent = (config?.headers as any)?.token;
  // A late failure from an older request must not log out a newly established session.
  if (current && sent !== current) return true;
  if (expired) {
    if (!redirectingToLogin) {
      redirectingToLogin = true;
      store.dispatch("app/logout");
      ElMessage.error("登录已失效，请重新登录");
    }
  } else {
    ElMessage.error(data?.msg || "权限不足");
  }
  return true;
}

// 请求拦截器
axios.interceptors.request.use((config: any) => {
  const token = storedToken();
  if (token && !publicAuthPaths.has(requestPath(config))) config.headers.token = token;
  else delete config.headers.token;
  return config;
});

// 响应拦截器
axios.interceptors.response.use(
  (res: AxiosResponse) => {
    if (handleAuthFailure(res.data, res.status, res.config)) return Promise.reject(res.data);
    if (requestPath(res.config) === "/api/account/login" && res.data.code === 200) redirectingToLogin = false;
    if (res.data.err === 1) {
      return Promise.reject(res.data);
    }
    return res.data;
  },
  err => {
    const data = err.response?.data;
    if (!handleAuthFailure(data, err.response?.status, err.config)) {
      ElMessage.error(data?.msg || "请求失败，请稍后重试");
    }
    return Promise.reject(data || err);
  }
);

export default axios;
