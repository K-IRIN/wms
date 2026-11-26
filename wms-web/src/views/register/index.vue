<template>
  <div class="register-page">
    <div class="register-card">
      <h1 class="title">注册账号</h1>
      <p class="subtitle">注册成功后自动获得「普通操作员」权限</p>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px" size="large">
        <el-form-item label="账号" prop="username">
          <el-input v-model="form.username" placeholder="4~20 位登录账号" clearable />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="真实姓名" clearable />
        </el-form-item>
        <el-form-item label="部门" prop="dept">
          <el-input v-model="form.dept" placeholder="所在部门（选填）" clearable />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="11 位手机号（选填）" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="form.password" type="password" placeholder="6~20 位密码" show-password />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirm">
          <el-input v-model="form.confirm" type="password" placeholder="再次输入密码" show-password />
        </el-form-item>
        <el-button type="primary" class="register-btn" :loading="loading" @click="submit">注 册</el-button>
      </el-form>
      <div class="links">
        <el-link type="primary" @click="$router.push('/login')">已有账号？返回登录</el-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { register } from '../../api/auth'

const router = useRouter()
const formRef = ref()
const form = reactive({ username: '', realName: '', dept: '', phone: '', password: '', confirm: '' })

const rules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { min: 4, max: 20, message: '账号长度需为 4~20 位', trigger: 'blur' }
  ],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度需为 6~20 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (r, v, cb) => (v === form.password ? cb() : cb(new Error('两次输入的密码不一致'))),
      trigger: 'blur'
    }
  ]
}

const loading = ref(false)
const submit = async () => {
  await formRef.value.validate()
  loading.value = true
  try {
    await register({
      username: form.username,
      realName: form.realName,
      dept: form.dept,
      phone: form.phone,
      password: form.password
    })
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  height: 100vh;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(140deg, #0a1834 0%, #10264f 48%, #143a6e 100%);
}
.register-page::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(91, 140, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(91, 140, 255, 0.05) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse 90% 80% at 50% 40%, #000 30%, transparent 75%);
}
.register-card {
  position: relative;
  width: 440px;
  background: rgba(255, 255, 255, 0.97);
  backdrop-filter: blur(16px);
  border-radius: 16px;
  padding: 34px 38px 22px;
  box-shadow: 0 24px 64px rgba(4, 14, 38, 0.5);
  border: 1px solid rgba(255, 255, 255, 0.6);
}
.title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--wms-text-main);
}
.subtitle {
  margin: 8px 0 20px;
  color: var(--wms-text-sub);
  font-size: 13px;
}
.register-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 6px;
}
.links {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
