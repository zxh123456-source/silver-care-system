<template>
  <div class="audit-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">AI AUDIT</div>
        <h2>AI 操作审计</h2>
        <p>记录 AI 功能的操作人、动作和对象，不保存完整护理口述、制度问题或健康测量正文。</p>
      </div>
      <el-button type="primary" :loading="loading" @click="loadLogs">刷新</el-button>
    </el-card>
    <div class="metrics-toolbar"><span>AI 质量指标</span><el-select v-model="metricDays" size="small" @change="loadMetrics"><el-option label="近7天" :value="7" /><el-option label="近14天" :value="14" /><el-option label="近30天" :value="30" /></el-select></div>
    <div v-if="metrics" class="metrics-grid">
      <el-card v-for="item in metricCards" :key="item.label" shadow="never"><small>{{ item.label }}</small><strong>{{ item.value }}</strong></el-card>
    </div>
    <el-card v-if="metrics" class="metrics-detail" shadow="never">
      <div><b>按功能调用：</b><el-tag v-for="(value, key) in metrics.byFeature" :key="key" class="metric-tag">{{ key }} {{ value }}</el-tag></div>
      <div><b>检索/执行后端：</b><el-tag v-for="(value, key) in metrics.retrievalBackend" :key="key" class="metric-tag" type="info">{{ key }} {{ value }}</el-tag></div>
    </el-card>
    <el-card shadow="never">
      <el-table :data="logs" stripe empty-text="暂无 AI 操作记录">
        <el-table-column prop="createTime" label="时间" min-width="165" />
        <el-table-column prop="module" label="模块" min-width="110" />
        <el-table-column prop="action" label="动作" min-width="130" />
        <el-table-column prop="operatorName" label="操作人" min-width="100" />
        <el-table-column prop="objectType" label="对象类型" min-width="100" />
        <el-table-column prop="objectId" label="对象编号" min-width="90" />
        <el-table-column prop="detail" label="操作摘要" min-width="280" show-overflow-tooltip />
      </el-table>
      <el-pagination
        class="pagination"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :current-page="pageNum"
        :page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="changePage"
        @size-change="changeSize"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { getAiAuditPage, getAiMetricsSummary } from "@/apis/aiAudit";

const logs = ref<any[]>([]);
const loading = ref(false);
const pageNum = ref(1);
const pageSize = ref(20);
const total = ref(0);
const metricDays = ref(7);
const metrics = ref<any>(null);
const metricCards = computed(() => metrics.value ? [
  { label: "管线调用", value: metrics.value.pipelineCalls },
  { label: "成功率", value: `${metrics.value.successRate}%` },
  { label: "回退率", value: `${metrics.value.fallbackRate}%` },
  { label: "错误率", value: `${metrics.value.errorRate}%` },
  { label: "制度有据率", value: `${metrics.value.policyGroundedRate}%` },
  { label: "模型使用率", value: `${metrics.value.modelUseRate}%` },
  { label: "P95耗时", value: `${metrics.value.p95LatencyMs}ms` },
  { label: "RAG待同步/失败", value: `${metrics.value.ragPending}/${metrics.value.ragFailed}` }
] : []);

async function loadLogs() {
  loading.value = true;
  try {
    const res: any = await getAiAuditPage(pageNum.value, pageSize.value);
    logs.value = res.data?.list || [];
    total.value = res.data?.total || 0;
  } finally {
    loading.value = false;
  }
}

async function loadMetrics() {
  const res: any = await getAiMetricsSummary(metricDays.value);
  metrics.value = res.data;
}

function changePage(value: number) {
  pageNum.value = value;
  loadLogs();
}

function changeSize(value: number) {
  pageSize.value = value;
  pageNum.value = 1;
  loadLogs();
}

onMounted(() => { loadLogs(); loadMetrics(); });
</script>

<style lang="scss" scoped>
.audit-page { padding: 4px; }
.hero { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; background: linear-gradient(120deg, #f5f3ff, #faf8ff); }
.eyebrow { color: #7c3aed; font-size: 12px; letter-spacing: 1.5px; }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.pagination { margin-top: 16px; justify-content: flex-end; }
.metrics-toolbar { display: flex; justify-content: space-between; align-items: center; margin: 14px 0 8px; font-weight: 600; }
.metrics-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 10px; }
.metrics-grid small { display: block; color: #6b7280; }
.metrics-grid strong { display: block; margin-top: 5px; font-size: 22px; color: #1f2937; }
.metrics-detail { margin-bottom: 16px; line-height: 2.3; }
.metric-tag { margin-left: 7px; }
@media (max-width: 900px) { .metrics-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 900px) { .hero { align-items: flex-start; gap: 10px; flex-direction: column; } }
</style>
