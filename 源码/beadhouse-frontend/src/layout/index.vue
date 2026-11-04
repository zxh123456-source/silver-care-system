<template>
  <el-container
    class="layout-container"
    :class="{ hideSidebar: !isMobile && !$store.state.app.siderType, 'mobile-layout': isMobile }"
  >
    <el-aside v-if="!isMobile" :width="$store.state.app.siderType ? '230px' : '64px'">
      <SideBar />
    </el-aside>
    <el-drawer v-if="isMobile" v-model="navigationOpen" class="mobile-navigation" title="页面导航" direction="ltr" size="min(280px, calc(100vw - 44px))">
      <SideBar mobile @navigate="closeNavigation" />
    </el-drawer>
    <el-container class="main-container">
      <el-header>
        <NavBar :mobile="isMobile" :navigation-open="navigationOpen" @open-navigation="navigationOpen = true" />
      </el-header>
      <el-main>
        <el-scrollbar>
          <router-view v-slot="{ Component, route }">
            <transition name="fade-transform" mode="out-in">
              <div :key="route.path" class="route-view">
                <component :is="Component" />
              </div>
            </transition>
          </router-view>
        </el-scrollbar>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import SideBar from './components/SideBar/index.vue'
import NavBar from './components/NavBar/index.vue'

import { useMobileNavigation } from './useMobileNavigation'

const { isMobile, navigationOpen, closeNavigation } = useMobileNavigation()
</script>

<style lang="scss" scoped>
.layout-container {
  position: relative;
  width: 100%;
  height: 100%;
}

// 消除导航栏内边距
.el-header {
  position: relative;
  padding: 0 !important;
  height: 48px !important;
}

// 消除主内容内边距
.el-main {
  padding: 0 !important;
  background-color: #f0f2f5;
}

.el-scrollbar {
  background-color: rgb(246, 246, 246) !important;
  padding: 10px;
}
</style>
