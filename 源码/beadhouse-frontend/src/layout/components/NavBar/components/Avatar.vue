<template>
  <el-dropdown trigger="click">
    <span class="navbar-bg-hover">
      <img v-if="avator" :src="avator" alt="头像" />
      <span v-else aria-label="默认头像">●</span>
      <p v-if="username" class="dark:text-black">{{ username }}</p>
    </span>
    <template #dropdown>
      <el-dropdown-menu class="logout">
        <el-dropdown-item @click="editPassShow">
          <svg-icon
            icon="verify"
            size="14"
            style="margin-right: 5px"
          ></svg-icon>
          修改密码
        </el-dropdown-item>
        <el-dropdown-item @click="logout">
          <svg-icon
            icon="logout"
            size="14"
            style="margin-right: 5px"
          ></svg-icon>
          退出登录
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <el-dialog v-model="editPassVisible" title="修改密码" :close-on-click-modal="!loading" :close-on-press-escape="!loading" :show-close="!loading">
    <template #default>
      <el-form
        :disabled="loading"
        :model="formData"
        class="forget-pass-form"
        ref="ruleFormRef"
        :rules="editPassRules"
        size="large"
      >
        <el-form-item prop="oldPass">
          <el-input
            v-model="formData.oldPass"
            placeholder="旧密码"
            type="password"
            :prefix-icon="useRenderIcon('password', { size: 12 })"
            clearable
            show-password
          />
        </el-form-item>

        <el-form-item prop="newPass">
          <el-input
            v-model="formData.newPass"
            placeholder="新密码"
            type="password"
            :prefix-icon="useRenderIcon('password', { size: 12 })"
            clearable
            show-password
          />
        </el-form-item>

        <el-form-item prop="confirmPassword">
          <el-input
            v-model="formData.confirmPassword"
            placeholder="确认密码"
            type="password"
            :prefix-icon="useRenderIcon('password', { size: 12 })"
            clearable
            show-password
          />
        </el-form-item>
      </el-form>
    </template>
    <template #footer>
      <div class="dialog-footer">
        <el-button :disabled="loading" @click="editPassVisible = false">取消</el-button>
        <el-button
          class="bg-blue"
          type="primary"
          @click="handleEditPass(ruleFormRef)"
          :loading="loading"
        >
          修改
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import store from '@/store'
import { useRenderIcon } from '@/hooks/useIcons'
import { onBeforeUnmount, reactive, ref, watch } from 'vue'
import { captureResourceSession, fetchResourceImage, isResourceSessionCurrent } from '@/utils/authResource'
import type { ResourceImage, ResourceSession } from '@/utils/authResource'
import { editPass, getLogout } from '@/apis/user'
import { createAsyncScope } from '@/utils/asyncScope'
import type { AsyncTicket } from '@/utils/asyncScope'
import { ElMessage, FormInstance, FormRules } from 'element-plus'

const avator = ref('')
let avatarImage: ResourceImage | undefined
let avatarSession: ResourceSession | undefined
let avatarGeneration = 0
let avatarAlive = true
function clearAvatar() {
  avatarGeneration++
  avatarImage?.release()
  avatarImage = undefined
  avator.value = ''
}
async function loadAvatar() {
  clearAvatar()
  const version = avatarGeneration
  try {
    avatarSession = captureResourceSession()
    const captured = avatarSession
    const value = store.state.app.userPeofile.avator
    if (!value) return
    const image = await fetchResourceImage(value, captured)
    if (!avatarAlive || version !== avatarGeneration || !isResourceSessionCurrent(captured)) {
      image.release()
      return
    }
    avatarImage = image
    avator.value = image.previewUrl
  } catch { /* Unavailable historical avatars render the local placeholder. */ }
}
function onAvatarStorageChange() {
  if (avatarSession && !isResourceSessionCurrent(avatarSession)) clearAvatar()
}
watch(() => [store.state.app.token, store.state.app.userPeofile?.userid, store.state.app.userPeofile?.avator],
  loadAvatar, { immediate: true, flush: 'sync' })
window.addEventListener('storage', onAvatarStorageChange)
onBeforeUnmount(() => {
  avatarAlive = false
  clearAvatar()
  window.removeEventListener('storage', onAvatarStorageChange)
})
const username = store.state.app.userPeofile.username
const editPassVisible = ref(false)
const ruleFormRef = ref<FormInstance | null>(null)
const loading = ref(false)
const emptyPassword = () => ({ oldPass: '', newPass: '', confirmPassword: '' })
const formData = ref(emptyPassword())
const passwordScope = createAsyncScope(isResourceSessionCurrent)
let passwordTicket: AsyncTicket<ResourceSession> | undefined

const clearPasswordIntent = () => {
  passwordScope.invalidate()
  passwordTicket = undefined
  formData.value = emptyPassword()
  loading.value = false
  ruleFormRef.value?.clearValidate?.()
}
watch(editPassVisible, visible => { if (!visible) clearPasswordIntent() }, { flush: 'sync' })
const passwordSessionChanged = () => {
  if (passwordTicket && !passwordScope.isCurrent(passwordTicket)) editPassVisible.value = false
}
const stopPasswordWatch = watch(() => [store.state.app.token, store.state.app.userPeofile?.userid],
  passwordSessionChanged, { flush: 'sync' })
window.addEventListener('storage', passwordSessionChanged)
onBeforeUnmount(() => {
  clearPasswordIntent()
  passwordScope.dispose()
  stopPasswordWatch()
  window.removeEventListener('storage', passwordSessionChanged)
})

const editPassShow = () => {
  if (loading.value || editPassVisible.value) return
  try {
    clearPasswordIntent()
    passwordTicket = passwordScope.begin(captureResourceSession())
    editPassVisible.value = true
  } catch (failure: any) {
    ElMessage.error(failure?.message || '登录状态已变化，请刷新页面')
  }
}

const newPasswordError = (value: unknown) => {
  if (typeof value !== 'string' || value.length === 0) return '新密码不能为空'
  if (value.length < 8 || value.length > 64) return '新密码长度应为8~64个字符'
  if (new TextEncoder().encode(value).length > 72) return '新密码UTF-8长度不能超过72字节'
  return ''
}
const handleEditPass = async (formRef: FormInstance | null) => {
  const ticket = passwordTicket
  if (!ticket || !passwordScope.isCurrent(ticket)) {
    editPassVisible.value = false
    return
  }
  if (loading.value || !formRef || !editPassVisible.value) return
  loading.value = true
  try {
    let valid = false
    try { valid = await formRef.validate() } catch { return }
    if (!valid || !passwordScope.isCurrent(ticket) || !editPassVisible.value) return
    const value = { ...formData.value }
    const validation = !value.oldPass ? '旧密码不能为空' : newPasswordError(value.newPass)
    if (validation || value.confirmPassword !== value.newPass) {
      ElMessage.error(validation || '确认密码与新密码不一致')
      return
    }
    const response: any = await editPass({ oldPass: value.oldPass, newPass: value.newPass }, ticket.session)
    if (!passwordScope.isCurrent(ticket) || !editPassVisible.value) return
    if (response.code !== 200) {
      ElMessage.error(response.msg || '密码修改失败，请重试')
      return
    }
    editPassVisible.value = false
    ElMessage.success('密码已修改，请重新登录')
    // The successful write already revoked the server session. Clear local state directly.
    await store.dispatch('app/logout')
  } catch (failure: any) {
    if (passwordScope.isCurrent(ticket)) ElMessage.error(failure?.msg || failure?.message || '密码修改失败，请重试')
  } finally {
    if (passwordScope.isCurrent(ticket)) loading.value = false
  }
}

const logout = async () => {
  await getLogout()
  store.dispatch('app/logout')
  ElMessage({ message: '操作成功', type: 'success' })
}
const editPassRules = reactive<FormRules>({
  oldPass: [{ validator: (rule, value, callback) => {
    callback(typeof value === 'string' && value.length > 0 ? undefined : new Error('旧密码不能为空'))
  }, trigger: 'blur' }],
  newPass: [{ validator: (rule, value, callback) => {
    const error = newPasswordError(value)
    callback(error ? new Error(error) : undefined)
  }, trigger: 'blur' }],
  confirmPassword: [{ validator: (rule, value, callback) => {
    callback(typeof value !== 'string' || value.length === 0 ? new Error('确认密码不能为空')
      : value !== formData.value.newPass ? new Error('与新密码不一致') : undefined)
  }, trigger: 'blur' }]
})
</script>

<style lang="scss" scoped>
.navbar-bg-hover {
  img {
    border-radius: 50%;
    height: 22px;
    width: 22px;
    margin-right: 10px;
  }
}
</style>
