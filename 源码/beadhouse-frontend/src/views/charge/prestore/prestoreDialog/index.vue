<template>
  <div>
    <el-dialog
      style="width: 70%"
      v-model="dialogVisible"
      :title="drawerProps.title"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      destroy-on-close
    >
      <div>
        <el-alert :title="pendingMessage" :type="pendingIntent ? 'warning' : 'info'" :closable="false" class="mb-4" />
        <el-alert v-if="storageError" :title="storageError" type="error" :closable="false" class="mb-4">
          <el-button link type="primary" @click="restorePendingIntent">重新读取</el-button>
        </el-alert>
        <el-form
          :model="formData"
          class="login-form"
          ref="ruleFormRef"
          :rules="rules"
          label-width="120px"
        >
          <div class="flex justify-around flex-wrap">
            <div class="w-full md:w-1/2">
              <el-form-item label="充值金额（元）:" prop="amount">
                <el-input v-model="formData.amount" clearable :disabled="submitting" placeholder="0.01～99999999.99，最多两位小数" />
              </el-form-item>
            </div>
            <!-- 占位 -->
            <div
              class="md:w-1/2"
              v-for="i in 1"
              :key="i"
              style="height: 0"
            ></div>
          </div>
          <div class="flex flex-row-reverse">
            <div></div>
          </div>
        </el-form>
      </div>
      <template v-if="!drawerProps.isView" #footer>
      <span class="dialog-footer">
        <el-button @click="dialogVisible = false" :disabled="submitting">取消</el-button>
        <el-button
          class="bg-blue"
          type="primary"
          :loading="submitting"
          :disabled="!!storageError"
          @click="handleSubmit"
        >
          提交
        </el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ElMessage, FormInstance, FormRules } from "element-plus";
import store from "@/store";
import {
  clearRechargeIntent,
  loadRechargeIntent,
  matchesPersistedRechargeSession,
  normalizeRechargeAmount,
  prepareRechargeIntent,
  receiptMatchesIntent,
  RechargeIntent,
  RechargeSession
} from "@/utils/rechargeIntent";

const ruleFormRef = ref<FormInstance | null>(null);
const dialogVisible = ref(false);
const submitting = ref(false);
const pendingIntent = ref<RechargeIntent | null>(null);
const storageError = ref("");
const accountId = computed(() => String(store.state.app.userPeofile?.userid ?? ""));
const sessionToken = computed(() => store.state.app.token);
let dialogEpoch = 0;

const pendingMessage = computed(() => pendingIntent.value
  ? `存在一笔 ${pendingIntent.value.amount} 元待确认充值。相同金额再次提交会核对原充值；修改金额将新建一笔充值，请先核对充值记录。`
  : "提交后请等待结果。网络异常或登录失效时，再次提交相同金额可核对原充值，避免重复入账。");

const rules = reactive<FormRules>({
  amount: [
    {
      required: true,
      validator(rule, value, callback) {
        callback(normalizeRechargeAmount(value) ? undefined : new Error("请输入 0.01～99999999.99 元，最多两位小数"));
      },
      trigger: "blur"
    }
  ]
});

interface DialogProps {
  title: string;
  isView: boolean;
  rowData?: any;
  api?: (params: any, session: RechargeSession) => Promise<any>;
  getTableList?: () => Promise<any>;
}

const drawerProps = ref<DialogProps>({
  isView: false,
  title: ""
});

const formData = ref({
  elderId: "",
  amount: ""
});

watch(dialogVisible, visible => {
  if (!visible) {
    dialogEpoch++;
    formData.value = { elderId: "", amount: "" };
    pendingIntent.value = null;
    storageError.value = "";
    submitting.value = false;
  }
}, { flush: "sync" });

watch([accountId, sessionToken], () => {
  dialogEpoch++;
  dialogVisible.value = false;
  submitting.value = false;
}, { flush: "sync" });

function ensureCurrentSession(reportWhenClosed = false): boolean {
  if (matchesPersistedRechargeSession({ staffId: accountId.value, token: sessionToken.value })) return true;
  const wasVisible = dialogVisible.value;
  dialogEpoch++;
  dialogVisible.value = false;
  submitting.value = false;
  if (wasVisible || reportWhenClosed) {
    ElMessage.warning("登录状态已变化，请刷新页面后再操作。待确认充值已保留。");
  }
  return false;
}

function handleSessionStorageChange(event: StorageEvent) {
  if (dialogVisible.value && (event.key === "stateData" || event.key === null)) ensureCurrentSession();
}

window.addEventListener("storage", handleSessionStorageChange);
onBeforeUnmount(() => {
  dialogEpoch++;
  window.removeEventListener("storage", handleSessionStorageChange);
});

const restorePendingIntent = () => {
  if (!ensureCurrentSession()) return;
  storageError.value = "";
  try {
    pendingIntent.value = loadRechargeIntent(accountId.value, formData.value.elderId);
    if (pendingIntent.value) formData.value.amount = pendingIntent.value.amount;
  } catch {
    storageError.value = "无法读取待确认充值信息。请恢复浏览器存储后重试，并先核对充值记录。";
  }
};

// 接收父组件传过来的参数
const acceptParams = (params: DialogProps) => {
  if (!ensureCurrentSession(true)) return;
  dialogEpoch++;
  drawerProps.value = params;
  submitting.value = false;
  pendingIntent.value = null;
  formData.value = { elderId: String(params.rowData.elderId), amount: "" };
  if (!accountId.value || !sessionToken.value) {
    ElMessage.error("请先登录后再充值");
    return;
  }
  dialogVisible.value = true;
  restorePendingIntent();
};

//点击提交
const handleSubmit = async () => {
  if (submitting.value || storageError.value) return;
  if (!ensureCurrentSession()) return;
  const epoch = dialogEpoch;
  const staffId = accountId.value;
  const token = sessionToken.value;
  const session = { staffId, token };
  const elderId = formData.value.elderId;
  const api = drawerProps.value.api;
  const refresh = drawerProps.value.getTableList;
  const isCurrent = () => dialogVisible.value && dialogEpoch === epoch
    && accountId.value === staffId && sessionToken.value === token && formData.value.elderId === elderId
    && ensureCurrentSession();
  submitting.value = true;
  try {
    const valid = await ruleFormRef.value?.validate().catch(() => false);
    if (!valid || !isCurrent()) return;
    let intent: RechargeIntent;
    try {
      // Persist before sending: refresh, logout and an uncertain response must retain the same request.
      intent = prepareRechargeIntent(staffId, elderId, formData.value.amount);
      pendingIntent.value = intent;
      formData.value.amount = intent.amount;
    } catch {
      storageError.value = "未能保存本次充值，因此尚未提交。请恢复浏览器存储后重试。";
      return;
    }
    if (!api) return;
    const res = await api({ ...intent }, session);
    if (!isCurrent()) return;
    if (res.code !== 200) {
      ElMessage.error(res.msg || "充值结果尚未确认，请核对充值记录后重试");
      return;
    }
    if (!receiptMatchesIntent(res.data, staffId, intent)) {
      ElMessage.error("充值回执与本次充值不一致，请先核对充值记录");
      return;
    }
    try {
      clearRechargeIntent(staffId, intent);
    } catch {
      ElMessage.warning("充值已确认，但未能清理待确认信息。再次提交相同金额会查询本次结果，请勿重复新建充值。");
    }
    ElMessage.success(`充值已确认，本笔充值后余额为 ${Number(res.data.balanceAfter).toFixed(2)} 元`);
    dialogVisible.value = false;
    // Reload the current balance; a replay receipt contains the balance at its original posting time.
    if (refresh) void refresh().catch(() => undefined);
  } catch {
    // The shared HTTP handler reports transport/authentication errors. Keep the persisted intent.
  } finally {
    if (isCurrent()) submitting.value = false;
  }
};

defineExpose({
  acceptParams
});
</script>
<style scoped lang="scss">
.dialog-footer button:first-child {
  margin-right: 10px;
}
</style>
