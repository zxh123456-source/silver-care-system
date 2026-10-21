import { IRoute } from "@/router/types";
import type { Component } from "vue";
import { RouteRecordRaw } from "vue-router";
import router from "./index";
import store from "@/store";
import { arrayDeduplicationByFiled } from "@/utils/commonUtil";

const viewModules = import.meta.glob<Component>("/src/views/**/*.vue", { import: "default" });

/** 将后端返回的路由转换成生成树形结构 */
function formatRouteTree(data: IRoute[]) {
  const parents = data.filter(routeInfo => routeInfo.pid === 0);
  const children = data.filter(routeInfo => routeInfo.pid !== 0);

  dataToTree(parents, children);

  return parents;

  function dataToTree(parents: IRoute[], children: IRoute[]) {
    parents.map(parent => {
      children.map((child, index) => {
        if (child.pid === parent.id) {
          const _children: IRoute[] = JSON.parse(JSON.stringify(children));
          _children.splice(index, 1);
          dataToTree([child], _children);
          if (parent.children) {
            // 添加
            parent.children.push(child);
            // 菜单去重
            parent.children = arrayDeduplicationByFiled(parent.children, "id");
          } else {
            parent.children = [child];
          }
        }
      });
    });
  }
}

/** 将树形结构路由转化成真实的路由 */
function generateRouter(routeTree: IRoute[]) {
  const newRoutes = routeTree.map(route => {
    const _route: RouteRecordRaw = {
      path: route.path,
      name: route.name,
      meta: route.meta,
      redirect: route.redirect,
      component: () => {
        const componentPath = route.component.replace(/^\/+/, "").replace(/\.vue$/, "");
        const load = viewModules[`/src/views/${componentPath}.vue`];
        return load ? load() : Promise.reject(new Error(`找不到路由组件：${route.component}`));
      },
      children: []
    };

    if (route.children) {
      _route.children = generateRouter(route.children);
    }

    return _route;
  });

  return newRoutes;
}

//初始化动态路由
export async function initRoutes() {
  await store.dispatch("app/getRouterTree");
  const newRoutes = generateRouter(store.state.app.routeTree);
  newRoutes.forEach(route => router.addRoute("Layout", route));
}

//清除路由
export function clearRoutes() {
  //删除之前注册的路由 默认状态下只有三个，如果超过三个删除多余的路由
  const defaultRoutes = ["Layout", "Login", "NotFound"];
  const currentRoutes = router.getRoutes();
  if (currentRoutes.length != 3) {
    //执行删除
    currentRoutes.forEach(item => {
      if (!defaultRoutes.includes(String(item.name))) {
        router.removeRoute(String(item.name));
      }
    });
  }
}

export { formatRouteTree, generateRouter };
