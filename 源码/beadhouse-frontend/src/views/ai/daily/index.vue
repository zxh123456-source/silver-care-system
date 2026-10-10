<template>
  <div class="daily-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">DAILY CARE ASSISTANT</div>
        <h2>每日护理助手</h2>
        <p>统一汇总护理、用药、健康变化和服务预约。所有处理仍需进入原页面人工确认。</p>
      </div>
      <div class="actions"><el-date-picker v-model="selectedDate" type="date" value-format="YYYY-MM-DD" :clearable="false" /><el-button type="primary" :loading="loading" @click="load">刷新</el-button></div>
    </el-card>
    <el-alert v-if="overview" :title="overview.notice" type="warning" :closable="false" show-icon />
    <div v-if="overview" class="stats">
      <el-card v-for="item in statCards" :key="item.label" shadow="never"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></el-card>
    </div>
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
      <el-table :data="alerts" stripe empty-text="暂无告警；历史用药核对需选择过去日期">
        <el-table-column prop="elderName" label="老人" width="100" />
        <el-table-column label="类别" width="130"><template #default="scope">{{ alertKind(scope.row.kind) }}</template></el-table-column>
        <el-table-column prop="detail" label="来源证据" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="100"><template #default="scope">{{ alertStatus(scope.row.state) }}</template></el-table-column>
        <el-table-column prop="createTime" label="首次发现" width="165" />
        <el-table-column prop="resolutionNote" label="解除依据" min-width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="180"><template #default="scope">
          <el-button link type="primary" @click="go(scope.row.kind === 'HEALTH_CHANGE' ? '/ai-care/health' : '/ai-care/medication')">原记录</el-button>
          <el-button v-if="scope.row.state === 'OPEN'" link type="primary" :disabled="alertBusy" @click="ackAlert(scope.row)">确认</el-button>
          <el-button v-if="scope.row.state === 'ACKNOWLEDGED'" link type="success" :disabled="alertBusy" @click="resolveAlert(scope.row)">解除</el-button>
        </template></el-table-column>
      </el-table>
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
            <el-button type="primary" :loading="taskBusy" @click="syncTasks">同步当日事项为任务</el-button>
          </div>
        </div>
      </template>
      <el-alert title="完成任务只表示已复核协作事项。用药执行、护理记录完成和服务扣费仍需前往原页面处理。协作截止时间按来源时间加24小时计算，不是医嘱服药时间。" type="info" :closable="false" />
      <el-table :data="tasks" stripe empty-text="暂无任务，可先同步当日事项">
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
            <el-button v-if="scope.row.state === 'OPEN'" link type="primary" :disabled="taskBusy" @click="claimTask(scope.row)">认领</el-button>
            <el-button v-if="scope.row.state === 'CLAIMED'" link type="primary" :disabled="taskBusy" @click="openTransfer(scope.row)">转交</el-button>
            <el-button v-if="scope.row.state === 'CLAIMED'" link type="success" :disabled="taskBusy" @click="completeTask(scope.row)">复核完成</el-button>
          </template>
        </el-table-column>
      </el-table>
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
        <el-tag v-if="overview.truncated" type="warning">任务过多，仅显示前 200 条</el-tag>
      </div>
      <el-table :data="filteredItems" stripe empty-text="当前没有需要关注的事项">
        <el-table-column prop="elderName" label="老人" width="100" />
        <el-table-column label="类别" width="105"><template #default="scope">{{ categoryText(scope.row.category) }}</template></el-table-column>
        <el-table-column prop="title" label="事项" min-width="150" />
        <el-table-column prop="detail" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="状态" width="90"><template #default="scope"><el-tag :type="levelType(scope.row.level)">{{ statusText(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="scope"><el-tooltip :content="scope.row.confirmationHint"><el-button type="primary" link @click="go(scope.row.targetPath)">前往处理</el-button></el-tooltip></template></el-table-column>
      </el-table>
    </el-card>
  </div>
  <el-dialog v-model="transferVisible" title="转交协作任务" width="420px">
    <p>只能转交给有该老人访问权限且可使用每日助手的在职员工。</p>
    <el-select v-model="targetStaffId" placeholder="选择接收人" filterable>
      <el-option v-for="owner in eligibleOwners" :key="owner.id" :label="owner.name" :value="owner.id" />
    </el-select>
    <template #footer><el-button @click="transferVisible = false">取消</el-button><el-button type="primary" :loading="taskBusy" @click="transferTask">确认转交</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import { getDailyOverview, listDailyTasks, syncDailyTasks, dailyTaskOwners, actDailyTask, listCareAlerts, scanCareAlerts, actCareAlert } from "@/apis/aiDaily";

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
const transferVisible = ref(false);
const transferRow = ref<any>(null);
const targetStaffId = ref<number>();
const eligibleOwners = ref<any[]>([]);
const currentDay = selectedDate.value;
const alerts = ref<any[]>([]);
const alertState = ref("ACTIVE");
const alertBusy = ref(false);
function alertKind(kind: string) { return kind === "HEALTH_CHANGE" ? "健康变化" : kind === "MEDICATION_SKIPPED" ? "已登记未执行" : "缺少执行登记"; }
function alertStatus(state: string) { return state === "RESOLVED" ? "已解除" : state === "ACKNOWLEDGED" ? "已确认" : "待确认"; }
async function scanAlerts() {
  alertBusy.value = true;
  try {
    const res: any = await scanCareAlerts(selectedDate.value);
    if (res.code !== 200) return ElMessage.warning(res.msg);
    ElMessage.success(res.msg); await load();
  } finally { alertBusy.value = false; }
}
async function alertAction(action: "ack" | "resolve", row: any, note?: string) {
  alertBusy.value = true;
  try {
    const res: any = await actCareAlert(action, { id: row.id, revision: row.revision, note });
    if (res.code !== 200) ElMessage.warning(res.msg); else ElMessage.success("告警已更新");
    await load();
  } finally { alertBusy.value = false; }
}
async function ackAlert(row: any) { await alertAction("ack", row); }
async function resolveAlert(row: any) {
  let note = "";
  try {
    const result = await ElMessageBox.prompt("请填写人工核对结果和解除依据。", "解除告警", {
      inputValidator: (value: string) => (!!value?.trim() && value.length <= 500) || "请填写1至500字的解除依据"
    });
    note = result.value;
  } catch { return; }
  await alertAction("resolve", row, note);
}

function taskStatus(state: string) { return state === "DONE" ? "已完成" : state === "CLAIMED" ? "已认领" : "待认领"; }
async function syncTasks() {
  taskBusy.value = true;
  try {
    const res: any = await syncDailyTasks(selectedDate.value);
    if (res.code !== 200) return ElMessage.warning(res.msg);
    ElMessage.success(res.msg);
    await load();
  } finally { taskBusy.value = false; }
}
async function taskAction(action: "claim" | "transfer" | "complete", row: any, extra: any = {}) {
  taskBusy.value = true;
  try {
    const res: any = await actDailyTask(action, { id: row.id, revision: row.revision, ...extra });
    if (res.code !== 200) { ElMessage.warning(res.msg); await load(); return false; }
    ElMessage.success("任务已更新"); await load(); return true;
  } finally { taskBusy.value = false; }
}
async function claimTask(row: any) { await taskAction("claim", row); }
async function openTransfer(row: any) {
  const res: any = await dailyTaskOwners(row.id);
  if (res.code !== 200) return ElMessage.warning(res.msg);
  eligibleOwners.value = res.data || []; transferRow.value = row;
  targetStaffId.value = undefined; transferVisible.value = true;
}
async function transferTask() {
  if (!targetStaffId.value) return ElMessage.warning("请选择接收人");
  if (await taskAction("transfer", transferRow.value, { targetStaffId: targetStaffId.value })) transferVisible.value = false;
}
async function completeTask(row: any) {
  let note = "";
  try {
    const answer = await ElMessageBox.prompt("请填写人工复核结果；原业务仍需单独处理。", "复核完成", {
      inputValidator: (value: string) => (!!value?.trim() && value.length <= 500) || "请填写1至500字的复核结果"
    });
    note = answer.value;
  } catch { return; }
  await taskAction("complete", row, { note });
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

async function load() {
  loading.value = true;
  try {
    const [res, taskRes, alertRes]: any[] = await Promise.all([getDailyOverview(selectedDate.value), listDailyTasks(selectedDate.value, taskState.value), listCareAlerts(alertState.value)]);
    if (res.code !== 200) ElMessage.warning(res.msg); else overview.value = res.data;
    if (taskRes.code !== 200) ElMessage.warning(taskRes.msg); else tasks.value = taskRes.data || [];
    if (alertRes.code !== 200) ElMessage.warning(alertRes.msg); else alerts.value = alertRes.data || [];
  }
  finally { loading.value = false; }
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
.eyebrow { color: #2563eb; font-size: 12px; letter-spacing: 1.5px; }
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
