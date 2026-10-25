export interface AsyncTicket<Session extends object> {
  readonly generation: number;
  readonly session: Session;
}

export function createAsyncScope<Session extends object>(isSessionCurrent: (session: Session) => boolean) {
  let generation = 0;
  let alive = true;
  const matches = (session: Session) => {
    try { return isSessionCurrent(session) === true; } catch { return false; }
  };
  return {
    begin(session: Session): AsyncTicket<Session> {
      if (!alive || !matches(session)) throw new Error('登录状态已变化，请刷新页面后再操作');
      return Object.freeze({ generation: ++generation, session: Object.freeze({ ...session }) as Session });
    },
    isCurrent(ticket?: AsyncTicket<Session>): boolean {
      return !!ticket && alive && ticket.generation === generation && matches(ticket.session);
    },
    invalidate(): void { generation++; },
    dispose(): void { alive = false; generation++; }
  };
}
