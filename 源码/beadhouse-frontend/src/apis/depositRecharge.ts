import type { ResourceSession } from "@/utils/authResource";
import { captureResourceSession, checkedResourceSessionConfig } from "@/utils/authResource";
import { http } from "@/utils";
import { IPageSearchElderByKey } from "@/apis/bookManage";
import type { AxiosRequestConfig, AxiosRequestTransformer } from "axios";
import { matchesPersistedRechargeSession, RechargeSession } from "@/utils/rechargeIntent";

interface IPageDepositRechargeByKey {
  idNum:string;
  name:string;
  elderName:string;
  phone: string;
  elderPhone: string;
}

interface IRecharge {
  elderId: string;
  amount: string;
  requestId: string;
}

export interface RechargeReceipt {
  id: string | number;
  requestId: string;
  elderId: string | number;
  staffId: string | number;
  amount: string | number;
  balanceBefore: string | number;
  balanceAfter: string | number;
  createdAt: string;
}

interface IRechargeLedgerPage {
  elderId: string;
  pageNum: number;
  pageSize: number;
}

// 分页查询预存充值
export async function pageDepositRechargeByKey(data: IPageDepositRechargeByKey) {
  // 因为后台返回的字段与前端表单数据的prop不一样，但是组件封装是需要一样的，所以请求前增加一些这两个字段
  if (Reflect.has(data, 'elderName')) data.name = data.elderName
  if (Reflect.has(data, 'elderPhone')) data.phone = data.elderPhone
  return http.get("/api/depositRecharge/pageDepositRechargeByKey", {
    params: {
      ...data
    }
  });
}

// 分页搜索老人
export function pageSearchElderByKey(data: IPageSearchElderByKey, session: ResourceSession = captureResourceSession()) {
  return http.get('/api/depositRecharge/pageSearchElderByKey', { ...checkedResourceSessionConfig(session),
    params: {
      ...data
    }
  })
}

export function rechargeSessionConfig(session: RechargeSession): AxiosRequestConfig {
  const captured = { staffId: session.staffId, token: session.token };
  const bindSession: AxiosRequestTransformer = (data, headers) => {
    if (!matchesPersistedRechargeSession(captured)) {
      throw new Error("登录状态已变化，请刷新页面后再操作");
    }
    // Run after the shared interceptor: it may have read a newer tab's token.
    headers.set("token", captured.token);
    return data;
  };
  const defaults = http.defaults.transformRequest;
  return { transformRequest: [...(Array.isArray(defaults) ? defaults : defaults ? [defaults] : []), bindSession] };
}

// 入住老人账户充值
export function recharge(data: IRecharge, session: RechargeSession) {
  return http.put("/api/depositRecharge/recharge", data, rechargeSessionConfig(session));
}

export function pageRechargeLedger(params: IRechargeLedgerPage, session: RechargeSession) {
  return http.get("/api/depositRecharge/pageRechargeLedger", { params, ...rechargeSessionConfig(session) });
}

export function getRechargeReceipt(params: { elderId: string; requestId: string }, session: RechargeSession) {
  return http.get("/api/depositRecharge/getRechargeReceipt", { params, ...rechargeSessionConfig(session) });
}
