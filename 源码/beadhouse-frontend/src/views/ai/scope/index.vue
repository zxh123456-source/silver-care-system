<template>
  <div class="scope-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">DATA SCOPE</div>
        <h2>老人数据权限</h2>
        <p>非超级管理员只能访问这里明确分配的老人；校验在服务端执行。</p>
      </div>
    </el-card>
    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="elderName" readonly placeholder="选择老人">
          <template #append><el-button @click="openElderDialog">选择</el-button></template>
        </el-input>
        <el-select v-model="staffId" filterable placeholder="选择员工">
          <el-option v-for="item in staffOptions" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-button type="primary" :loading="saving" @click="handleAssign">分配数据权限</el-button>
      </div>
      <el-table :data="assignments" stripe empty-text="暂无老人数据分配">
        <el-table-column prop="elderName" label="老人" />
        <el-table-column prop="staffName" label="员工" />
        <el-table-column label="操作" width="100">
          <template #default="scope">
            <el-button type="danger" size="small" link @click="handleRevoke(scope.row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    <elder-list-dialog ref="elderDialogRef" @get-check-elder-info="selectElder" />
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { onMounted, ref } from "vue";
import elderListDialog from "@/components/elderListDialog/index.vue";
import { assignElder, listElderAssignments, listScopeStaff, pageAccessibleElders, revokeElderAssignment } from "@/apis/aiScope";

const elderDialogRef = ref();
const elderId = ref(0);
const elderName = ref("");
const staffId = ref<number>();
const staffOptions = ref<any[]>([]);
const assignments = ref<any[]>([]);
const saving = ref(false);

function openElderDialog() { elderDialogRef.value?.elderAcceptParams({ elderApi: pageAccessibleElders }); }
function selectElder(row: any) { elderId.value = row.id; elderName.value = row.name; }

async function loadData() {
  const [staffRes, assignmentRes]: any[] = await Promise.all([listScopeStaff(), listElderAssignments()]);
  staffOptions.value = staffRes.data || [];
  assignments.value = assignmentRes.data || [];
}

async function handleAssign() {
  if (!elderId.value || !staffId.value) return ElMessage.warning("请选择老人和员工");
  saving.value = true;
  try {
    await assignElder({ elderId: elderId.value, staffId: staffId.value });
    ElMessage.success("数据权限已分配");
    await loadData();
  } finally { saving.value = false; }
}

async function handleRevoke(row: any) {
  await ElMessageBox.confirm(`确定撤销 ${row.staffName} 对 ${row.elderName} 的访问权限吗？`, "撤销权限", { type: "warning" });
  await revokeElderAssignment(row.elderId, row.staffId);
  ElMessage.success("数据权限已撤销");
  await loadData();
}

onMounted(loadData);
</script>

<style lang="scss" scoped>
.scope-page { padding: 4px; }
.hero { margin-bottom: 16px; background: linear-gradient(120deg, #fdf2f8, #fff8fc); }
.eyebrow { color: #db2777; font-size: 12px; letter-spacing: 1.5px; }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) minmax(180px, 260px) auto; gap: 10px; margin-bottom: 16px; }
@media (max-width: 800px) { .toolbar { grid-template-columns: 1fr; } }
</style>
