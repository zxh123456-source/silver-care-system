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
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { getDailyOverview } from "@/apis/aiDaily";

const pad = (value: number) => String(value).padStart(2, "0");
const now = new Date();
const selectedDate = ref(`${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`);
const overview = ref<any>(null);
const loading = ref(false);
const category = ref("ALL");
const router = useRouter();

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
  try { const res: any = await getDailyOverview(selectedDate.value); overview.value = res.data; }
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
