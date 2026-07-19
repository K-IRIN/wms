<template>
  <div class="login-page">
    <div class="login-card">
      <h1 class="title">📦 WMS 智能仓储管理系统</h1>
      <p class="subtitle">基于 SpringBoot + Vue 的智能仓储管理平台</p>
      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入账号" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password :prefix-icon="Lock" />
        </el-form-item>
        <el-form-item prop="code">
          <div class="captcha-row">
            <el-input v-model="form.code" placeholder="验证码" :prefix-icon="Key" maxlength="4" />
            <img v-if="captchaImg" :src="captchaImg" class="captcha-img" title="看不清？点击刷新" alt="验证码" @click="loadCaptcha" />
          </div>
        </el-form-item>
        <el-button type="primary" class="login-btn" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
      <div class="links">
        <el-link type="primary" @click="$router.push('/register')">没有账号？立即注册</el-link>
      </div>
      <el-alert type="info" :closable="false" title="演示账号：admin / 123456" show-icon />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Key } from '@element-plus/icons-vue'
import { getCaptcha } from '../../api/auth'
import { useUserStore } from '../../store/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()

const formRef = ref()
const form = reactive({ username: '', password: '', code: '' })
const rules = {
  username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }]
}

const captchaImg = ref('')
const captchaKey = ref('')
const loading = ref(false)

const loadCaptcha = async () => {
  const res = await getCaptcha()
  captchaKey.value = res.data.key
  captchaImg.value = 'data:image/png;base64,' + res.data.img
  form.code = ''
}

const submit = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    await store.login({ ...form, key: captchaKey.value })
    ElMessage.success('登录成功，欢迎回来')
    router.push(route.query.redirect || '/')
  } catch (e) {
    // request.js 已提示错误，这里刷新验证码即可
    loadCaptcha()
  } finally {
    loading.value = false
  }
}

onMounted(loadCaptcha)
</script>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4b6f 100%);
}
.login-card {
  width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 36px 36px 28px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.25);
}
.title {
  margin: 0;
  font-size: 22px;
  text-align: center;
}
.subtitle {
  margin: 8px 0 24px;
  text-align: center;
  color: #909399;
  font-size: 13px;
}
.captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
.captcha-img {
  height: 40px;
  border-radius: 4px;
  cursor: pointer;
  flex-shrink: 0;
}
.login-btn {
  width: 100%;
}
.links {
  display: flex;
  justify-content: flex-end;
  margin: 12px 0;
}
</style>
