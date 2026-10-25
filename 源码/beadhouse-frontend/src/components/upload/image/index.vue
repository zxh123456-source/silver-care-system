<template>
  <el-upload
    v-model:file-list="imageList"
    action="/api/file/uploadImg"
    :http-request="uploadRequest"
    accept="image/jpeg,image/png"
    :disabled="uploading || sessionInvalid"
    list-type="picture-card"
    :before-upload="uploadBefore"
    :on-error="handleError"
    :on-success="handleSuccess"
  >
    <el-icon><Plus /></el-icon>
    <template #file="{ file }">
      <img v-if="file.url" class="el-upload-list__item-thumbnail" :src="file.url" alt="图片预览" />
      <span v-else class="image-placeholder">图片暂不可查看</span>
      <span class="el-upload-list__item-actions">
        <button type="button" aria-label="查看图片" :disabled="!file.url || sessionInvalid" @click="handlePreview(file)">
          <el-icon><ZoomIn /></el-icon>
        </button>
        <button type="button" aria-label="移除图片" :disabled="uploading || sessionInvalid" @click="handleRemove(file)">
          <el-icon><Delete /></el-icon>
        </button>
      </span>
    </template>
  </el-upload>
  <el-dialog v-model="dialogVisible" title="图片预览">
    <img v-if="dialogImageUrl" w-full :src="dialogImageUrl" alt="图片预览" />
  </el-dialog>
</template>

<script lang="ts" setup>
import store from "@/store";
import { ElMessage } from "element-plus";
import { Plus, ZoomIn, Delete } from "@element-plus/icons-vue";
import { onBeforeUnmount, ref, watch } from "vue";
import type { UploadUserFile, UploadRequestOptions } from "element-plus";
import {
  captureResourceSession, fetchResourceImage, isResourceSessionCurrent, uploadResourceImage
} from "@/utils/authResource";
import type { ResourceImage, ResourceSession } from "@/utils/authResource";

const props = defineProps({ uploadParams: Object });
const emits = defineEmits(["setImageData"]);
const dialogImageUrl = ref("");
const dialogVisible = ref(false);
const imageList = ref<UploadUserFile[]>([]);
const uploading = ref(false);
const sessionInvalid = ref(false);
// Persisted URLs never share the field that Element Plus uses to render Blob previews.
const resources = new Map<number, { value: string; image?: ResourceImage }>();
const pendingPreviews = new Set<ResourceImage>();
let generation = 0;
let alive = true;
let session: ResourceSession | undefined;
let nextUid = -1;

function releaseFile(file: UploadUserFile) {
  const resource = resources.get(file.uid!);
  if (resource?.image) resource.image.release();
  else if (file.url?.startsWith("blob:")) URL.revokeObjectURL(file.url);
  resources.delete(file.uid!);
}
function clearImages() {
  generation++;
  imageList.value.forEach(releaseFile);
  imageList.value = [];
  resources.clear();
  pendingPreviews.forEach(image => image.release());
  pendingPreviews.clear();
  dialogVisible.value = false;
  dialogImageUrl.value = "";
  uploading.value = false;
}
function checkSession() {
  if (session && !isResourceSessionCurrent(session)) {
    sessionInvalid.value = true;
    clearImages();
  }
}

watch(() => props.uploadParams?.imageList, async images => {
  clearImages();
  const version = generation;
  try { session = captureResourceSession(); }
  catch { sessionInvalid.value = true; return; }
  sessionInvalid.value = false;
  const captured = session;
  const files: UploadUserFile[] = (images || []).map((image: UploadUserFile) => {
    const file = { uid: nextUid--, name: image.name || "图片", status: "success" as const };
    resources.set(file.uid, { value: typeof image.url === "string" ? image.url : "" });
    return file;
  });
  imageList.value = files;
  await Promise.all(files.map(async file => {
    const resource = resources.get(file.uid!)!;
    if (!resource.value) return;
    try {
      const image = await fetchResourceImage(resource.value, captured);
      if (!alive || version !== generation || !isResourceSessionCurrent(captured)
        || resources.get(file.uid!) !== resource) { image.release(); return; }
      resource.image = image;
      const visible = imageList.value.find(item => item.uid === file.uid);
      if (visible) visible.url = image.previewUrl;
      else image.release();
    } catch { /* Keep an unresolved legacy reference unchanged while displaying a placeholder. */ }
  }));
}, { immediate: true, deep: true });

watch(() => [store.state.app.token, store.state.app.userPeofile?.userid], checkSession, { flush: "sync" });
window.addEventListener("storage", checkSession);
onBeforeUnmount(() => {
  alive = false;
  clearImages();
  window.removeEventListener("storage", checkSession);
});

function updateData() {
  const values = imageList.value.map(file => resources.get(file.uid!)?.value).filter(Boolean);
  emits("setImageData", props.uploadParams?.uploadType === "single" ? (values[0] || "") : values);
}
function uploadBefore(file: File) {
  checkSession();
  if (sessionInvalid.value || !session) return false;
  const supported = file.type === "image/jpeg" || file.type === "image/png";
  const sizeValid = file.size > 0 && file.size <= 2 * 1024 * 1024;
  if (!supported) ElMessage.error("只能上传jpg/png文件！");
  if (!sizeValid) ElMessage.error("图片不能为空且不能超过2MB！");
  uploading.value = supported && sizeValid;
  return uploading.value;
}
async function uploadRequest(options: UploadRequestOptions) {
  const captured = session;
  const version = generation;
  if (!captured || sessionInvalid.value || !isResourceSessionCurrent(captured)) throw new Error("登录状态已变化");
  const metadata = await uploadResourceImage(options.file, captured);
  const image = await fetchResourceImage(metadata.url, captured);
  if (!alive || version !== generation || !isResourceSessionCurrent(captured)) {
    image.release();
    throw new Error("登录状态或当前图片已变化，请重新打开表单");
  }
  pendingPreviews.add(image);
  return { metadata, image, session: captured, version };
}
function handleError(error: any, file: UploadUserFile) {
  releaseFile(file);
  imageList.value = imageList.value.filter(item => item.uid !== file.uid);
  uploading.value = false;
  if (alive && session && isResourceSessionCurrent(session)) ElMessage.error(error?.msg || error?.message || "上传失败，请重试");
}
function handleSuccess(result: { metadata: { url: string }; image: ResourceImage; session: ResourceSession; version: number }, file: UploadUserFile) {
  pendingPreviews.delete(result.image);
  if (!alive || result.version !== generation || !isResourceSessionCurrent(result.session)) {
    result.image.release();
    releaseFile(file);
    imageList.value = imageList.value.filter(item => item.uid !== file.uid);
    return;
  }
  releaseFile(file);
  if (props.uploadParams?.uploadType === "single") {
    imageList.value.filter(item => item.uid !== file.uid).forEach(releaseFile);
    imageList.value = [file];
  }
  resources.set(file.uid!, { value: result.metadata.url, image: result.image });
  file.url = result.image.previewUrl;
  file.status = "success";
  uploading.value = false;
  dialogVisible.value = false;
  dialogImageUrl.value = "";
  updateData();
}
function handleRemove(file: UploadUserFile) {
  checkSession();
  if (uploading.value || sessionInvalid.value) return;
  releaseFile(file);
  imageList.value = imageList.value.filter(item => item.uid !== file.uid);
  dialogVisible.value = false;
  dialogImageUrl.value = "";
  updateData();
}
function handlePreview(file: UploadUserFile) {
  checkSession();
  if (sessionInvalid.value || !file.url?.startsWith("blob:")) return;
  dialogImageUrl.value = file.url;
  dialogVisible.value = true;
}
</script>

<style scoped>
.image-placeholder { display: grid; place-items: center; height: 100%; color: #606266; }
.el-upload-list__item-actions button { margin: 0 6px; }
.el-upload-list__item-actions button:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
