<template>
  <el-dialog v-model="dialogVisible" title="充值记录" width="85%" destroy-on-close>
    <p class="mb-4">老人：{{ elderName }}。以下为已确认的充值记录。</p>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" class="mb-4">
      <el-button link type="primary" :loading="loading" @click="loadLedger">重试</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="records" empty-text="暂无充值记录">
      <el-table-column prop="id" label="流水号" min-width="120" />
      <el-table-column prop="createdAt" label="充值时间" min-width="175">
        <template #default="scope">{{ formatTime(scope.row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="充值金额（元）" min-width="140">
        <template #default="scope">{{ formatMoney(scope.row.amount) }}</template>
      </el-table-column>
      <el-table-column label="充值前余额（元）" min-width="150">
        <template #default="scope">{{ formatMoney(scope.row.balanceBefore) }}</template>
      </el-table-column>
      <el-table-column label="充值后余额（元）" min-width="150">
        <template #default="scope">{{ formatMoney(scope.row.balanceAfter) }}</template>
      </el-table-column>
      <el-table-column prop="staffId" label="经办人编号" min-width="110" />
    </el-table>
    <el-pagination
      v-model:current-page="pageNum"
      v-model:page-size="pageSize"
      :total="total"
      :page-sizes="[10, 20, 50]"
      :disabled="loading"
      layout="total, sizes, prev, pager, next"
      class="ledger-pagination"
      @current-change="loadLedger"
      @size-change="changePageSize"
    />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import store from "@/store";
import { pageRechargeLedger, RechargeReceipt } from "@/apis/depositRecharge";
import { matchesPersistedRechargeSession } from "@/utils/rechargeIntent";

const dialogVisible = ref(false);
const elderId = ref("");
const elderName = ref("");
const records = ref<RechargeReceipt[]>([]);
const total = ref(0);
const pageNum = ref(1);
const pageSize = ref(10);
const loading = ref(false);
const errorMessage = ref("");
const accountId = computed(() => String(store.state.app.userPeofile?.userid ?? ""));
const sessionToken = computed(() => store.state.app.token);
let requestEpoch = 0;

function invalidate() {
  requestEpoch++;
  records.value = [];
  total.value = 0;
  loading.value = false;
  errorMessage.value = "";
}

watch(dialogVisible, visible => { if (!visible) invalidate(); }, { flush: "sync" });
watch([accountId, sessionToken], () => {
  dialogVisible.value = false;
  invalidate();
}, { flush: "sync" });
function ensureCurrentSession(reportWhenClosed = false): boolean {
  if (matchesPersistedRechargeSession({ staffId: accountId.value, token: sessionToken.value })) return true;
  const wasVisible = dialogVisible.value;
  dialogVisible.value = false;
  invalidate();
  if (wasVisible || reportWhenClosed) ElMessage.warning("登录状态已变化，请刷新页面后再查看充值记录。");
  return false;
}

function handleSessionStorageChange(event: StorageEvent) {
  if (dialogVisible.value && (event.key === "stateData" || event.key === null)) ensureCurrentSession();
}

window.addEventListener("storage", handleSessionStorageChange);
onBeforeUnmount(() => {
  invalidate();
  window.removeEventListener("storage", handleSessionStorageChange);
});

async function loadLedger() {
  if (!dialogVisible.value || !elderId.value || !accountId.value || !sessionToken.value) return;
  if (!ensureCurrentSession()) return;
  const epoch = ++requestEpoch;
  const account = accountId.value;
  const token = sessionToken.value;
  const selectedElder = elderId.value;
  const isCurrent = () => epoch === requestEpoch && dialogVisible.value
    && accountId.value === account && sessionToken.value === token && elderId.value === selectedElder
    && ensureCurrentSession();
  loading.value = true;
  errorMessage.value = "";
  records.value = [];
  try {
    const response: any = await pageRechargeLedger(
      { elderId: selectedElder, pageNum: pageNum.value, pageSize: pageSize.value },
      { staffId: account, token }
    );
    if (!isCurrent()) return;
    if (response.code !== 200 || !Array.isArray(response.data?.list)) {
      throw new Error(response.msg || "充值记录加载失败，请重试");
    }
    records.value = response.data.list;
    total.value = Number(response.data.total) || 0;
  } catch {
    if (isCurrent()) errorMessage.value = "充值记录加载失败，请重试。";
  } finally {
    if (isCurrent()) loading.value = false;
  }
}

function changePageSize() {
  pageNum.value = 1;
  void loadLedger();
}

function acceptParams(row: { elderId: string | number; elderName: string }) {
  if (!ensureCurrentSession(true)) return;
  invalidate();
  elderId.value = String(row.elderId);
  elderName.value = row.elderName;
  pageNum.value = 1;
  pageSize.value = 10;
  dialogVisible.value = true;
  void loadLedger();
}

function formatMoney(value: string | number): string {
  const amount = Number(value);
  return Number.isFinite(amount) ? amount.toFixed(2) : "--";
}

function formatTime(value: string): string {
  return value ? value.replace("T", " ") : "--";
}

defineExpose({ acceptParams });
</script>

<style scoped>
.ledger-pagination {
  margin-top: 20px;
  justify-content: flex-end;
}
</style>
