import { http } from "@/utils";
import { isResourceSessionCurrent, requireWorkbookBlob, resourceSessionConfig } from "@/utils/authResource";
import type { ResourceSession } from "@/utils/authResource";

interface IPageElderByKey {
  name: string;
  elderName: string;
  idNum: string;
  sex: string;
  elderSex: string;
}

interface IGetElderById {
  elderId: string;
}

interface IEditElder {
  id: number;
  name: string;
  idNum: string;
  age: number;
  sex: string;
  phone: string;
  address: string;
}

// 性别
export const sexList = [
  { label: "男", value: "男" },
  { label: "女", value: "女" }
];

// 导出excel
export async function exportExcel(session: ResourceSession): Promise<Blob> {
  const response = await http.get("/api/elderRecord/exportExcel", {
    responseType: "blob", timeout: 60000, ...resourceSessionConfig(session)
  });
  const blob = await requireWorkbookBlob(response);
  if (!isResourceSessionCurrent(session)) throw new Error("登录状态已变化，请重新导出");
  return blob;
}

// 分页查询员工
export async function pageElderByKey(data: IPageElderByKey) {
  // 因为后台返回的字段与前端表单数据的prop不一样，但是组件封装是需要一样的，所以请求前增加一些这两个字段
  Reflect.has(data, "sex") ? (data.elderSex = data.sex) : "";
  Reflect.has(data, "name") ? (data.elderName = data.name) : "";
  return http.get("/api/elderRecord/pageElderByKey", {
    params: {
      ...data
    }
  });
}

// 根据编号获取长者信息
export async function getElderById(data: IGetElderById) {
  return http.get("/api/elderRecord/getElderById", {
    params: {
      ...data
    }
  });
}

// 根据编号获取长者档案
export async function getElderRecordById(data: IGetElderById) {
  return http.get("/api/elderRecord/getElderRecordById", {
    params: {
      ...data
    }
  });
}

// 编辑长者
export function editElder(data: IEditElder) {
  return http.put("/api/elderRecord/editElder", data);
}

// 删除长者
export async function deleteElder(data: IGetElderById) {
  return http.delete("/api/elderRecord/deleteElder", {
    params: {
      ...data
    }
  });
}
