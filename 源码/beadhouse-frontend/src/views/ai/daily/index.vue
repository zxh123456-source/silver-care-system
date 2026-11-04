<template>
  <div class="daily-page care-mobile-page">
    <el-card class="hero" shadow="never">
      <div>
        <h2>每日护理助手</h2>
        <p>统一汇总护理、用药、健康变化和服务预约。所有处理仍需进入原页面人工确认。</p>
      </div>
      <div class="actions"><el-date-picker popper-class="care-mobile-picker" v-model="selectedDate" type="date" value-format="YYYY-MM-DD" :clearable="false" /><el-button type="primary" :loading="loading" @click="load">刷新</el-button></div>
    </el-card>
    <el-alert v-if="loadError" class="request-error" :title="loadError" type="error" :closable="false" show-icon><el-button :loading="loading" @click="load">重新加载</el-button></el-alert>
    <el-skeleton v-if="loading && !overview" :rows="4" animated />
    <el-alert v-if="overview" :title="overview.notice" type="warning" :closable="false" show-icon />
    <div v-if="overview" class="stats">
      <el-card v-for="item in statCards" :key="item.label" shadow="never"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></el-card>
    </div>
    <el-card v-if="notificationsAllowed" class="task-center notification-center" shadow="never">
      <template #header>
        <div class="table-toolbar">
          <span>我的未读提醒（{{ notificationCount === null ? '待刷新' : notificationCount }}）</span>
          <el-button :loading="notificationLoading" @click="refreshNotifications">刷新提醒</el-button>
        </div>
      </template>
      <el-alert title="缺少登记仅表示待人工核对。标记已读只影响您自己的提醒，不会确认或解除告警；来源出现新证据会再次提醒。" type="info" :closable="false" />
      <el-alert :title="scanStatusText" :type="scanStatus?.enabled === false || scanStatus?.state === 'RETRY_PENDING' ? 'warning' : 'info'" :closable="false" />
      <el-alert v-if="notificationError" :title="notificationError" type="warning" :closable="false" />
      <el-table class="desktop-table" v-loading="loading" :data="notifications" stripe empty-text="当前没有未读提醒">
        <el-table-column prop="elderName" label="老人" width="100" />
        <el-table-column label="类别" width="130"><template #default="scope">{{ alertKind(scope.row.kind) }}</template></el-table-column>
        <el-table-column prop="detail" label="待核对证据" min-width="260" show-overflow-tooltip />
        <el-table-column label="告警状态" width="100"><template #default="scope">{{ alertStatus(scope.row.state) }}</template></el-table-column>
        <el-table-column label="个人提醒" width="110"><template #default="scope">
          <el-button link type="primary" :disabled="notificationReading" @click="readNotification(scope.row)">标记已读</el-button>
        </template></el-table-column>
      </el-table>
      <div class="mobile-records" :aria-busy="notificationLoading">
        <article v-for="item in notifications" :key="item.id" class="mobile-record">
          <h3>{{ item.elderName }} · {{ alertKind(item.kind) }}</h3>
          <p>{{ item.detail }}</p>
          <div class="record-meta"><el-tag>{{ alertStatus(item.state) }}</el-tag></div>
          <div class="record-actions"><el-button type="primary" plain :disabled="notificationReading" @click="readNotification(item)">标记已读</el-button></div>
        </article>
        <p v-if="!notificationLoading && !notificationError && !notifications?.length" class="mobile-empty">当前没有未读提醒</p>
      </div>
      <el-button v-if="notificationHasMore" :loading="notificationLoading" @click="loadMoreNotifications">加载更多提醒</el-button>
    </el-card>
    <el-card class="task-center" shadow="never">
      <template #header>
        <div class="table-toolbar">
          <span>健康与用药告警（最多200条）</span>
          <div class="actions">
            <el-select v-model="alertState" style="width:130px" @change="load">
              <el-option label="未解除" value="ACTIVE" /><el-option label="待确认" value="OPEN" />
              <el-option label="已确认" value="ACKNOWLEDGED" /><el-option label="已解除" value="RESOLVED" />
              <el-option label="全部" value="ALL" />
            </el-select>
            <el-button :loading="alertBusy" :disabled="selectedDate >= currentDay" @click="scanAlerts">核对所选历史用药</el-button>
          </div>
        </div>
      </template>
      <el-alert title="测量变化和已登记未执行会自动保存告警并关联任务。历史缺少执行登记只表示需核对，不能据此认定漏服。解除告警不会修改用药或测量记录。" type="warning" :closable="false" />
      <el-alert v-if="alertError" class="request-error" :title="alertError" type="error" :closable="false" show-icon><el-button :loading="loading" @click="load">重新加载</el-button></el-alert>
      <el-table class="desktop-table" v-loading="loading" :data="alerts" stripe empty-text="暂无告警；历史用药核对需选择过去日期">
        <el-table-column prop="elderName" label="老人" width="100" />
        <el-table-column label="类别" width="130"><template #default="scope">{{ alertKind(scope.row.kind) }}</template></el-table-column>
        <el-table-column prop="detail" label="来源证据" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="100"><template #default="scope">{{ alertStatus(scope.row.state) }}</template></el-table-column>
        <el-table-column prop="createTime" label="首次发现" width="165" />
        <el-table-column prop="resolutionNote" label="解除依据" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="180"><template #default="scope">
          <el-button link type="primary" @click="go(scope.row.kind === 'HEALTH_CHANGE' ? '/ai-care/health' : '/ai-care/medication')">原记录</el-button>
          <el-button v-if="scope.row.state === 'OPEN'" link type="primary" :disabled="alertBusy || alertPromptBusy || loading" @click="ackAlert(scope.row)">确认</el-button>
          <el-button v-if="scope.row.state === 'ACKNOWLEDGED'" link type="success" :disabled="alertBusy || alertPromptBusy || loading" @click="resolveAlert(scope.row)">解除</el-button>
        </template></el-table-column>
      </el-table>
      <div class="mobile-records" :aria-busy="loading">
        <article v-for="item in alerts" :key="item.id" class="mobile-record">
          <h3>{{ item.elderName }} · {{ alertKind(item.kind) }}</h3>
          <div class="record-meta"><el-tag>{{ alertStatus(item.state) }}</el-tag></div>
          <p>{{ item.detail }}</p>
          <dl class="record-details"><div><dt>首次发现</dt><dd>{{ item.createTime }}</dd></div><div v-if="item.resolutionNote"><dt>解除依据</dt><dd>{{ item.resolutionNote }}</dd></div></dl>
          <div class="record-actions">
            <el-button @click="go(item.kind === 'HEALTH_CHANGE' ? '/ai-care/health' : '/ai-care/medication')">原记录</el-button>
            <el-button v-if="item.state === 'OPEN'" type="primary" plain :disabled="alertBusy || alertPromptBusy || loading" @click="ackAlert(item)">确认</el-button>
            <el-button v-if="item.state === 'ACKNOWLEDGED'" type="success" plain :disabled="alertBusy || alertPromptBusy || loading" @click="resolveAlert(item)">解除</el-button>
          </div>
        </article>
        <p v-if="!loading && !loadError && !alerts.length" class="mobile-empty">暂无告警；历史用药核对需选择过去日期</p>
      </div>
    </el-card>
    <el-card class="task-center" shadow="never">
      <template #header>
        <div class="table-toolbar">
          <span>协作任务（截至所选日期，最多200条）</span>
          <div class="actions">
            <el-select v-model="taskState" style="width:130px" @change="load">
              <el-option label="未完成" value="ACTIVE" />
              <el-option label="待认领" value="OPEN" />
              <el-option label="已认领" value="CLAIMED" />
              <el-option label="已完成" value="DONE" />
              <el-option label="全部" value="ALL" />
            </el-select>
            <el-button type="primary" :loading="taskBusy" @click="syncTasks">{{ syncing ? '正在全量同步，请稍候' : '全量同步所选日期事项' }}</el-button>
          </div>
        </div>
      </template>
      <el-alert title="完成任务只表示已复核协作事项。用药执行、护理记录完成和服务扣费仍需前往原页面处理。协作截止时间按来源时间加24小时计算，不是医嘱服药时间。" type="info" :closable="false" />
      <el-alert v-if="taskError" class="request-error" :title="taskError" type="error" :closable="false" show-icon><el-button :loading="loading" @click="load">重新加载</el-button></el-alert>
      <el-table class="desktop-table" v-loading="loading" :data="tasks" stripe empty-text="暂无任务，可先同步当日事项">
        <el-table-column prop="elderName" label="老人" width="95" />
        <el-table-column prop="title" label="事项" min-width="150" />
        <el-table-column prop="detail" label="说明" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="100"><template #default="scope">{{ taskStatus(scope.row.state) }}<el-tag v-if="scope.row.overdue" size="small" type="danger">逾期</el-tag></template></el-table-column>
        <el-table-column label="负责人" width="100"><template #default="scope">{{ scope.row.ownerName || "未认领" }}</template></el-table-column>
        <el-table-column prop="dueAt" label="协作截止" width="165" />
        <el-table-column prop="completionNote" label="复核结果" min-width="130" show-overflow-tooltip />
        <el-table-column label="操作" width="225">
          <template #default="scope">
            <el-button link type="primary" @click="go(scope.row.targetPath)">原业务</el-button>
            <el-button v-if="scope.row.state === 'OPEN'" link type="primary" :disabled="taskBusy || taskPromptBusy || loading" @click="claimTask(scope.row)">认领</el-button>
            <el-button v-if="scope.row.state === 'CLAIMED'" link type="primary" :disabled="taskBusy || taskPromptBusy || loading" @click="openTransfer(scope.row)">转交</el-button>
            <el-button v-if="scope.row.state === 'CLAIMED'" link type="success" :disabled="taskBusy || taskPromptBusy || loading" @click="completeTask(scope.row)">复核完成</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="mobile-records" :aria-busy="loading">
        <article v-for="item in tasks" :key="item.id" class="mobile-record">
          <h3>{{ item.elderName }} · {{ item.title }}</h3>
          <div class="record-meta"><el-tag>{{ taskStatus(item.state) }}</el-tag><el-tag v-if="item.overdue" type="danger">逾期</el-tag><span>{{ item.ownerName || '未认领' }}</span></div>
          <p>{{ item.detail }}</p>
          <dl class="record-details"><div><dt>协作截止</dt><dd>{{ item.dueAt }}</dd></div><div v-if="item.completionNote"><dt>复核结果</dt><dd>{{ item.completionNote }}</dd></div></dl>
          <div class="record-actions">
            <el-button @click="go(item.targetPath)">原业务</el-button>
            <el-button v-if="item.state === 'OPEN'" type="primary" plain :disabled="taskBusy || taskPromptBusy || loading" @click="claimTask(item)">认领</el-button>
            <el-button v-if="item.state === 'CLAIMED'" type="primary" plain :disabled="taskBusy || taskPromptBusy || loading" @click="openTransfer(item)">转交</el-button>
            <el-button v-if="item.state === 'CLAIMED'" type="success" plain :disabled="taskBusy || taskPromptBusy || loading" @click="completeTask(item)">复核完成</el-button>
          </div>
        </article>
        <p v-if="!loading && !loadError && !tasks.length" class="mobile-empty">暂无任务，可先同步当日事项</p>
      </div>
    </el-card>
    <el-card v-if="overview" shadow="never">
      <div class="table-toolbar">
        <el-radio-group v-model="category">
          <el-radio-button label="ALL">全部</el-radio-button>
          <el-radio-button label="CARE_FOLLOW_UP">护理</el-radio-button>
          <el-radio-button label="MEDICATION">用药</el-radio-button>
          <el-radio-button label="HEALTH_CHANGE">健康</el-radio-button>
          <el-radio-button label="SERVICE_BACKLOG">预约</el-radio-button>
        </el-radio-group>
        <el-tag v-if="overview.truncated" type="warning">仅展示部分事项；全量同步仍会处理所有可访问来源</el-tag>
      </div>
      <el-table class="desktop-table" v-loading="loading" :data="filteredItems" stripe empty-text="当前没有需要关注的事项">
        <el-table-column prop="elderName" label="老人" width="100" />
        <el-table-column label="类别" width="105"><template #default="scope">{{ categoryText(scope.row.category) }}</template></el-table-column>
        <el-table-column prop="title" label="事项" min-width="150" />
        <el-table-column prop="detail" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="levelType(scope.row.level)">{{ statusText(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="scope"><el-tooltip :content="scope.row.confirmationHint"><el-button type="primary" link @click="go(scope.row.targetPath)">前往处理</el-button></el-tooltip></template></el-table-column>
      </el-table>
      <div class="mobile-records" :aria-busy="loading">
        <article v-for="(item, index) in filteredItems" :key="index" class="mobile-record">
          <h3>{{ item.elderName }} · {{ item.title }}</h3>
          <div class="record-meta"><span>{{ categoryText(item.category) }}</span><el-tag :type="levelType(item.level)">{{ statusText(item.status) }}</el-tag></div>
          <p>{{ item.detail }}</p>
          <p v-if="item.confirmationHint">{{ item.confirmationHint }}</p>
          <div class="record-actions"><el-button type="primary" plain @click="go(item.targetPath)">前往处理</el-button></div>
        </article>
        <p v-if="!loading && !loadError && !filteredItems.length" class="mobile-empty">当前没有需要关注的事项</p>
      </div>
    </el-card>
  </div>
  <el-dialog class="care-mobile-dialog" v-model="transferVisible" title="转交协作任务" width="420px" :close-on-click-modal="!taskBusy" :close-on-press-escape="!taskBusy" :show-close="!taskBusy">
    <p>只能转交给有该老人访问权限且可使用每日助手的在职员工。</p>
    <el-select v-model="targetStaffId" placeholder="选择接收人" filterable>
      <el-option v-for="owner in eligibleOwners" :key="owner.id" :label="owner.name" :value="owner.id" />
    </el-select>
    <el-alert v-if="taskError" class="request-error" :title="taskError" type="error" :closable="false" show-icon />
    <el-empty v-if="!eligibleOwners.length" description="暂无符合权限的接收人" />
    <template #footer><el-button :disabled="taskBusy" @click="transferVisible = false">取消</el-button><el-button type="primary" :loading="taskBusy" @click="transferTask">确认转交</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useCareNotifications } from "@/utils/careNotifications";
import { ElMessage, ElMessageBox } from "element-plus";
import { isAxiosError } from "axios";
import { handleRequestFailure } from "@/utils/requestFailure";
import { useCarePageSession } from "@/views/ai/care/useCarePageSession";
import type { ResourceSession } from "@/utils/authResource";
import { getDailyOverview, listDailyTasks, syncDailyTasks, dailyTaskOwners, actDailyTask, listCareAlerts, scanCareAlerts, actCareAlert } from "@/apis/aiDaily";

const { allowed: notificationsAllowed, unreadCount: notificationCount, items: notifications,
  loading: notificationLoading, reading: notificationReading, error: notificationError,
  hasMore: notificationHasMore, refresh: refreshNotifications, loadMore: loadMoreNotifications,
  scanStatus, scanStatusText, markRead: readNotification } = useCareNotifications(true);

const pad = (value: number) => String(value).padStart(2, "0");
const now = new Date();
const selectedDate = ref(`${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`);
const overview = ref<any>(null);
const loading = ref(false);
const category = ref("ALL");
const router = useRouter();
const tasks = ref<any[]>([]);
const taskState = ref("ACTIVE");
const taskBusy = ref(false);
const syncing = ref(false);
const transferVisible = ref(false);
const transferRow = ref<any>(null);
const targetStaffId = ref<number>();
const eligibleOwners = ref<any[]>([]);
const currentDay = selectedDate.value;
const alerts = ref<any[]>([]);
const alertState = ref("ACTIVE");
const alertBusy = ref(false);
const loadError = ref("");
const taskError = ref("");
const alertError = ref("");
const taskPromptBusy = ref(false);
const alertPromptBusy = ref(false);
const taskNotes = ref<Record<number, string>>({});
const alertNotes = ref<Record<number, string>>({});
let loadRequest = 0;
const pageSession = useCarePageSession(() => {
  overview.value = null; tasks.value = []; alerts.value = []; eligibleOwners.value = [];
  transferVisible.value = false; transferRow.value = null; targetStaffId.value = undefined;
  taskNotes.value = {}; alertNotes.value = {}; loadError.value = taskError.value = alertError.value = "";
});
function alertKind(kind: string) { return kind === "HEALTH_CHANGE" ? "健康变化" : kind === "MEDICATION_SKIPPED" ? "已登记未执行" : "缺少执行登记"; }
function alertStatus(state: string) { return state === "RESOLVED" ? "已解除" : state === "ACKNOWLEDGED" ? "已确认" : "待确认"; }
async function scanAlerts() {
  if (alertBusy.value) return;
  const session = pageSession.capture();
  alertBusy.value = true; alertError.value = "";
  try {
    const res: any = await scanCareAlerts(selectedDate.value, session);
    if (!pageSession.current(session)) return;
    if (res.code !== 200) throw res;
    ElMessage.success(res.msg); await load();
  } catch (error) {
    if (!pageSession.current(session)) return;
    handleRequestFailure(error);
    alertError.value = "历史用药核对未完成，请刷新核对后重试。";
  } finally { alertBusy.value = false; }
}
async function alertAction(action: "ack" | "resolve", row: any, note?: string, session: ResourceSession = pageSession.capture()) {
  if (alertBusy.value || !pageSession.current(session)) return;
  alertBusy.value = true; alertError.value = "";
  try {
    const res: any = await actCareAlert(action, { id: row.id, revision: row.revision, note }, session);
    if (!pageSession.current(session)) return;
    if (res.code !== 200) throw res;
    delete alertNotes.value[row.id];
    ElMessage.success("告警已更新");
    await load();
    await refreshNotifications();
  } catch (error) {
    if (!pageSession.current(session)) return;
    handleRequestFailure(error);
    alertError.value = "告警状态未确认，请刷新核对后重试；已填写的依据会保留。";
  } finally { alertBusy.value = false; }
}
async function ackAlert(row: any) { await alertAction("ack", row); }
async function resolveAlert(row: any) {
  if (alertBusy.value || alertPromptBusy.value) return;
  const session = pageSession.capture();
  let note: string;
  alertPromptBusy.value = true;
  try {
    const result = await ElMessageBox.prompt("请填写人工核对结果和解除依据。", "解除告警", {
      inputValue: alertNotes.value[row.id] || "",
      inputValidator: (value: string) => (!!value?.trim() && value.length <= 500) || "请填写1至500字的解除依据"
    });
    note = result.value;
  } catch { return; } finally { alertPromptBusy.value = false; }
  if (!pageSession.current(session)) return;
  alertNotes.value[row.id] = note;
  await alertAction("resolve", row, note, session);
}

function taskStatus(state: string) { return state === "DONE" ? "已完成" : state === "CLAIMED" ? "已认领" : "待认领"; }
async function syncTasks() {
  if (taskBusy.value) return;
  const session = pageSession.capture();
  taskBusy.value = true; syncing.value = true; taskError.value = "";
  try {
    const res: any = await syncDailyTasks(selectedDate.value, session);
    if (!pageSession.current(session)) return;
    if (res.code !== 200) throw res;
    ElMessage.success(res.msg);
    await load();
  } catch (error) {
    if (!pageSession.current(session)) return;
    handleRequestFailure(error);
    taskError.value = "未确认同步结果，请刷新核对后重试；相同来源不会重复建任务。";
    ElMessage.warning(taskError.value);
  } finally { taskBusy.value = false; syncing.value = false; }
}
async function taskAction(action: "claim" | "transfer" | "complete", row: any, extra: any = {}, session: ResourceSession = pageSession.capture()) {
  if (taskBusy.value || !pageSession.current(session)) return false;
  taskBusy.value = true; taskError.value = "";
  try {
    const res: any = await actDailyTask(action, { id: row.id, revision: row.revision, ...extra }, session);
    if (!pageSession.current(session)) return false;
    if (res.code !== 200) throw res;
    delete taskNotes.value[row.id];
    ElMessage.success("任务已更新"); await load(); return true;
  } catch (error) {
    if (!pageSession.current(session)) return false;
    handleRequestFailure(error);
    taskError.value = "任务状态未确认，请刷新核对后重试；已填写的复核结果会保留。";
    return false;
  } finally { taskBusy.value = false; }
}
async function claimTask(row: any) { await taskAction("claim", row); }
async function openTransfer(row: any) {
  if (taskBusy.value) return;
  const session = pageSession.capture();
  taskBusy.value = true; taskError.value = "";
  try {
    const res: any = await dailyTaskOwners(row.id, session);
    if (!pageSession.current(session)) return;
    if (res.code !== 200) throw res;
    eligibleOwners.value = res.data || []; transferRow.value = row;
    targetStaffId.value = undefined; transferVisible.value = true;
  } catch (error) {
    if (!pageSession.current(session)) return;
    handleRequestFailure(error);
    taskError.value = "接收人暂未加载，请重试转交。";
  } finally { taskBusy.value = false; }
}
async function transferTask() {
  if (!targetStaffId.value) return ElMessage.warning("请选择接收人");
  if (await taskAction("transfer", transferRow.value, { targetStaffId: targetStaffId.value })) transferVisible.value = false;
}
async function completeTask(row: any) {
  if (taskBusy.value || taskPromptBusy.value) return;
  const session = pageSession.capture();
  let note: string;
  taskPromptBusy.value = true;
  try {
    const answer = await ElMessageBox.prompt("请填写人工复核结果；原业务仍需单独处理。", "复核完成", {
      inputValue: taskNotes.value[row.id] || "",
      inputValidator: (value: string) => (!!value?.trim() && value.length <= 500) || "请填写1至500字的复核结果"
    });
    note = answer.value;
  } catch { return; } finally { taskPromptBusy.value = false; }
  if (!pageSession.current(session)) return;
  taskNotes.value[row.id] = note;
  await taskAction("complete", row, { note }, session);
}

const statCards = computed(() => overview.value ? [
  { label: "今日护理记录", value: overview.value.todayCareRecordCount },
  { label: "护理待跟进", value: overview.value.carePendingCount },
  { label: "用药已执行", value: overview.value.medicationDoneCount },
  { label: "用药待核对", value: overview.value.medicationPendingCount },
  { label: "用药未执行", value: overview.value.medicationSkippedCount },
  { label: "健康变化复核", value: overview.value.healthReviewCount },
  { label: "服务预约积压", value: overview.value.serviceBacklogCount }
] : []);
const filteredItems = computed(() => {
  const items = overview.value?.items || [];
  if (category.value === "ALL") return items;
  if (category.value === "MEDICATION") return items.filter((item: any) => item.category.startsWith("MEDICATION_"));
  return items.filter((item: any) => item.category === category.value);
});

function isExpectedRequestFailure(error: unknown): error is { code?: number; msg?: string } {
  if (isAxiosError(error)) return true;
  if (Object.prototype.toString.call(error) !== '[object Object]') return false;
  const result = error as { code?: number; msg?: string };
  return Number.isInteger(result.code) && result.code! >= 400 && result.code! <= 599 && typeof result.msg === 'string';
}

async function load() {
  const session = pageSession.capture();
  const request = ++loadRequest;
  loading.value = true; loadError.value = "";
  try {
    const [res, taskRes, alertRes]: any[] = await Promise.all([getDailyOverview(selectedDate.value, session), listDailyTasks(selectedDate.value, taskState.value, session), listCareAlerts(alertState.value, session)]);
    if (!pageSession.current(session) || request !== loadRequest) return;
    if (res.code !== 200) throw res; else overview.value = res.data;
    if (taskRes.code !== 200) throw taskRes; else tasks.value = taskRes.data || [];
    if (alertRes.code !== 200) throw alertRes; else alerts.value = alertRes.data || [];
  } catch (error) {
    if (!pageSession.current(session) || request !== loadRequest) return;
    if (!isExpectedRequestFailure(error)) throw error;
    loadError.value = "每日事项暂未加载，请重新加载。";
    if (error.code === 401 || error.code === 403) {
      overview.value = null;
      tasks.value = [];
      alerts.value = [];
    }
    ElMessage.warning(error.msg || '每日事项暂未加载，请稍后刷新重试');
  } finally { if (request === loadRequest) loading.value = false; }
}
function go(path: string) { router.push(path); }
function categoryText(value: string) { return value.startsWith("MEDICATION_") ? "用药" : value === "CARE_FOLLOW_UP" ? "护理" : value === "HEALTH_CHANGE" ? "健康" : "服务预约"; }
function statusText(value: string) { return value === "SKIPPED" ? "未执行" : value === "REVIEW" ? "需复核" : "待处理"; }
function levelType(value: string): "danger" | "warning" | "info" { return value === "HIGH" ? "danger" : value === "REVIEW" ? "warning" : "info"; }

onMounted(load);
onActivated(load);
</script>

<style lang="scss" scoped>
.daily-page { padding: 4px; }
.task-center { margin-bottom: 16px; }
.hero { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; background: linear-gradient(120deg, #eff6ff, #f8fbff); }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.actions { display: flex; gap: 8px; }
.stats { display: grid; grid-template-columns: repeat(7, minmax(120px, 1fr)); gap: 10px; margin: 16px 0; }
.stats small { display: block; color: #6b7280; }
.stats strong { display: block; margin-top: 5px; font-size: 25px; color: #1f2937; }
.table-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 14px; }
@media (max-width: 1200px) { .stats { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 800px) { .hero, .table-toolbar { align-items: flex-start; flex-direction: column; } .stats { grid-template-columns: repeat(2, 1fr); } }
</style>
