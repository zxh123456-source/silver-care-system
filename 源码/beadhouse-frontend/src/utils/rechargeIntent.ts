import type { RechargeReceipt } from "@/apis/depositRecharge";

export interface RechargeIntent {
  requestId: string;
  elderId: string;
  amount: string;
}

export interface RechargeSession {
  staffId: string;
  token: string;
}

export function matchesPersistedRechargeSession(session: RechargeSession): boolean {
  if (!session.staffId || !session.token) return false;
  try {
    const app = JSON.parse(window.localStorage.getItem("stateData") || "null")?.app;
    return String(app?.userPeofile?.userid ?? "") === session.staffId && app?.token === session.token;
  } catch {
    return false;
  }
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

// Convert decimal text to cents without silently rounding a third decimal place.
export function normalizeRechargeAmount(value: unknown): string | null {
  const text = String(value ?? "").trim();
  if (!/^\d+(?:\.\d{1,2})?$/.test(text)) return null;
  const [whole, fraction = ""] = text.split(".");
  const cents = Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
  if (!Number.isSafeInteger(cents) || cents < 1 || cents > 9999999999) return null;
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, "0")}`;
}

function storageKey(staffId: string, elderId: string): string {
  if (!staffId || !elderId) throw new Error("缺少充值账户信息，请重新打开充值窗口。");
  return `silver-care:recharge-intent:v1:${encodeURIComponent(staffId)}:${encodeURIComponent(elderId)}`;
}

export function loadRechargeIntent(staffId: string, elderId: string): RechargeIntent | null {
  const raw = window.sessionStorage.getItem(storageKey(staffId, elderId));
  if (raw === null) return null;
  const parsed = JSON.parse(raw);
  if (!parsed || typeof parsed.requestId !== "string" || !uuidPattern.test(parsed.requestId)
      || parsed.elderId !== elderId || typeof parsed.amount !== "string"
      || normalizeRechargeAmount(parsed.amount) !== parsed.amount) {
    throw new Error("待确认充值信息无法读取，请先核对充值记录。");
  }
  return { requestId: parsed.requestId, elderId: parsed.elderId, amount: parsed.amount };
}

function createRequestId(): string {
  const secureCrypto = window.crypto as Crypto & { randomUUID?: () => string };
  if (!secureCrypto?.getRandomValues) {
    throw new Error("当前浏览器无法安全保存充值，请使用安全连接后重试。");
  }
  if (secureCrypto.randomUUID) return secureCrypto.randomUUID();
  const bytes = secureCrypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function prepareRechargeIntent(staffId: string, elderId: string, value: unknown): RechargeIntent {
  const amount = normalizeRechargeAmount(value);
  if (!amount) throw new Error("充值金额应为 0.01～99999999.99 元，最多两位小数。");
  const pending = loadRechargeIntent(staffId, elderId);
  const intent = pending?.amount === amount ? pending : { requestId: createRequestId(), elderId, amount };
  const key = storageKey(staffId, elderId);
  const serialized = JSON.stringify(intent);
  window.sessionStorage.setItem(key, serialized);
  if (window.sessionStorage.getItem(key) !== serialized) {
    throw new Error("未能保存本次充值，请检查浏览器存储后重试。");
  }
  return intent;
}

export function receiptMatchesIntent(receipt: RechargeReceipt | undefined, staffId: string, intent: RechargeIntent): boolean {
  return !!receipt && receipt.id !== null && receipt.id !== undefined
    && receipt.requestId === intent.requestId && String(receipt.staffId) === staffId
    && String(receipt.elderId) === intent.elderId && normalizeRechargeAmount(receipt.amount) === intent.amount;
}

export function clearRechargeIntent(staffId: string, intent: RechargeIntent): void {
  const pending = loadRechargeIntent(staffId, intent.elderId);
  if (pending?.requestId === intent.requestId && pending.amount === intent.amount) {
    window.sessionStorage.removeItem(storageKey(staffId, intent.elderId));
  }
}
