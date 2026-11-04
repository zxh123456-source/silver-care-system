<template>
  <div class="health-page care-mobile-page">
    <el-card class="hero" shadow="never">
      <div>
        <h2>健康趋势观察</h2>
        <p>记录日常测量数据，观察连续变化。页面提示仅用于工作人员复核，不构成医疗诊断。</p>
      </div>
      <el-tag type="warning">人工复核</el-tag>
    </el-card>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span>日常测量录入</span></template>
        <el-form label-position="top" :disabled="saving">
          <el-form-item label="老人">
            <el-input v-model="elderName" readonly placeholder="请选择老人">
              <template #append><el-button @click="openElderDialog">选择</el-button></template>
            </el-input>
          </el-form-item>
          <el-form-item label="测量时间">
            <el-date-picker popper-class="care-mobile-picker" v-model="form.measureTime" class="full" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" />
          </el-form-item>
          <el-row :gutter="12">
            <el-col :xs="24" :sm="12"><el-form-item label="体温（℃）"><el-input-number v-model="form.temperature" :precision="1" :step="0.1" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="心率（次/分）"><el-input-number v-model="form.heartRate" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="收缩压（mmHg）"><el-input-number v-model="form.systolicBloodPressure" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="舒张压（mmHg）"><el-input-number v-model="form.diastolicBloodPressure" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="血氧（%）"><el-input-number v-model="form.bloodOxygenSaturation" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="体重（kg）"><el-input-number v-model="form.weight" :precision="1" :step="0.1" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="空腹血糖（mmol/L）"><el-input-number v-model="form.fastingBloodGlucose" :precision="1" :step="0.1" :controls="false" /></el-form-item></el-col>
            <el-col :xs="24" :sm="12"><el-form-item label="餐后血糖（mmol/L）"><el-input-number v-model="form.postprandialBloodGlucose" :precision="1" :step="0.1" :controls="false" /></el-form-item></el-col>
          </el-row>
          <el-form-item label="备注"><el-input v-model="form.remarks" type="textarea" :rows="2" /></el-form-item>
          <el-alert v-if="saveError" class="request-error" :title="saveError" type="error" :closable="false" show-icon />
          <el-button type="primary" :loading="saving" @click="saveMeasurement">保存测量</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <template #header>
          <span>{{ elderName || '老人' }}的趋势</span>
          <el-select v-model="metric" class="metric-select" @change="renderChart">
            <el-option v-for="item in metrics" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </template>
        <el-alert v-if="trendError" class="request-error" :title="trendError" type="error" :closable="false" show-icon>
          <el-button :loading="trendLoading" @click="loadTrend">重新加载</el-button>
        </el-alert>
        <el-skeleton v-if="trendLoading && !trend" :rows="6" animated />
        <el-empty v-else-if="!trend?.records?.length && !trendError" description="选择老人并录入测量后查看趋势" />
        <template v-if="trend?.records?.length">
          <div ref="chartRef" class="chart"></div>
          <el-alert :title="trend.summary" type="info" :closable="false" show-icon />
          <div v-if="trend.changeReminders.length" class="reminders">
            <div v-for="item in trend.changeReminders" :key="item" class="reminder">数据变化：{{ item }}</div>
          </div>
        </template>
      </el-card>
    </div>
    <elder-list-dialog class="care-mobile-dialog care-elder-picker" ref="elderDialogRef" @get-check-elder-info="selectElder" />
  </div>
</template>

<script setup lang="ts">
import type { ECharts } from "@/utils/charts";
import { ElMessage } from "element-plus";
import { isAxiosError } from "axios";
import { nextTick, onUnmounted, ref } from "vue";
import elderListDialog from "@/components/elderListDialog/index.vue";
import { addHealthMeasurement, getHealthTrend } from "@/apis/aiHealth";
import { pageAccessibleElders } from "@/apis/aiScope";
import { useCarePageSession } from "@/views/ai/care/useCarePageSession";

const pad = (value: number) => String(value).padStart(2, "0");
const formatNow = () => {
  const date = new Date();
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};
const emptyForm = () => ({ elderId: 0, measureTime: formatNow(), temperature: undefined, heartRate: undefined, systolicBloodPressure: undefined, diastolicBloodPressure: undefined, bloodOxygenSaturation: undefined, weight: undefined, fastingBloodGlucose: undefined, postprandialBloodGlucose: undefined, remarks: "" });

const form = ref<any>(emptyForm());
const elderName = ref("");
const elderDialogRef = ref();
const saving = ref(false);
const trend = ref<any>(null);
const chartRef = ref<HTMLElement>();
let chart: ECharts | null = null;
let chartObserver: ResizeObserver | null = null;
let alive = true;
const metric = ref("temperature");
const trendLoading = ref(false);
const trendError = ref("");
const saveError = ref("");
let trendRequest = 0;
function disposeChart() {
  chartObserver?.disconnect(); chartObserver = null; chart?.dispose(); chart = null;
}
const pageSession = useCarePageSession(() => {
  form.value = emptyForm(); elderName.value = ""; trend.value = null;
  trendError.value = saveError.value = ""; disposeChart();
});
const metrics = [
  { value: "temperature", label: "体温（℃）" },
  { value: "heartRate", label: "心率（次/分）" },
  { value: "systolicBloodPressure", label: "收缩压（mmHg）" },
  { value: "diastolicBloodPressure", label: "舒张压（mmHg）" },
  { value: "bloodOxygenSaturation", label: "血氧（%）" },
  { value: "weight", label: "体重（kg）" },
  { value: "fastingBloodGlucose", label: "空腹血糖（mmol/L）" },
  { value: "postprandialBloodGlucose", label: "餐后血糖（mmol/L）" }
];

function openElderDialog() {
  elderDialogRef.value?.elderAcceptParams({ elderApi: pageAccessibleElders, fixedOperation: true });
}

async function selectElder(row: any) {
  trend.value = null; disposeChart();
  form.value.elderId = row.id;
  elderName.value = row.name;
  await loadTrend();
}

function isExpectedRequestFailure(error: unknown): error is { code?: number; msg?: string } {
  if (isAxiosError(error)) return true;
  if (Object.prototype.toString.call(error) !== '[object Object]') return false;
  const result = error as { code?: number; msg?: string };
  return Number.isInteger(result.code) && result.code! >= 400 && result.code! <= 599 && typeof result.msg === 'string';
}

async function saveMeasurement() {
  if (saving.value) return;
  if (!form.value.elderId) return ElMessage.warning("请先选择老人");
  const session = pageSession.capture();
  const elderId = form.value.elderId;
  saving.value = true; saveError.value = "";
  let saved = false;
  try {
    const res: any = await addHealthMeasurement({ ...form.value }, session);
    if (!pageSession.current(session) || elderId !== form.value.elderId) return;
    if (res.code !== 200) throw res;
    saved = true;
    ElMessage.success("健康测量已保存");
    form.value = { ...emptyForm(), elderId };
    await loadTrend();
    if (trendError.value) trendError.value = "测量已保存，趋势暂未刷新，请重新加载。";
  } catch (error) {
    if (!pageSession.current(session)) return;
    if (!isExpectedRequestFailure(error)) throw error;
    saveError.value = saved ? "测量已保存，趋势暂未刷新，请重新加载。"
      : "保存结果未确认，输入已保留，请先重新加载趋势核对后再重试。";
  } finally {
    saving.value = false;
  }
}

async function loadTrend() {
  if (!form.value.elderId) return;
  const session = pageSession.capture();
  const request = ++trendRequest;
  trendLoading.value = true; trendError.value = "";
  try {
    const res: any = await getHealthTrend(form.value.elderId, 30, session);
    if (!alive || !pageSession.current(session) || request !== trendRequest) return;
    if (res.code !== 200) throw res;
    trend.value = res.data;
    if (!trend.value?.records?.length) { disposeChart(); return; }
    await nextTick();
    await renderChart();
  } catch (error) {
    if (!alive || !pageSession.current(session) || request !== trendRequest) return;
    if (!isExpectedRequestFailure(error)) throw error;
    trendError.value = "健康趋势暂未加载，请重新加载。";
  } finally { if (request === trendRequest) trendLoading.value = false; }
}

async function renderChart() {
  if (!alive || !chartRef.value || !trend.value?.records?.length) return;
  const element = chartRef.value;
  if (chart && chart.getDom() !== chartRef.value) {
    chartObserver?.disconnect();
    chart.dispose();
    chart = null;
  }
  if (!chart) {
    let echarts: typeof import("@/utils/charts");
    try {
      echarts = await import("@/utils/charts");
    } catch {
      if (alive) { trendError.value = "趋势图暂未加载，请重新加载。"; ElMessage.warning("趋势图暂未加载，请稍后重试"); }
      return;
    }
    if (!alive || chartRef.value !== element || !trend.value?.records?.length) return;
    if (!chart) chart = echarts.init(element);
  }
  chartObserver?.disconnect();
  chartObserver = new ResizeObserver(() => chart?.resize());
  chartObserver.observe(chartRef.value);
  const config = metrics.find(item => item.value === metric.value)!;
  chart.setOption({
    tooltip: { trigger: "axis" },
    grid: { left: 55, right: 20, top: 30, bottom: 55 },
    xAxis: { type: "category", data: trend.value.records.map((item: any) => item.measureTime), axisLabel: { rotate: 30 } },
    yAxis: { type: "value", name: config.label },
    series: [{ name: config.label, type: "line", smooth: true, connectNulls: true, data: trend.value.records.map((item: any) => item[metric.value]) }]
  }, true);
}

const resize = () => chart?.resize();
window.addEventListener("resize", resize);
onUnmounted(() => { alive = false; window.removeEventListener("resize", resize); chartObserver?.disconnect(); chart?.dispose(); chart = null; });
</script>

<style lang="scss" scoped>
.health-page { padding: 4px; }
.hero { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; background: linear-gradient(120deg, #eff6ff, #f8fbff); }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.grid { display: grid; grid-template-columns: 420px minmax(0, 1fr); gap: 16px; }
.full { width: 100%; }
.health-page :deep(.el-input-number) { width: 100%; }
.metric-select { width: 180px; float: right; }
.chart { height: 360px; width: 100%; }
.reminders { margin-top: 12px; }
.reminder { margin-top: 6px; padding: 9px 12px; border-radius: 6px; background: #fff7ed; color: #9a3412; }
@media (max-width: 1000px) { .grid { grid-template-columns: 1fr; } .hero { align-items: flex-start; gap: 10px; flex-direction: column; } }
</style>
