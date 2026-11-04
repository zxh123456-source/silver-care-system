<template>
  <div>
    <el-dialog
      v-model="dialogVisible"
      :title="drawerProps.title"
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
      destroy-on-close
    >
      <div>
        <el-form
          :model="formData"
          class="login-form"
          ref="ruleFormRef"
          :rules="rules"
          :disabled="drawerProps.isView || submitting"
          label-width="120px"
        >
          <div class="flex justify-around flex-wrap">
            <div class="w-full md:w-1/2">
              <el-form-item label="老人姓名:" prop="elderName">
                <el-input v-model="formData.elderName" clearable>
                  <template #append>
                    <el-button
                      @click="openDrawer('elder')"
                      :icon="Plus"
                      :style="{ cursor: drawerProps.title !== '新增' ? 'not-allowed' : 'pointer' }"
                      :disabled="drawerProps.title !== '新增'"
                    />
                  </template>
                </el-input>
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人身份证:" prop="idNum">
                <el-input v-model="formData.idNum" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人性别:" prop="elderSex">
                <el-select
                  v-model="formData.elderSex"
                  placeholder="请选择"
                  class="w-full"
                >
                  <el-option label="男" value="男" />
                  <el-option label="女" value="女" />
                </el-select>
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人年龄:" prop="elderAge">
                <el-input v-model="formData.elderAge" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人电话:" prop="elderPhone">
                <el-input v-model="formData.elderPhone" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="家庭地址:" prop="elderAddress">
                <el-input v-model="formData.elderAddress" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="预定床位:" prop="bedId">
                <el-input
                  v-model="formData.bedName"
                  readonly
                  @clear="formData.bedId = ''"
                  @click="openDrawer('tree')"
                  clearable
                  placeholder="请选择"
                />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="预付定金:" prop="deposit">
                <el-input v-model="formData.deposit" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="交款人姓名:" prop="payerName">
                <el-input v-model="formData.payerName" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="交款人电话:" prop="payerPhone">
                <el-input v-model="formData.payerPhone" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="预定到期日期:" prop="dueDate">
                <el-date-picker
                  class="w-full"
                  type="datetime"
                  v-model="formData.dueDate"
                  placeholder="请选择"
                  unlink-panels
                  :disabled-date="disabledNowBeforeDate"
                />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="营销人员:" prop="staffId">
                <el-select
                  v-model="formData.staffId"
                  placeholder="请选择"
                  class="w-full"
                >
                  <el-option
                    clearable
                    :label="item.name"
                    :value="item.id"
                    v-for="item in ReserveStaffList"
                    :key="item.id"
                  />
                </el-select>
              </el-form-item>
            </div>
            <!-- 占位 -->
            <div
              class="border md:w-1/2"
              v-for="i in 2"
              :key="i"
              style="visibility: hidden; height: 0"
            ></div>
          </div>
          <div class="flex flex-row-reverse">
            <div></div>
          </div>
        </el-form>
      </div>
      <template v-if="!drawerProps.isView" #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false" :disabled="submitting">取消</el-button>
          <el-button class="bg-blue" type="primary" :loading="submitting" @click="handleSubmit">
            提交
          </el-button>
        </span>
      </template>
    </el-dialog>
    <elderListDialog v-if="dialogVisible" @getCheckElderInfo="getCheckElderInfo" ref="ElderDialogRef" />
    <treeDialog v-if="dialogVisible" @getCheckBedInfo="getCheckBedInfo" ref="TreeDialogRef" />
  </div>
</template>

<script lang="ts" setup>
import { onBeforeUnmount, reactive, ref, watch } from "vue";
import { captureResourceSession, isResourceSessionCurrent } from "@/utils/authResource";
import type { ResourceSession } from "@/utils/authResource";
import elderListDialog from "@/components/elderListDialog/index.vue";
import treeDialog from "@/components/treeDialog/index.vue";
import { ElMessage, FormInstance, FormRules } from "element-plus";
import { Plus } from "@element-plus/icons-vue";

import {
  idNumRule,
  integerRule,
  numberRule,
  phoneRule,
  stringRule,
  disabledNowBeforeDate
} from "@/utils/formRules";

import { getBuildTree, getReserveById, listReserveStaff, pageSearchElderByKey } from "@/apis/bookManage";

const ruleFormRef = ref<FormInstance | null>(null);
const dialogVisible = ref(false);
const submitting = ref(false);
let dialogEpoch = 0;
let dialogSession: ResourceSession | undefined;
watch(dialogVisible, visible => { if (!visible) { dialogEpoch++; submitting.value = false; } }, { flush: "sync" });
const sessionChanged = () => { if (dialogSession && !isResourceSessionCurrent(dialogSession)) dialogVisible.value = false; };
window.addEventListener("storage", sessionChanged);
onBeforeUnmount(() => { dialogEpoch++; window.removeEventListener("storage", sessionChanged); });
const canEdit = () => dialogVisible.value && !drawerProps.value.isView && !submitting.value
  && !!dialogSession && isResourceSessionCurrent(dialogSession);
const ReserveStaffList = ref<{ id: number; name: string | number }[]>([]);
const Reserve = ref<{ id: number; name: string | number }[]>([]);

const rules = reactive<FormRules>({
  bedId: [{ required: true, message: "请选择床位", trigger: "blur" }],
  deposit: [
    {
      required: true,
      validator(rule, value, callback) {
        numberRule(rule, value, callback, "预付定金", 1, 1000000);
      },
      trigger: "blur"
    }
  ],
  dueDate: [{ required: true, message: "请选择预定过期日期", trigger: "blur" }],
  elderAddress: [
    {
      required: true,
      validator(rule, value, callback) {
        stringRule(rule, value, callback, "老人地址", 6, 50);
      },
      trigger: "blur"
    }
  ],
  elderAge: [
    {
      required: true,
      validator(rule, value, callback) {
        integerRule(rule, value, callback, "老人年龄", 50, 200);
      },
      trigger: "blur"
    }
  ],
  elderName: [
    {
      required: true,
      validator(rule, value, callback) {
        stringRule(rule, value, callback, "老人姓名", 2, 10);
      },
      trigger: "blur"
    }
  ],
  elderPhone: [
    {
      required: true,
      validator(rule, value, callback) {
        phoneRule(rule, value, callback, "老人");
      },
      trigger: "blur"
    }
  ],
  elderSex: [{ required: true, message: "请选择老人性别", trigger: "blur" }],
  idNum: [
    {
      required: true,
      validator(rule, value, callback) {
        idNumRule(rule, value, callback, "老人");
      },
      trigger: "blur"
    }
  ],
  payerName: [
    {
      required: true,
      validator(rule, value, callback) {
        stringRule(rule, value, callback, "交款人姓名", 2, 10);
      },
      trigger: "blur"
    }
  ],
  payerPhone: [
    {
      required: true,
      validator(rule, value, callback) {
        phoneRule(rule, value, callback, "交款人");
      },
      trigger: "blur"
    }
  ],
  staffId: [{ required: true, message: "请选择营销人员", trigger: "blur" }]
});

interface DialogProps {
  title: string;
  isView: boolean;
  rowData?: any;
  api?: (params: any, session: ResourceSession) => Promise<any>;
  getTableList?: () => Promise<any>;
}

const drawerProps = ref<DialogProps>({
  isView: false,
  title: ""
});
const formData = ref({
  bedId: "",
  bedName: "",
  deposit: "",
  dueDate: "",
  elderAddress: "",
  elderAge: "",
  elderName: "",
  elderPhone: "",
  elderSex: "",
  idNum: "",
  payerName: "",
  payerPhone: "",
  staffId: ""
});

// 打开选择老人的列表 drawer
const ElderDialogRef = ref();
const TreeDialogRef = ref();
const openDrawer = (type: string) => {
  if (!canEdit()) return;
  if (type === "elder") {
    const params = {
      elderApi: pageSearchElderByKey
    };
    ElderDialogRef.value.elderAcceptParams(params);
  } else if (type === "tree") {
    const params = {
      treeApi: getBuildTree
    };
    TreeDialogRef.value.treeAcceptParams(params);
  }
};

// 获取选择老人的信息
const getCheckElderInfo = (row: any) => {
  if (!canEdit()) return;
  formData.value.elderName = row.name;
  formData.value.idNum = row.idNum;
  formData.value.elderAge = row.age;
  formData.value.elderSex = row.sex;
  formData.value.elderPhone = row.phone;
  formData.value.elderAddress = row.address;
};

// 获取选择床位的信息
const getCheckBedInfo = (bed: any) => {
  if (!canEdit()) return;
  formData.value.bedId = bed.id;
  formData.value.bedName = bed.name;
};

// 接收父组件传过来的参数
const acceptParams = async (params: DialogProps) => {
  const epoch = ++dialogEpoch;
  try {
    const session = captureResourceSession();
    dialogSession = session;
    const [staff, detail] = await Promise.all([
      listReserveStaff(),
      params.title === "新增" ? Promise.resolve({ code: 200, data: {} }) : getReserveById({
        reserveId: params.rowData.reserveId, elderId: params.rowData.elderId
      })
    ]);
    if (epoch !== dialogEpoch || !isResourceSessionCurrent(session)) return;
    if (staff.code !== 200 || detail.code !== 200) {
      ElMessage.error("预订资料读取失败，请重试");
      return;
    }
    ReserveStaffList.value = staff.data;
    Reserve.value = detail.data;
    drawerProps.value = params;
    formData.value = { bedId: "", bedName: "", deposit: "", dueDate: "", elderAddress: "", elderAge: "",
      elderName: "", elderPhone: "", elderSex: "", idNum: "", payerName: "", payerPhone: "", staffId: "",
      ...params.rowData, ...detail.data };
    submitting.value = false;
    dialogVisible.value = true;
  } catch { if (epoch === dialogEpoch) ElMessage.error("预订资料读取失败，请重试"); }
};

const handleSubmit = async () => {
  if (!canEdit() || !dialogSession) return;
  const epoch = dialogEpoch, session = dialogSession;
  submitting.value = true;
  try {
    const valid = await ruleFormRef.value!.validate().catch(() => false);
    if (!valid || epoch !== dialogEpoch || !isResourceSessionCurrent(session)) return;
    const res = await drawerProps.value.api!(formData.value, session);
    if (epoch !== dialogEpoch || !isResourceSessionCurrent(session)) return;
    if (res.code === 200) {
      ElMessage.success(res.msg);
      drawerProps.value.getTableList!();
      dialogVisible.value = false;
    } else ElMessage.error(res.msg);
  } catch { /* Keep form values for a retry after transport or business rejection. */ }
  finally { if (epoch === dialogEpoch) submitting.value = false; }
};

defineExpose({
  acceptParams
});
</script>
<style scoped>
.dialog-footer button:first-child {
  margin-right: 10px;
}
</style>
