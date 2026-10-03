<template>
  <div class="medication-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">MEDICATION CHECK</div>
        <h2>用药执行核对</h2>
        <p>按工作人员录入的医嘱计划核对执行情况。系统不推荐药物、不调整剂量，也不自动扣减库存。</p>
      </div>
      <el-tag type="warning">人工核对</el-tag>
    </el-card>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span>新增执行计划</span></template>
        <el-form label-position="top">
          <el-form-item label="老人">
            <el-input v-model="elderName" readonly placeholder="请选择老人">
              <template #append><el-button @click="openElderDialog">选择</el-button></template>
            </el-input>
          </el-form-item>
          <el-form-item label="药品名称（按医嘱）"><el-input v-model="plan.medicineName" /></el-form-item>
          <el-form-item label="执行说明（按医嘱）"><el-input v-model="plan.doseInstruction" placeholder="例如：每次1片，餐后执行" /></el-form-item>
          <el-form-item label="执行时段">
            <el-select v-model="plan.periods" multiple class="full">
              <el-option v-for="item in periods" :key="item" :label="item" :value="item" />
            </el-select>
          </el-form-item>
          <el-row :gutter="12">
            <el-col :span="12"><el-form-item label="开始日期"><el-date-picker v-model="plan.startDate" type="date" value-format="YYYY-MM-DD" class="full" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="结束日期（可选）"><el-date-picker v-model="plan.endDate" type="date" value-format="YYYY-MM-DD" class="full" /></el-form-item></el-col>
          </el-row>
          <el-button type="primary" :loading="saving" @click="savePlan">保存计划</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <span>{{ elderName || '老人' }}的执行清单</span>
          <el-date-picker v-model="selectedDate" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="loadDay" />
        </template>
        <div v-if="day" class="stats">
          <el-tag>共 {{ day.total }}</el-tag>
          <el-tag type="success">已执行 {{ day.done }}</el-tag>
          <el-tag type="danger">未执行 {{ day.skipped }}</el-tag>
          <el-tag type="warning">待核对 {{ day.pending }}</el-tag>
        </div>
        <el-table :data="day?.tasks || []" stripe empty-text="选择老人或新增有效计划后查看">
          <el-table-column prop="period" label="时段" width="75" />
          <el-table-column prop="medicineName" label="药品名称" min-width="130" />
          <el-table-column prop="doseInstruction" label="执行说明" min-width="180" />
          <el-table-column label="状态" width="90">
            <template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ statusText(scope.row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="note" label="备注" min-width="130" />
          <el-table-column label="操作" width="205">
            <template #default="scope">
              <el-button type="success" size="small" link @click="mark(scope.row, 'DONE')">已执行</el-button>
              <el-button type="danger" size="small" link @click="mark(scope.row, 'SKIPPED')">未执行</el-button>
              <el-button type="info" size="small" link @click="disablePlan(scope.row)">停用计划</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
    <elder-list-dialog ref="elderDialogRef" @get-check-elder-info="selectElder" />
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { ref } from "vue";
import elderListDialog from "@/components/elderListDialog/index.vue";
import { addMedicationPlan, disableMedicationPlan, getMedicationDay, recordMedicationExecution } from "@/apis/aiMedication";
import { pageAccessibleElders } from "@/apis/aiScope";

const pad = (value: number) => String(value).padStart(2, "0");
const today = () => { const date = new Date(); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`; };
const periods = ["早", "中", "晚", "睡前"];
const elderName = ref("");
const elderDialogRef = ref();
const saving = ref(false);
const selectedDate = ref(today());
const day = ref<any>(null);
const plan = ref<any>({ elderId: 0, medicineName: "", doseInstruction: "", periods: [], startDate: today(), endDate: "" });

function openElderDialog() {
  elderDialogRef.value?.elderAcceptParams({ elderApi: pageAccessibleElders });
}

async function selectElder(row: any) {
  plan.value.elderId = row.id;
  elderName.value = row.name;
  await loadDay();
}

async function savePlan() {
  if (!plan.value.elderId) return ElMessage.warning("请先选择老人");
  saving.value = true;
  try {
    const payload = { ...plan.value, endDate: plan.value.endDate || null };
    const res: any = await addMedicationPlan(payload);
    if (res.code !== 200) return ElMessage.warning(res.msg || "保存失败");
    ElMessage.success("用药执行计划已保存");
    plan.value.medicineName = "";
    plan.value.doseInstruction = "";
    plan.value.periods = [];
    await loadDay();
  } finally {
    saving.value = false;
  }
}

async function loadDay() {
  if (!plan.value.elderId) return;
  const res: any = await getMedicationDay(plan.value.elderId, selectedDate.value);
  day.value = res.data;
}

async function mark(task: any, status: "DONE" | "SKIPPED") {
  let note = "";
  if (status === "SKIPPED") {
    try {
      const result: any = await ElMessageBox.prompt("请填写未执行原因", "登记未执行", { inputValidator: (value: string) => !!value?.trim() || "原因不能为空" });
      note = result.value;
    } catch (_) {
      return;
    }
  }
  const res: any = await recordMedicationExecution({ planId: task.planId, executionDate: selectedDate.value, period: task.period, status, note });
  if (res.code !== 200) return ElMessage.warning(res.msg || "登记失败");
  ElMessage.success(status === "DONE" ? "已登记执行" : "已登记未执行原因");
  await loadDay();
}

async function disablePlan(task: any) {
  await ElMessageBox.confirm(`确定停用“${task.medicineName}”计划吗？当天记录保留，后续不再生成任务。`, "停用计划", { type: "warning" });
  await disableMedicationPlan(task.planId);
  ElMessage.success("计划已停用");
  await loadDay();
}

function statusText(status: string) { return status === "DONE" ? "已执行" : status === "SKIPPED" ? "未执行" : "待核对"; }
function statusType(status: string): "success" | "danger" | "warning" { return status === "DONE" ? "success" : status === "SKIPPED" ? "danger" : "warning"; }
</script>

<style lang="scss" scoped>
.medication-page { padding: 4px; }
.hero { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; background: linear-gradient(120deg, #fff7ed, #fffbf5); }
.eyebrow { color: #ea580c; font-size: 12px; letter-spacing: 1.5px; }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.grid { display: grid; grid-template-columns: 420px 1fr; gap: 16px; }
.full { width: 100%; }
.stats { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } .hero { align-items: flex-start; gap: 10px; flex-direction: column; } }
</style>
