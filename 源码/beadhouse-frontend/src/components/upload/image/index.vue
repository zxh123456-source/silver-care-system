<template>
  <el-upload
    v-model:file-list="imageList"
    :action="requestUrl"
    :headers="{ token: token }"
    accept="image/jpeg,image/png"
    :disabled="uploading"
    list-type="picture-card"
    :before-upload="uploadBefore"
    :on-error="handleError"
    :on-success="handleSuccess"
    :on-preview="handlePreview"
    :on-remove="handleRemove"
  >
    <el-icon>
      <Plus />
    </el-icon>
  </el-upload>

  <el-dialog v-model="dialogVisible">
    <img w-full :src="dialogImageUrl" alt="Preview Image" />
  </el-dialog>
</template>
<!--

TODO 上传图片（单 / 多）使用方法

// 1.导入组件
import elderListDialog from "@/components/elderListDialog/index.vue";

// 2.使用组件
<uploadImage :uploadParams="uploadParams" @setImageData='setImageData' />

// 3.定义变量和方法 single / multiple 任选一个
ref({
  uploadType: "single",
  imageList: ref<UploadUserFile[]>([])
});
const setImageData = (url: string) => {
  formData.value.picture = url;
};

// 4.显示单张上传的图片(多张图片时循环添加)
// 在加载上传组件之前清空图片列表
uploadParams.value.imageList = []
// 添加显示上传的图片
uploadParams.value.imageList.push({
  uid: 0,
  name: "0.jpeg",
  url: formData.value.image
});

-->
<script lang="ts" setup>
import store from "@/store";
import { ElMessage } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { computed, defineEmits, ref, watch } from "vue";
import type { UploadUserFile } from "element-plus";
// page data
const dialogImageUrl = ref("");
const dialogVisible = ref(false);
const imageList = ref<UploadUserFile[]>([]);
const uploading = ref(false);

// father component transfer to this date
const props = defineProps({
  uploadParams: Object
});
// 将即将渲染的图片添加到列表
watch(() => props.uploadParams?.imageList, images => {
  imageList.value = [...(images || [])];
}, { immediate: true });

// http request data
const requestUrl = "/api/file/uploadImg";
const token = computed(() => store.state.app.token);

// return data
// 获取父组件方法
const emits = defineEmits(["setImageData"]);
// 更新数据
const updateData = () => {
  const urls = imageList.value.filter(image => (!image.status || image.status === "success")
    && typeof image.url === "string" && !image.url.startsWith("blob:")).map(image => image.url);
  emits("setImageData", props.uploadParams?.uploadType === "single" ? (urls[0] || "") : urls);
};

// 上传之前
const uploadBefore = (file: any) => {
  const isJPG = file.type === "image/jpeg" || file.type === "image/png";
  const isLt2M = file.size > 0 && file.size <= 2 * 1024 * 1024;

  if (!isJPG) {
    ElMessage.error("只能上传jpg/png文件！");
  }
  if (!isLt2M) {
    ElMessage.error("图片不能为空且不能超过2MB！");
  }

  uploading.value = isJPG && isLt2M;
  return uploading.value;
};

// 上传失败
const handleError = (error: any, uploadFile: any) => {
  uploading.value = false;
  releasePreview(uploadFile);
  imageList.value = imageList.value.filter(image => image.uid !== uploadFile.uid);
  let message = "上传失败，请检查登录状态或稍后重试";
  try { message = JSON.parse(error.message)?.msg || message; } catch { /* Network errors have no JSON body. */ }
  ElMessage.error(message);
};

// 上传成功
const handleSuccess = (response: any, uploadFile: any) => {
  uploading.value = false;
  if (response?.code !== 200 || typeof response?.data?.url !== "string") {
    releasePreview(uploadFile);
    imageList.value = imageList.value.filter(image => image.uid !== uploadFile.uid);
    ElMessage.error(response?.msg || "上传失败，请重试");
    return;
  }
  releasePreview(uploadFile);
  uploadFile.url = response.data.url;
  if (props.uploadParams?.uploadType === "single") imageList.value = [uploadFile];
  updateData();
};

const releasePreview = (file: UploadUserFile) => {
  if (file.url?.startsWith("blob:")) URL.revokeObjectURL(file.url);
};

// 删除图片
const handleRemove = (uploadFile: any) => {
  imageList.value = imageList.value.filter(image => image.uid !== uploadFile.uid);
  updateData();

  // TODO 根据【imageId : uploadFile.uid】调用删除数据库图片的接口
};

// 查看大图
const handlePreview = (uploadFile: any) => {
  dialogImageUrl.value = uploadFile.url!;
  dialogVisible.value = true;
};
</script>
