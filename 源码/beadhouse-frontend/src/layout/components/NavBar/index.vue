<template>
  <div class="nav-container">
    <div class="nav-left">
      <el-button v-if="mobile" class="navigation-toggle" text aria-label="打开导航" :aria-expanded="navigationOpen" @click="$emit('open-navigation')">
        <el-icon :size="22"><Expand /></el-icon>
      </el-button>
      <Breadcrumb />
    </div>
    <div class="nav-right">
      <CareNotifications />
      <Avatar class="nav-item" />
    </div>
  </div>
</template>

<script setup lang="ts">
import CareNotifications from './components/CareNotifications.vue'
import Avatar from './components/Avatar.vue'
import Breadcrumb from './components/Breadcrumb.vue'
import { Expand } from '@element-plus/icons-vue'

defineProps<{ mobile?: boolean; navigationOpen?: boolean }>()
defineEmits<{ (event: 'open-navigation'): void }>()
</script>

<style lang="scss" scoped>
.nav-container {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  height: 48px;
  min-width: 0;
  background-color: #fff;
  border-bottom: 1px solid #d8d8d860;

  .nav-right,
  .nav-left {
    display: flex;
  }
}
@media (max-width: 767px) {
  .nav-container .nav-left { min-width: 0; align-items: center; flex: 1; }
  .nav-container .nav-right { flex-shrink: 0; align-items: center; }
  .navigation-toggle { min-width: 44px; height: 44px; margin: 0; padding: 8px; }
  :deep(.breadcrumb) { min-width: 0; margin-left: 0; font-size: 12px; }
  :deep(.el-breadcrumb__item:not(:last-child)) { display: none; }
  :deep(.el-breadcrumb__inner) { overflow-wrap: anywhere; line-height: 1.4; }
  :deep(.care-notification-button) { min-height: 44px; margin-right: 4px; padding: 8px; font-size: 12px; }
  :deep(.navbar-bg-hover) { min-height: 44px; margin-right: 4px; }
  :deep(.navbar-bg-hover p) { max-width: 42px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
</style>
