<template>
  <el-dialog
    style="width: 70%"
    v-model="dialogVisible"
    title="选择床位"
    destroy-on-close
  >
    <el-tree
      :data="data"
      :props="defaultProps"
      accordion
      @node-click="checkBed"
    />
  </el-dialog>
</template>

<script setup lang="ts" name="useProTable">
import { onBeforeUnmount, ref, watch } from "vue";
import { captureResourceSession, isResourceSessionCurrent } from "@/utils/authResource";
import type { ResourceSession } from "@/utils/authResource";
import { ElMessage } from "element-plus";

const data: any = ref();
const dialogVisible = ref(false);
const drawerProps = ref<DialogProps>();
let epoch = 0;
let session: ResourceSession | undefined;
watch(dialogVisible, visible => { if (!visible) epoch++; }, { flush: "sync" });
const sessionChanged = () => { if (session && !isResourceSessionCurrent(session)) { data.value = []; dialogVisible.value = false; } };
window.addEventListener("storage", sessionChanged);
onBeforeUnmount(() => { epoch++; window.removeEventListener("storage", sessionChanged); });

interface DialogProps {
  treeApi: () => Promise<any>;
}

const defaultProps = {
  id: "id",
  label: "name",
  children: "childrenList"
};

// 接收父组件传过来的参数
const treeAcceptParams = async (params: DialogProps) => {
  const version = ++epoch;
  try {
    const captured = captureResourceSession();
    session = captured;
    drawerProps.value = params;
    data.value = [];
    dialogVisible.value = true;
    const res: any = await params.treeApi();
    if (version !== epoch || !isResourceSessionCurrent(captured)) return;
    if (res.code !== 200) { ElMessage.error("床位列表读取失败，请重试"); return; }
    data.value = res.data;
  } catch { if (version === epoch) ElMessage.error("床位列表读取失败，请重试"); }
};
// 将方法主动暴露出来
defineExpose({
  treeAcceptParams
});

// const emit = defineEmits(['getCheckElderInfo'])
const emit = defineEmits<{
  (event: "getCheckBedInfo", val: any): void
}>();
// 向父组件传值
const checkBed = (bed: any) => {
  if (!dialogVisible.value || !session || !isResourceSessionCurrent(session)) return;
  if (bed.level === 4) {
    emit("getCheckBedInfo", bed);
    dialogVisible.value = false;
  }
};
</script>

<style lang="scss" scoped></style>
