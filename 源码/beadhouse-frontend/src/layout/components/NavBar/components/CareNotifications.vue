<template>
  <el-button v-if="allowed" class="care-notification-button" text aria-label="查看护理未读提醒" @click="router.push('/ai-care/daily')">
    <el-badge :value="unreadCount || 0" :max="99" :hidden="!unreadCount">
      <span>护理提醒{{ unreadCount === null ? ' · 待刷新' : '' }}</span>
    </el-badge>
  </el-button>
</template>

<script setup lang="ts">
import { useRouter } from "vue-router";
import { useCareNotifications } from "@/utils/careNotifications";
const router = useRouter();
const { allowed, unreadCount } = useCareNotifications();
</script>

<style scoped>
.care-notification-button { margin-right: 12px; }
</style>
