<template>
  <div>
    <el-dialog
      style="width: 70%"
      v-model="dialogVisible"
      :title="drawerProps.title"
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <div>
        <el-form
          :disabled="drawerProps.isView || loading || submitting"
          :model="formData"
          class="login-form"
          ref="ruleFormRef"
          :rules="rules"
          label-width="120px"
        >
          <div class="flex justify-around flex-wrap">
            <div class="w-full md:w-1/2">
              <el-form-item label="老人姓名:" prop="name">
                <el-input v-model="formData.name" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="身份证号:" prop="idNum">
                <el-input v-model="formData.idNum" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人年龄:" prop="age">
                <el-input v-model="formData.age" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人性别:" prop="sex">
                <el-select
                  v-model="formData.sex"
                  placeholder="请选择"
                  class="w-full"
                >
                  <el-option label="男" value="男" />
                  <el-option label="女" value="女" />
                </el-select>
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人电话:" prop="phone">
                <el-input v-model="formData.phone" clearable />
              </el-form-item>
            </div>
            <div class="w-full md:w-1/2">
              <el-form-item label="老人地址:" prop="address">
                <el-input v-model="formData.address" clearable />
              </el-form-item>
            </div>
            <!-- 占位 -->
            <div
              class="md:w-1/2"
              v-for="i in 1"
              :key="i"
              style="height: 0"
            ></div>
            <div style="width: 100%;" v-show="!show">
              <el-form-item label="床位信息:">
                <div class="w-full md:w-1/2">
                  <el-form-item label="床位名称:">
                    <el-input v-if="formData.elderBedByIdVo" v-model="formData.elderBedByIdVo.bedName" clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
                <div class="w-full md:w-1/2">
                  <el-form-item label="房间类型:">
                    <el-input v-if="formData.elderBedByIdVo" v-model="formData.elderBedByIdVo.roomType" clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
                <div class="w-full md:w-1/2" style="margin-top: 20px;">
                  <el-form-item label="月费（元）:">
                    <el-input v-if="formData.elderBedByIdVo" v-model="formData.elderBedByIdVo.monthPrice" clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
              </el-form-item>
            </div>
            <div style="width: 100%" v-show="!show">
              <el-form-item label="紧急联系人:">
                <div style="width: 100%">
                  <el-table :data="formData.elderEmergencyContactByIdVoList" border>
                    <el-table-column label="姓名" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.name" placeholder="请输入姓名" />
                      </template>
                    </el-table-column>
                    <el-table-column label="电话" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.phone" placeholder="请输入电话"></el-input>
                      </template>
                    </el-table-column>
                    <el-table-column label="邮箱" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.email" placeholder="请输入邮箱"></el-input>
                      </template>
                    </el-table-column>
                    <el-table-column label="关系" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.relation" placeholder="请输入关系"></el-input>
                      </template>
                    </el-table-column>
                    <el-table-column label="是否接收消息" align="center">
                      <template v-slot="scope">
                        <el-radio-group v-model="scope.row.receiveFlag" aria-placeholder="请选择">
                          <el-radio label="Y">是</el-radio>
                          <el-radio label="N">否</el-radio>
                        </el-radio-group>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </el-form-item>
            </div>
            <div style="width: 100%" v-show="!show">
              <el-form-item label="护理等级:">
                <div class="w-full md:w-1/2">
                  <el-form-item label="护理名称:">
                    <el-input v-if="formData.elderNurseGradeByIdVo" v-model="formData.elderNurseGradeByIdVo.name"
                              clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
                <div class="w-full md:w-1/2">
                  <el-form-item label="护理类型:">
                    <el-input v-if="formData.elderNurseGradeByIdVo" v-model="formData.elderNurseGradeByIdVo.type"
                              clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
                <div class="w-full md:w-1/2" style="margin-top: 20px;">
                  <el-form-item label="月费（元）:">
                    <el-input v-if="formData.elderNurseGradeByIdVo" v-model="formData.elderNurseGradeByIdVo.monthPrice"
                              clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
              </el-form-item>
            </div>
            <div style="width: 100%" v-show="!show">
              <el-form-item label="护理项目:" v-if="formData.elderNurseGradeByIdVo">
                <div style="width: 100%">
                  <el-table :data="formData.elderNurseGradeByIdVo.nurseGradeServiceVoList" border>
                    <el-table-column label="服务名称" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.name" />
                      </template>
                    </el-table-column>
                    <el-table-column label="服务单价" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.price"></el-input>
                      </template>
                    </el-table-column>
                    <el-table-column label="服务时间（min）" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.needDate"></el-input>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </el-form-item>
            </div>
            <div style="width: 100%" v-show="!show">
              <el-form-item label="餐饮套餐:">
                <div class="w-full md:w-1/2">
                  <el-form-item label="套餐名称:">
                    <el-input v-if="formData.elderCateringSetByIdVo" v-model="formData.elderCateringSetByIdVo.name"
                              clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
                <div class="w-full md:w-1/2">
                  <el-form-item label="月费（元）:">
                    <el-input v-if="formData.elderCateringSetByIdVo"
                              v-model="formData.elderCateringSetByIdVo.monthPrice" clearable />
                    <el-input v-else clearable />
                  </el-form-item>
                </div>
              </el-form-item>
            </div>
            <div style="width: 100%" v-show="!show">
              <el-form-item label="套餐食物:" v-if="formData.elderCateringSetByIdVo">
                <div style="width: 100%">
                  <el-table :data="formData.elderCateringSetByIdVo.setDishesVoList" border>
                    <el-table-column label="食物名称" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.name" />
                      </template>
                    </el-table-column>
                    <el-table-column label="食物单价" align="center">
                      <template v-slot="scope">
                        <el-input v-model="scope.row.price"></el-input>
                      </template>
                    </el-table-column>
                  </el-table>
                </div>
              </el-form-item>
            </div>
          </div>
          <div class="flex flex-row-reverse">
            <div></div>
          </div>
        </el-form>
      </div>
      <template v-if="!drawerProps.isView" #footer>
      <span class="dialog-footer" v-show="show">
        <el-button :disabled="submitting" @click="dialogVisible = false">取消</el-button>
        <el-button
          class="bg-blue"
          type="primary"
          :loading="submitting"
          :disabled="loading || !detailReady"
          @click="handleSubmit"
        >
          提交
        </el-button>
      </span>
      </template>
    </el-dialog>
  </div>
</template>

<script lang="ts" setup>
import { reactive, ref, watch, onBeforeUnmount } from "vue";
import store from "@/store";
import { captureResourceSession, isResourceSessionCurrent } from "@/utils/authResource";
import type { ResourceSession } from "@/utils/authResource";
import { createAsyncScope } from "@/utils/asyncScope";
import type { AsyncTicket } from "@/utils/asyncScope";
import { ElMessage, FormInstance, FormRules } from "element-plus";
import {
  idNumRule, integerRule,
  phoneRule,
  stringRule
} from "@/utils/formRules";
import { getElderById, getElderRecordById } from "@/apis/elderRecord";

const ruleFormRef = ref<FormInstance | null>(null);
const dialogVisible = ref(false);
const show = ref<boolean>(false);

const rules = reactive<FormRules>({
  roleId: [{ required: true, message: "请选择员工角色", trigger: "blur" }],
  name: [
    {
      required: true,
      validator(rule, value, callback) {
        stringRule(rule, value, callback, "老人姓名", 2, 10);
      },
      trigger: "blur"
    }
  ],
  idNum: [
    {
      required: true,
      validator(rule, value, callback) {
        idNumRule(rule, value, callback, "老人");
      },
      trigger: "blur"
    }
  ],
  age: [
    {
      required: true,
      validator(rule, value, callback) {
        integerRule(rule, value, callback, "老人年龄", 50, 200);
      },
      trigger: "blur"
    }
  ],
  sex: [{ required: true, message: "请选择老人性别", trigger: "blur" }],
  phone: [
    {
      required: true,
      validator(rule, value, callback) {
        phoneRule(rule, value, callback, "老人");
      },
      trigger: "blur"
    }
  ],
  address: [
    {
      required: true,
      validator(rule, value, callback) {
        stringRule(rule, value, callback, "老人地址", 6, 50);
      },
      trigger: "blur"
    }
  ]
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

const formData = ref<any>({
  id: "",
  name: "",
  idNum: "",
  age: "",
  sex: "",
  phone: "",
  address: "",
  elderEmergencyContactByIdVoList: [{
    name: "",
    phone: "",
    email: "",
    relation: "",
    receiveFlag: ""
  }],
  elderNurseGradeByIdVo: {
    name: "",
    type: "",
    monthPrice: "",
    nurseGradeServiceVoList: [
      {
        name: "",
        chargeMethod: "",
        price: "",
        needDate: ""
      }
    ]
  },
  elderCateringSetByIdVo: {
    id: "",
    name: "",
    monthPrice: "",
    setDishesVoList: [{
      id: "",
      name: "",
      price: ""
    }]
  },
  elderBedByIdVo: {
    bedId: "",
    bedName: "",
    roomType: "",
    monthPrice: ""
  }
});

const initialForm = JSON.parse(JSON.stringify(formData.value));
const loading = ref(false);
const submitting = ref(false);
const detailReady = ref(false);
const dialogScope = createAsyncScope(isResourceSessionCurrent);
let dialogTicket: AsyncTicket<ResourceSession> | undefined;
const clearDialogIntent = () => {
  dialogScope.invalidate();
  dialogTicket = undefined;
  loading.value = false;
  submitting.value = false;
  detailReady.value = false;
  formData.value = JSON.parse(JSON.stringify(initialForm));
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
  stopSessionWatch();
  window.removeEventListener('storage', sessionChanged);
});

const acceptParams = async (params: DialogProps) => {
  let ticket: AsyncTicket<ResourceSession> | undefined;
  try {
    ticket = dialogScope.begin(captureResourceSession());
    dialogTicket = ticket;
    drawerProps.value = { ...params, rowData: { ...params.rowData } };
    show.value = params.title === '编辑';
    formData.value = JSON.parse(JSON.stringify(initialForm));
    detailReady.value = false;
    submitting.value = false;
    loading.value = true;
    dialogVisible.value = true;
    ruleFormRef.value?.clearValidate?.();
    if (!params.rowData?.id) throw new Error('请先选择老人');
    const request = params.title === '长者档案' ? getElderRecordById : getElderById;
    const response: any = await request({ elderId: params.rowData.id }, ticket.session);
    if (!dialogScope.isCurrent(ticket) || !dialogVisible.value) return;
    if (response.code !== 200 || !response.data || typeof response.data !== 'object') {
      throw new Error(response.msg || '老人资料加载失败，请重新打开');
    }
    formData.value = response.data;
    detailReady.value = true;
  } catch (failure: any) {
    if (!ticket) dialogVisible.value = false;
    if (!ticket || dialogScope.isCurrent(ticket)) ElMessage.error(failure?.msg || failure?.message || '老人资料加载失败，请重试');
  } finally {
    if (ticket && dialogScope.isCurrent(ticket)) loading.value = false;
  }
};

const handleSubmit = async () => {
  const ticket = dialogTicket;
  if (!ticket || !dialogScope.isCurrent(ticket)) { dialogVisible.value = false; return; }
  if (!dialogVisible.value || submitting.value || loading.value || !detailReady.value || drawerProps.value.isView || !ruleFormRef.value) return;
  submitting.value = true;
  try {
    let valid = false;
    try { valid = await ruleFormRef.value.validate(); } catch { return; }
    if (!valid || !dialogScope.isCurrent(ticket) || !dialogVisible.value) return;
    if (!drawerProps.value.api) throw new Error('当前资料暂不可编辑');
    const response = await drawerProps.value.api(JSON.parse(JSON.stringify(formData.value)), ticket.session);
    if (!dialogScope.isCurrent(ticket) || !dialogVisible.value) return;
    if (response.code !== 200) {
      ElMessage.error(response.msg || '老人资料保存失败，请重试');
      return;
    }
    const refresh = drawerProps.value.getTableList;
    ElMessage.success(response.msg || '老人资料已保存');
    dialogVisible.value = false;
    await refresh?.();
  } catch (failure: any) {
    if (dialogScope.isCurrent(ticket)) ElMessage.error(failure?.msg || failure?.message || '老人资料保存失败，请重试');
  } finally {
    if (dialogScope.isCurrent(ticket)) submitting.value = false;
  }
};

defineExpose({
  acceptParams
});
</script>
<style scoped lang="scss">
.dialog-footer button:first-child {
  margin-right: 10px;
}
</style>
