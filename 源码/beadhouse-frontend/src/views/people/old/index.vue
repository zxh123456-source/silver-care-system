<template>
  <div class="table-box">
    <ProTable
      ref="proTable"
      title="用户列表"
      :columns="columns"
      :requestApi="getTableList"
      :initParam="initParam"
      :dataCallback="dataCallback"
    >
      <!-- 表格 header 按钮 -->
      <template #tableHeader>
        <el-button class="bg-blue" @click="exportExcelData" :loading="exporting" type="primary">
          <IconPark :icon="Download" class="mr-1"></IconPark>
          <span>导出</span>
        </el-button>
      </template>

      <!-- 表格操作 -->
      <template #operation="scope">
        <el-button
          size="small"
          link
          :icon="View"
          @click="openDrawer('长者档案',scope)"
        >
          长者档案
        </el-button>
        <el-button
          size="small"
          link
          :icon="EditPen"
          @click="openDrawer('编辑',scope)"
        >
          编辑
        </el-button>
        <el-popconfirm
          title="Are you sure to delete this?"
          @confirm="deleteData(scope)"
          confirm-button-type="danger"
        >
          <template #reference>
            <el-button size="small" link :icon="Delete">删除</el-button>
          </template>
        </el-popconfirm>
      </template>
    </ProTable>
    <oldDialog ref="DialogRef" />
  </div>
</template>

<script setup lang="ts" name="useProTable">
import { onBeforeUnmount, ref, reactive } from "vue";
import { captureResourceSession, isResourceSessionCurrent } from "@/utils/authResource";
import { ElMessage } from "element-plus";
import oldDialog from "./oldDialog/index.vue";
import { Download } from "@icon-park/vue-next";
import { ColumnProps } from "@/components/ProTable/interface";
import ProTable from "@/components/ProTable/index.vue";
import { Delete, EditPen, View } from "@element-plus/icons-vue";
import { deleteElder, editElder, exportExcel, pageElderByKey, sexList } from "@/apis/elderRecord";

// 获取 ProTable 元素，调用其获取刷新数据方法（还能获取到当前查询参数，方便导出携带参数）
const proTable = ref();
const initParam = reactive({});

// dataCallback 是对于返回的表格数据做处理，如果你后台返回的数据不是 list && total && pageNum && pageSize 这些字段，那么你可以在这里进行处理成这些字段
// 或者直接去 hooks/useTable.ts 文件中把字段改为你后端对应的就行
const dataCallback = (data: any) => {
  return {
    list: data.list,
    total: data.total,
    pageNum: data.pageNum,
    pageSize: data.pageSize
  };
};

// 如果你想在请求之前对当前请求参数做一些操作，可以自定义如下函数：params 为当前所有的请求参数（包括分页），最后返回请求列表接口
// 默认不做操作就直接在 ProTable 组件上绑定	:requestApi="getUserList"
const getTableList = (params: any) => {
  let newParams = JSON.parse(JSON.stringify(params));
  return pageElderByKey(newParams);
};

// Export stays within the captured account and never writes a publicly accessible file.
const exporting = ref(false);
const exportUrls = new Map<string, number>();
let exportAlive = true;
function releaseExport(url: string) {
  window.clearTimeout(exportUrls.get(url));
  exportUrls.delete(url);
  URL.revokeObjectURL(url);
}
onBeforeUnmount(() => {
  exportAlive = false;
  Array.from(exportUrls.keys()).forEach(releaseExport);
});
const exportExcelData = async () => {
  if (exporting.value) return;
  exporting.value = true;
  try {
    const session = captureResourceSession();
    const blob = await exportExcel(session);
    if (!exportAlive || !isResourceSessionCurrent(session)) return;
    const url = URL.createObjectURL(blob);
    // Keep the Blob alive long enough for browsers to start the download, then release it.
    exportUrls.set(url, window.setTimeout(() => releaseExport(url), 1000));
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = "长者档案.xlsx";
    document.body.appendChild(anchor);
    try { anchor.click(); } finally { anchor.remove(); }
    ElMessage.success("导出文件已生成");
  } catch (error: any) {
    if (exportAlive) ElMessage.error(error?.msg || error?.message || "导出失败，请重试");
  } finally {
    if (exportAlive) exporting.value = false;
  }
};

// 删除
const deleteData = async (params: any) => {
  const res: any = await deleteElder({
    elderId: params.row.id
  });
  responseHint(res);
};

// 响应提示
const responseHint = (res: any) => {
  if (res.code === 200) {
    ElMessage.success(res.msg);
    proTable.value.getTableList();
  } else {
    ElMessage.error(res.msg);
  }
};

// 打开 drawer(新增、查看、编辑)
const DialogRef = ref();
const openDrawer = (title: string, rowData: any = {}) => {
  const params = {
    title,
    rowData: { ...rowData.row },
    isView: title === "长者档案",
    api: title === "编辑" ? editElder : "",
    getTableList: proTable.value.getTableList
  };
  DialogRef.value.acceptParams(params);
};

// 表格配置项
const columns: ColumnProps<any>[] = [
  { prop: "rank", label: "序号", width: 55 },
  { prop: "name", label: "老人姓名", width: 100, search: { el: "input" } },
  { prop: "bedName", label: "床位名称" },
  {
    enum: sexList,
    prop: "sex",
    label: "老人性别",
    width: 100,
    search: { el: "select", props: { filterable: true } }
  },
  { prop: "age", label: "老人年龄", width: 100 },
  { prop: "idNum", label: "身份证号", search: { el: "input" } },
  { prop: "phone", label: "老人电话" },
  { prop: "checkFlag", label: "入住状态" },
  { prop: "operation", label: "操作", width: 220 }
];
</script>

<style lang="scss" scoped></style>
