<template>
  <div class="ai-care-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">AI CARE WORKBENCH</div>
        <h2>护理记录与交班</h2>
        <p>把护理员的口述整理成可确认、可跟进的记录。AI 只生成草稿，保存前请人工核对。</p>
      </div>
      <div class="hero-actions">
        <el-date-picker v-model="handoverDate" value-format="YYYY-MM-DD" type="date" placeholder="选择交班日期" :clearable="false" />
        <el-button type="primary" @click="loadHandover">生成交班摘要</el-button>
      </div>
    </el-card>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span>新增护理记录</span></template>
        <el-form label-position="top">
          <el-form-item label="老人编号">
            <el-input v-model="selectedElderName" placeholder="请选择老人" readonly>
              <template #append><el-button @click="openElderDialog">选择</el-button></template>
            </el-input>
          </el-form-item>
          <el-form-item label="护理员口述">
            <el-input
              v-model="form.sourceText"
              type="textarea"
              :rows="7"
              :readonly="isListening"
              placeholder="例如：下午没有参加活动，说昨晚没睡好，已经跟晚班说了，明天再问一下"
            />
            <div class="voice-toolbar">
              <div class="voice-actions">
                <el-button type="primary" plain :disabled="!isSpeechSupported || isListening" @click="startVoiceRecognition">
                  {{ isListening ? "正在录音" : "开始语音录入" }}
                </el-button>
                <el-button :disabled="!isListening || isStopping" @click="stopVoiceRecognition">
                  {{ isStopping ? "正在停止" : "停止录音" }}
                </el-button>
                <el-tag v-if="isListening" type="danger" effect="plain">麦克风使用中</el-tag>
                <el-tag v-else-if="voiceFinalText" type="success" effect="plain">本次转写已完成</el-tag>
              </div>
              <div v-if="voiceInterimText" class="voice-interim">实时转写：{{ voiceInterimText }}</div>
              <div v-else class="voice-help">{{ voiceStatusText }}</div>
              <el-alert v-if="voiceError" class="voice-alert" :title="voiceError" type="error" :closable="false" show-icon />
            </div>
          </el-form-item>
          <el-button type="primary" :loading="draftLoading" :disabled="isListening" @click="makeDraft">生成结构化草稿</el-button>
        </el-form>
      </el-card>

      <el-card shadow="never">
        <template #header><span>AI 草稿（人工确认后保存）</span></template>
        <el-empty v-if="!draft" description="输入护理员口述后生成草稿" />
        <el-form v-else label-position="top">
          <el-alert :title="draft.warning || '请核对草稿内容'" type="warning" :closable="false" show-icon />
          <el-form-item label="观察到的情况"><el-input v-model="draft.observation" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="已采取措施"><el-input v-model="draft.actionTaken" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="待跟进事项"><el-input v-model="draft.followUp" type="textarea" :rows="2" /></el-form-item>
          <el-row :gutter="12">
            <el-col :span="12"><el-form-item label="进餐"><el-input v-model="draft.appetite" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="休息"><el-input v-model="draft.sleep" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="服药"><el-input v-model="draft.medicine" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="活动"><el-input v-model="draft.activity" /></el-form-item></el-col>
          </el-row>
          <el-button type="success" :loading="saveLoading" @click="confirmSave">确认并保存</el-button>
        </el-form>
      </el-card>
    </div>

    <el-card class="family-report" shadow="never">
      <template #header><span>家属周报草稿</span><el-tag size="small" type="info">人工审核后发送</el-tag></template>
      <div class="report-toolbar">
        <el-date-picker v-model="reportStart" value-format="YYYY-MM-DD" type="date" placeholder="开始日期" />
        <span>至</span>
        <el-date-picker v-model="reportEnd" value-format="YYYY-MM-DD" type="date" placeholder="结束日期" />
        <el-button type="primary" :loading="reportLoading" :disabled="!form.elderId" @click="makeFamilyReport">生成周报草稿</el-button>
      </div>
      <el-alert v-if="report?.warning" :title="report.warning" type="warning" :closable="false" show-icon />
      <el-input v-if="report" v-model="report.summary" class="report-text" type="textarea" :rows="6" />
      <el-empty v-else description="选择老人和日期范围后生成家属周报" />
    </el-card>

    <el-card v-if="handover" class="handover" shadow="never">
      <template #header><span>{{ handover.date }} 交班摘要</span><el-tag type="info">{{ handover.count }} 条记录 / 已完成 {{ handover.completedCount }} 条</el-tag></template>
      <p class="summary">{{ handover.summary }} <el-tag v-if="handover.modelUsed" size="small" type="success">模型摘要</el-tag></p>
      <el-divider />
      <div v-if="handover.notes.length" class="follow-ups">
        <div v-for="item in handover.notes" :key="item.id" class="follow-up" :class="{ done: item.status === 'DONE' }">
          <span class="note-preview" @click="viewNote(item)"><b>{{ item.elderName || `老人${item.elderId}` }}</b>：{{ item.followUp || '本条记录未填写待跟进事项' }}<small v-if="item.staffName">（记录人：{{ item.staffName }}）</small></span>
          <el-button v-if="item.followUp && item.status !== 'DONE'" size="small" type="success" link @click="completeNote(item.id)">标记完成</el-button>
          <el-tag v-else-if="item.followUp" size="small" type="success">已完成</el-tag>
        </div>
      </div>
      <el-empty v-else description="暂无待跟进事项" />
    </el-card>
  </div>
  <elder-list-dialog ref="elderDialogRef" @get-check-elder-info="getCheckElderInfo" />
  <el-dialog v-model="detailVisible" title="护理记录详情" width="560px">
    <el-descriptions v-if="selectedNote && !detailEditing" :column="1" border>
      <el-descriptions-item label="老人">{{ selectedNote.elderName || `老人${selectedNote.elderId}` }}</el-descriptions-item>
      <el-descriptions-item label="记录人">{{ selectedNote.staffName || '未记录' }}</el-descriptions-item>
      <el-descriptions-item label="原始口述">{{ selectedNote.sourceText }}</el-descriptions-item>
      <el-descriptions-item label="观察情况">{{ selectedNote.observation || '未填写' }}</el-descriptions-item>
      <el-descriptions-item label="已采取措施">{{ selectedNote.actionTaken || '未填写' }}</el-descriptions-item>
      <el-descriptions-item label="待跟进事项">{{ selectedNote.followUp || '未填写' }}</el-descriptions-item>
    </el-descriptions>
    <el-form v-else-if="selectedNote" :model="editForm" label-position="top">
      <el-form-item label="原始口述"><el-input v-model="editForm.sourceText" type="textarea" :rows="3" /></el-form-item>
      <el-form-item label="观察情况"><el-input v-model="editForm.observation" type="textarea" :rows="2" /></el-form-item>
      <el-form-item label="已采取措施"><el-input v-model="editForm.actionTaken" type="textarea" :rows="2" /></el-form-item>
      <el-form-item label="待跟进事项"><el-input v-model="editForm.followUp" type="textarea" :rows="2" /></el-form-item>
      <el-row :gutter="12">
        <el-col :span="12"><el-form-item label="进餐"><el-input v-model="editForm.appetite" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="休息"><el-input v-model="editForm.sleep" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="服药"><el-input v-model="editForm.medicine" /></el-form-item></el-col>
        <el-col :span="12"><el-form-item label="活动"><el-input v-model="editForm.activity" /></el-form-item></el-col>
      </el-row>
    </el-form>
    <template #footer v-if="selectedNote">
      <el-button @click="detailVisible = false">关闭</el-button>
      <el-button v-if="!detailEditing" type="primary" @click="detailEditing = true">编辑记录</el-button>
      <el-button v-else type="success" :loading="editLoading" @click="saveEditedNote">保存修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { AiCareDraft, completeCareNote, createCareDraft, createFamilyReport, getHandover, saveCareNote, updateCareNote } from "@/apis/aiCare";
import { pageAccessibleElders } from "@/apis/aiScope";
import elderListDialog from "@/components/elderListDialog/index.vue";

interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

interface SpeechRecognitionResultLike {
  isFinal: boolean;
  [index: number]: SpeechRecognitionAlternativeLike;
}

interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: {
    length: number;
    [index: number]: SpeechRecognitionResultLike;
  };
}

interface SpeechRecognitionErrorEventLike {
  error: string;
}

interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  maxAlternatives: number;
  onstart: (() => void) | null;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  onend: (() => void) | null;
  start: () => void;
  stop: () => void;
  abort: () => void;
}

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

function getSpeechRecognitionConstructor(): SpeechRecognitionConstructor | undefined {
  if (typeof window === "undefined") return undefined;
  const speechWindow = window as typeof window & {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  };
  return speechWindow.SpeechRecognition || speechWindow.webkitSpeechRecognition;
}

const form = ref({ elderId: 0, sourceText: "" });
const selectedElderName = ref("");
const elderDialogRef = ref();
const draft = ref<AiCareDraft | null>(null);
const handover = ref<any>(null);
const selectedNote = ref<any>(null);
const detailVisible = ref(false);
const detailEditing = ref(false);
const editLoading = ref(false);
const editForm = ref<any>({});
const now = new Date();
const handoverDate = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`);
const reportEnd = ref(handoverDate.value);
const reportStartDate = new Date(now);
reportStartDate.setDate(reportStartDate.getDate() - 6);
const reportStart = ref(`${reportStartDate.getFullYear()}-${String(reportStartDate.getMonth() + 1).padStart(2, "0")}-${String(reportStartDate.getDate()).padStart(2, "0")}`);
const draftLoading = ref(false);
const saveLoading = ref(false);
const reportLoading = ref(false);
const report = ref<any>(null);
const isSpeechSupported = ref(Boolean(getSpeechRecognitionConstructor()));
const isListening = ref(false);
const isStopping = ref(false);
const voiceInterimText = ref("");
const voiceFinalText = ref("");
const voiceError = ref("");
let speechRecognition: SpeechRecognitionLike | null = null;
let voiceSessionPrefix = "";
let stopRequested = false;

const voiceStatusText = computed(() => {
  if (!isSpeechSupported.value) return "当前浏览器不支持语音识别，请使用最新版 Chrome 或 Edge，或直接键入口述内容。";
  if (isStopping.value) return "正在完成最后一段转写，请稍候。";
  if (isListening.value) return "请开始口述，识别结果会实时填入上方文本框。";
  if (voiceFinalText.value) return "语音结果已填入文本框，请核对后再生成草稿；系统不会自动保存。";
  return "语音结果只会填入文本框，仍需生成草稿并人工确认保存。";
});

function combineVoiceText(prefix: string, transcript: string) {
  const normalizedPrefix = prefix.trimEnd();
  const normalizedTranscript = transcript.trim();
  if (!normalizedPrefix) return normalizedTranscript;
  if (!normalizedTranscript) return normalizedPrefix;
  const separator = /[\s，。！？；：,.!?;:]$/.test(normalizedPrefix) ? "" : "\n";
  return `${normalizedPrefix}${separator}${normalizedTranscript}`;
}

function syncVoiceText() {
  form.value.sourceText = combineVoiceText(voiceSessionPrefix, `${voiceFinalText.value}${voiceInterimText.value}`);
}

function describeSpeechError(error: string) {
  const descriptions: Record<string, string> = {
    "not-allowed": "未获得麦克风权限，请允许浏览器访问麦克风后重试。",
    "service-not-allowed": "浏览器已禁止语音识别服务，请检查站点权限或浏览器设置。",
    "audio-capture": "没有检测到可用麦克风，请检查设备连接。",
    "no-speech": "没有识别到语音，请靠近麦克风后重试。",
    network: "语音识别服务连接失败，请检查网络后重试。",
    "language-not-supported": "当前浏览器不支持中文语音识别。",
    aborted: "语音识别已中止，请重新开始。"
  };
  return descriptions[error] || "语音识别失败，请重试或直接键入口述内容。";
}

function startVoiceRecognition() {
  const Recognition = getSpeechRecognitionConstructor();
  if (!Recognition) {
    isSpeechSupported.value = false;
    voiceError.value = "当前浏览器不支持语音识别，请使用最新版 Chrome 或 Edge。";
    return;
  }

  voiceError.value = "";
  voiceFinalText.value = "";
  voiceInterimText.value = "";
  voiceSessionPrefix = form.value.sourceText;
  stopRequested = false;
  isStopping.value = false;

  const recognition = new Recognition();
  speechRecognition = recognition;
  recognition.lang = "zh-CN";
  recognition.continuous = true;
  recognition.interimResults = true;
  recognition.maxAlternatives = 1;
  recognition.onstart = () => {
    if (speechRecognition !== recognition) return;
    isListening.value = true;
  };
  recognition.onresult = (event) => {
    if (speechRecognition !== recognition) return;
    let interim = "";
    for (let index = event.resultIndex; index < event.results.length; index += 1) {
      const result = event.results[index];
      const transcript = result[0]?.transcript || "";
      if (result.isFinal) voiceFinalText.value += transcript;
      else interim += transcript;
    }
    voiceInterimText.value = interim;
    syncVoiceText();
  };
  recognition.onerror = (event) => {
    if (speechRecognition !== recognition) return;
    if (!(event.error === "aborted" && stopRequested)) voiceError.value = describeSpeechError(event.error);
    isListening.value = false;
    isStopping.value = false;
  };
  recognition.onend = () => {
    if (speechRecognition !== recognition) return;
    if (voiceInterimText.value) {
      voiceFinalText.value += voiceInterimText.value;
      voiceInterimText.value = "";
      syncVoiceText();
    }
    isListening.value = false;
    isStopping.value = false;
    stopRequested = false;
    if (speechRecognition === recognition) speechRecognition = null;
  };

  try {
    isListening.value = true;
    recognition.start();
  } catch (error) {
    isListening.value = false;
    speechRecognition = null;
    voiceError.value = error instanceof Error && error.name === "InvalidStateError"
      ? "语音识别正在运行，请先停止后再重试。"
      : "无法启动语音识别，请检查麦克风权限后重试。";
  }
}

function stopVoiceRecognition() {
  if (!speechRecognition || !isListening.value) return;
  stopRequested = true;
  isStopping.value = true;
  try {
    speechRecognition.stop();
  } catch {
    isListening.value = false;
    isStopping.value = false;
    stopRequested = false;
    voiceError.value = "停止语音识别失败，请刷新页面后重试。";
  }
}

async function makeDraft() {
  if (!form.value.elderId) return ElMessage.warning("请选择老人");
  if (!form.value.sourceText.trim()) return ElMessage.warning("请先填写护理员口述");
  draftLoading.value = true;
  try {
    const res: any = await createCareDraft(form.value);
    draft.value = res.data;
  } finally {
    draftLoading.value = false;
  }
}

function openElderDialog() {
  elderDialogRef.value?.elderAcceptParams({ elderApi: pageAccessibleElders });
}

function getCheckElderInfo(row: any) {
  form.value.elderId = row.id;
  selectedElderName.value = row.name;
}

function viewNote(note: any) {
  selectedNote.value = note;
  editForm.value = {
    id: note.id,
    elderId: note.elderId,
    sourceText: note.sourceText || "",
    observation: note.observation || "",
    actionTaken: note.actionTaken || "",
    followUp: note.followUp || "",
    appetite: note.appetite || "",
    sleep: note.sleep || "",
    medicine: note.medicine || "",
    activity: note.activity || ""
  };
  detailEditing.value = false;
  detailVisible.value = true;
}

async function saveEditedNote() {
  if (!editForm.value.sourceText.trim()) return ElMessage.warning("原始口述不能为空");
  editLoading.value = true;
  try {
    await updateCareNote(editForm.value);
    ElMessage.success("护理记录已更新");
    detailEditing.value = false;
    detailVisible.value = false;
    await loadHandover();
  } finally {
    editLoading.value = false;
  }
}

async function makeFamilyReport() {
  if (!form.value.elderId) return ElMessage.warning("请先选择老人");
  if (reportStart.value > reportEnd.value) return ElMessage.warning("开始日期不能晚于结束日期");
  reportLoading.value = true;
  try {
    const res: any = await createFamilyReport({ elderId: form.value.elderId, startDate: reportStart.value, endDate: reportEnd.value });
    report.value = res.data;
  } finally {
    reportLoading.value = false;
  }
}

async function confirmSave() {
  if (!draft.value) return;
  saveLoading.value = true;
  try {
    await saveCareNote({
      elderId: draft.value.elderId,
      sourceText: draft.value.sourceText,
      observation: draft.value.observation,
      actionTaken: draft.value.actionTaken,
      followUp: draft.value.followUp,
      appetite: draft.value.appetite,
      sleep: draft.value.sleep,
      medicine: draft.value.medicine,
      activity: draft.value.activity
    });
    ElMessage.success("护理记录已保存");
    draft.value = null;
    form.value.sourceText = "";
    await loadHandover();
  } finally {
    saveLoading.value = false;
  }
}

async function loadHandover() {
  const res: any = await getHandover(handoverDate.value);
  handover.value = res.data;
}

async function completeNote(id: number) {
  await completeCareNote(id);
  ElMessage.success("待跟进事项已完成");
  await loadHandover();
}

onMounted(loadHandover);

onBeforeUnmount(() => {
  if (!speechRecognition) return;
  speechRecognition.onstart = null;
  speechRecognition.onresult = null;
  speechRecognition.onerror = null;
  speechRecognition.onend = null;
  speechRecognition.abort();
  speechRecognition = null;
});
</script>

<style lang="scss" scoped>
.ai-care-page { padding: 4px; }
.hero { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; background: linear-gradient(120deg, #eff6ff, #f8fbff); }
.hero-actions { display: flex; align-items: center; gap: 10px; }
.eyebrow { color: #409eff; font-size: 12px; letter-spacing: 1.5px; }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.grid { display: grid; grid-template-columns: 1fr 1.2fr; gap: 16px; }
.handover { margin-top: 16px; }
.family-report { margin-top: 16px; }
.report-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.report-text { margin-top: 14px; }
.voice-toolbar { width: 100%; margin-top: 10px; }
.voice-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.voice-help, .voice-interim { margin-top: 8px; color: #6b7280; font-size: 12px; line-height: 1.6; }
.voice-interim { color: #2563eb; }
.voice-alert { margin-top: 8px; }
.handover :deep(.el-card__header) { display: flex; justify-content: space-between; align-items: center; }
.summary { color: #374151; line-height: 1.8; }
.follow-up { padding: 10px 12px; margin-bottom: 8px; border-radius: 6px; background: #fff7ed; color: #9a3412; }
.follow-up { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.follow-up.done { background: #f0fdf4; color: #166534; }
.note-preview { cursor: pointer; flex: 1; }
.note-preview:hover { text-decoration: underline; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } .hero { align-items: flex-start; gap: 12px; flex-direction: column; } .hero-actions, .report-toolbar { width: 100%; flex-wrap: wrap; } }
</style>
