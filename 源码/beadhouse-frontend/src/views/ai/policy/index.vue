<template>
  <div class="policy-page">
    <el-card class="hero" shadow="never">
      <div>
        <div class="eyebrow">POLICY RAG</div>
        <h2>院内制度知识库</h2>
        <p>导入制度正文，回答护理问题时返回匹配片段和出处。</p>
      </div>
      <div class="hero-tags">
        <el-tag type="success">回答带引用</el-tag>
        <el-tag v-if="ocrStatus" :type="ocrStatus.available ? 'success' : 'warning'">
          扫描 OCR：{{ ocrStatus.available ? "已就绪" : "需配置" }}
        </el-tag>
      </div>
    </el-card>

    <div class="grid">
      <el-card shadow="never">
        <template #header><span>导入制度文档</span></template>
        <el-form label-position="top">
          <el-form-item label="制度标题"><el-input v-model="importForm.title" placeholder="例如：老人跌倒处理流程" /></el-form-item>
          <el-form-item label="文档来源"><el-input v-model="importForm.source" placeholder="例如：护理部制度汇编 2026" /></el-form-item>
          <el-form-item label="制度正文"><el-input v-model="importForm.content" type="textarea" :rows="11" placeholder="按段落粘贴制度正文，空行会分成检索片段" /></el-form-item>
          <el-button type="primary" :loading="importLoading" @click="handleImport">导入知识库</el-button>
          <label class="upload-button">
            <input type="file" accept=".txt,.md,.pdf,.docx,.png,.jpg,.jpeg,.bmp" @change="handleFileChange" />
            <el-button :loading="uploadLoading">上传文档或扫描图片</el-button>
          </label>
          <el-alert
            v-if="ocrStatus && !ocrStatus.available"
            class="ocr-tip"
            type="warning"
            :closable="false"
            show-icon
            :title="ocrStatus.message"
          />
        </el-form>
        <el-divider />
        <div class="catalog-title"><span>已导入制度</span><el-button size="small" link type="primary" :loading="reindexLoading" @click="handleReindex">重建混合索引</el-button></div>
        <el-empty v-if="!documents.length" description="暂无制度文档" />
        <div v-for="item in documents" :key="`${item.title}-${item.source}`" class="catalog-item">
          <div>
            <b>{{ item.title }}</b>
            <small>{{ item.source }} · {{ item.sectionCount }} 个片段</small>
            <el-tag class="sync-tag" size="small" :type="syncTagType(item.ragStatus)">索引 {{ syncText(item.ragStatus) }}</el-tag>
          </div>
          <el-button size="small" type="danger" link @click="handleDelete(item)">删除</el-button>
        </div>
      </el-card>

      <el-card shadow="never">
        <template #header><span>制度问答</span></template>
        <el-input v-model="question" type="textarea" :rows="4" placeholder="例如：老人跌倒后应该先做什么？" />
        <el-button class="query-button" type="primary" :loading="queryLoading" @click="handleQuery">检索制度</el-button>
        <el-alert v-if="answer" :title="answer.grounded ? '已找到相关制度片段' : '知识库暂无直接匹配'" :type="answer.grounded ? 'success' : 'warning'" :closable="false" show-icon />
        <el-tag v-if="answer?.retrievalBackend" class="backend-tag" size="small" type="info">检索：{{ answer.retrievalBackend }}</el-tag>
        <el-tag v-if="answer?.modelUsed" class="model-tag" size="small" type="success">模型已基于引用片段组织答案</el-tag>
        <div v-if="answer" class="answer">{{ answer.answer }}</div>
        <el-empty v-if="answer && !answer.citations.length" description="请补充制度文档或换一个问题" />
        <div v-if="answer?.citations.length" class="citations">
          <div v-for="citation in answer.citations" :key="citation.id" class="citation">
            <div class="citation-title">《{{ citation.title }}》 · 第 {{ citation.sectionNo }} 段</div>
            <div class="citation-source">来源：{{ citation.source }}</div>
            <div>{{ citation.content }}</div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { ref } from "vue";
import { deletePolicyDocument, getPolicyOcrStatus, importPolicy, listPolicyDocuments, queryPolicy, reindexPolicies, uploadPolicy } from "@/apis/aiPolicy";

const importForm = ref({ title: "", source: "", content: "" });
const question = ref("");
const answer = ref<any>(null);
const importLoading = ref(false);
const queryLoading = ref(false);
const documents = ref<any[]>([]);
const uploadLoading = ref(false);
const reindexLoading = ref(false);
const ocrStatus = ref<any>(null);

async function handleImport() {
  if (!importForm.value.title.trim() || !importForm.value.source.trim() || !importForm.value.content.trim()) {
    return ElMessage.warning("请填写制度标题、来源和正文");
  }
  importLoading.value = true;
  try {
    const res: any = await importPolicy(importForm.value);
    ElMessage.success(res.msg || "制度已导入");
    importForm.value.content = "";
    await loadDocuments();
  } finally {
    importLoading.value = false;
  }
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!/\.(txt|md|pdf|docx|png|jpg|jpeg|bmp)$/i.test(file.name)) {
    ElMessage.warning("当前支持 txt、md、pdf、docx、png、jpg 和 bmp 文件");
    input.value = "";
    return;
  }
  uploadLoading.value = true;
  try {
    const res: any = await uploadPolicy(file);
    if (res.code !== 200) {
      ElMessage.error(res.msg || "制度文件解析失败");
      return;
    }
    ElMessage.success(res.msg || "制度文件已导入");
    await loadDocuments();
  } finally {
    uploadLoading.value = false;
    input.value = "";
  }
}

async function loadDocuments() {
  const res: any = await listPolicyDocuments();
  documents.value = res.data || [];
}

async function loadOcrStatus() {
  try {
    const res: any = await getPolicyOcrStatus();
    ocrStatus.value = res.data;
  } catch {
    ocrStatus.value = { available: false, message: "暂时无法读取 OCR 状态" };
  }
}

async function handleDelete(item: any) {
  await ElMessageBox.confirm(`确定删除《${item.title}》吗？`, "删除制度", { type: "warning" });
  await deletePolicyDocument(item.title, item.source);
  ElMessage.success("制度已删除");
  await loadDocuments();
}

async function handleReindex() {
  reindexLoading.value = true;
  try {
    const res: any = await reindexPolicies();
    ElMessage.success(res.msg || "已提交索引重建");
  } finally {
    reindexLoading.value = false;
  }
}

async function handleQuery() {
  if (!question.value.trim()) return ElMessage.warning("请输入制度问题");
  queryLoading.value = true;
  try {
    const res: any = await queryPolicy(question.value);
    answer.value = res.data;
  } finally {
    queryLoading.value = false;
  }
}

function syncText(status: string) {
  return status === "SYNCED" ? "已同步" : status === "FAILED" ? "待重试" : status === "PENDING" ? "同步中" : "未同步";
}

function syncTagType(status: string): "success" | "danger" | "warning" | "info" {
  return status === "SYNCED" ? "success" : status === "FAILED" ? "danger" : status === "PENDING" ? "warning" : "info";
}

loadDocuments();
loadOcrStatus();
</script>

<style lang="scss" scoped>
.policy-page { padding: 4px; }
.hero { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; background: linear-gradient(120deg, #ecfdf5, #f8fffb); }
.hero-tags { display: flex; align-items: center; gap: 8px; }
.eyebrow { color: #10b981; font-size: 12px; letter-spacing: 1.5px; }
h2 { margin: 6px 0; color: #1f2937; }
p { margin: 0; color: #6b7280; }
.grid { display: grid; grid-template-columns: 1fr 1.2fr; gap: 16px; }
.query-button { margin: 12px 0; }
.answer { margin: 14px 0; padding: 12px; border-radius: 6px; background: #f8fafc; line-height: 1.8; color: #374151; }
.model-tag { margin-bottom: 8px; }
.backend-tag { margin: 8px 8px 0 0; }
.citation { margin-top: 10px; padding: 12px; border-left: 3px solid #10b981; background: #f0fdf4; line-height: 1.7; color: #374151; }
.citation-title { font-weight: 600; color: #166534; }
.citation-source { font-size: 12px; color: #6b7280; }
.catalog-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; font-weight: 600; color: #374151; }
.upload-button { display: inline-block; margin-left: 8px; }
.upload-button input { display: none; }
.ocr-tip { margin-top: 12px; }
.catalog-item { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 9px 0; border-bottom: 1px solid #f3f4f6; }
.catalog-item small { display: block; margin-top: 3px; color: #6b7280; }
.sync-tag { margin-top: 5px; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } .hero { align-items: flex-start; gap: 12px; flex-direction: column; } }
</style>
