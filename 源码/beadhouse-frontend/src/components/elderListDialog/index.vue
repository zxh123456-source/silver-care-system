<template>
  <el-dialog
    style="width: 70%"
    v-model="dialogVisible"
    title="选择老人"
    destroy-on-close
  >
    <div class="table-box">
      <ProTable
        :key="dialogGeneration"
        ref="proTable"
        title="用户列表"
        :columns="columns"
        :requestApi="getTableList"
      >
        <!-- 表格操作 -->
        <template #operation="scope">
          <el-popconfirm
            title="Are you sure to choose this?"
            @confirm="checkElder(scope.row)"
            confirm-button-type="warning"
          >
            <template #reference>
              <el-button size="small" link :icon="View" > 选择 </el-button>
            </template>
          </el-popconfirm>
        </template>
      </ProTable>
    </div>
  </el-dialog>
</template>

<script setup lang="ts" name="useProTable">
import { ref, watch, onBeforeUnmount } from "vue";
import store from "@/store";
import { captureResourceSession, isResourceSessionCurrent } from "@/utils/authResource";
import type { ResourceSession } from "@/utils/authResource";
import { createAsyncScope } from "@/utils/asyncScope";
import type { AsyncTicket } from "@/utils/asyncScope";
import { ColumnProps } from "@/components/ProTable/interface";
import ProTable from "@/components/ProTable/index.vue";
import { View } from "@element-plus/icons-vue";

const dialogVisible = ref(false);
const proTable = ref();
const drawerProps = ref<DialogProps>();

interface DialogProps {
  elderApi: (params: any, session?: ResourceSession) => Promise<any>;
}

const dialogScope = createAsyncScope(isResourceSessionCurrent);
const listScope = createAsyncScope(isResourceSessionCurrent);
const dialogGeneration = ref(0);
let dialogTicket: AsyncTicket<ResourceSession> | undefined;
let choices = new Map<string, any>();
const clearDialogIntent = () => {
  dialogScope.invalidate();
  listScope.invalidate();
  dialogTicket = undefined;
  choices.clear();
};
watch(dialogVisible, visible => { if (!visible) clearDialogIntent(); }, { flush: 'sync' });
const sessionChanged = () => {
  if (dialogTicket && !dialogScope.isCurrent(dialogTicket)) dialogVisible.value = false;
};
const stopSessionWatch = watch(() => [store.state.app.token, store.state.app.userPeofile?.userid],
  sessionChanged, { flush: 'sync' });
window.addEventListener('storage', sessionChanged);
onBeforeUnmount(() => {
  clearDialogIntent();
  dialogScope.dispose();
  listScope.dispose();
  stopSessionWatch();
  window.removeEventListener('storage', sessionChanged);
});

const getTableList = async (params: any) => {
  const opened = dialogTicket;
  if (!opened || !dialogVisible.value || !dialogScope.isCurrent(opened) || !drawerProps.value?.elderApi) {
    throw new Error('选择窗口已关闭或登录状态已变化');
  }
  const request = listScope.begin(opened.session);
  const response = await drawerProps.value.elderApi({ ...params }, opened.session);
  if (!dialogScope.isCurrent(opened) || !listScope.isCurrent(request) || !dialogVisible.value) {
    throw new Error('已忽略过期的老人列表');
  }
  if (response.code !== 200 || !Array.isArray(response.data?.list)) throw new Error(response.msg || '老人列表加载失败，请重试');
  choices = new Map<string, any>(response.data.list.map((row: any): [string, any] => [String(row.id), row]));
  return response;
};

// 表格配置项
const columns: ColumnProps<any>[] = [
  { prop: "rank", label: "序号", width: 55 },
  { prop: "name", label: "姓名", search: { el: "input" } },
  { prop: "idNum", label: "身份证号" },
  { prop: "sex", label: "性别" },
  { prop: "age", label: "年龄" },
  { prop: "phone", label: "电话", search: { el: "input" } },
  { prop: "address", label: "地址" },
  { prop: "operation", label: "操作", width: 70 }
];

const elderAcceptParams = (params: DialogProps) => {
  clearDialogIntent();
  try {
    dialogTicket = dialogScope.begin(captureResourceSession());
    dialogGeneration.value = dialogTicket.generation;
    drawerProps.value = { ...params };
    dialogVisible.value = true;
  } catch { dialogVisible.value = false; }
};
// 将方法主动暴露出来
defineExpose({
  elderAcceptParams
});

// const emit = defineEmits(['getCheckElderInfo'])
const emit = defineEmits<{
  (event: "getCheckElderInfo", val: any): void
}>();
// 向父组件传值
const checkElder = (row: any) => {
  if (!dialogTicket || !dialogScope.isCurrent(dialogTicket) || !dialogVisible.value) {
    dialogVisible.value = false;
    return;
  }
  const current = choices.get(String(row.id));
  if (!current) return;
  emit('getCheckElderInfo', { ...current });
  dialogVisible.value = false;
};
</script>

<style lang="scss" scoped></style>
