<template>
  <div class="login-page">
    <!-- 背景装饰 -->
    <div class="bg-grid"></div>
    <div class="bg-glow glow-1"></div>
    <div class="bg-glow glow-2"></div>

    <div class="login-body">
      <!-- 左侧品牌区 -->
      <div class="brand">
        <div class="brand-logo">
          <el-icon :size="34"><Box /></el-icon>
        </div>
        <h1 class="brand-title">WMS 智能仓储管理系统</h1>
        <p class="brand-sub">基于 SpringBoot + Vue 的一体化智能仓储管理平台</p>
        <ul class="brand-features">
          <li><el-icon><CircleCheckFilled /></el-icon>入库质检上架 · 出库拣货复核 全流程管控</li>
          <li><el-icon><CircleCheckFilled /></el-icon>库存预警 · 临期提醒 · 盘点盈亏自动修正</li>
          <li><el-icon><CircleCheckFilled /></el-icon>ECharts 数据看板 · 多维度统计分析</li>
        </ul>
      </div>

      <!-- 右侧登录卡片（玻璃拟态） -->
      <div class="login-card">
        <h2 class="title">欢迎登录</h2>
        <p class="subtitle">请使用账号密码登录系统</p>
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
        <div class="demo-tip">演示账号：admin / 123456</div>
      </div>
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
  captchaImg.value = res.data.img
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
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(140deg, #0a1834 0%, #10264f 48%, #143a6e 100%);
}

/* 网格纹理背景 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(91, 140, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(91, 140, 255, 0.05) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse 90% 80% at 50% 40%, #000 30%, transparent 75%);
}

/* 光斑点缀 */
.bg-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  pointer-events: none;
}
.glow-1 {
  width: 480px;
  height: 480px;
  background: rgba(46, 107, 255, 0.22);
  top: -140px;
  right: -100px;
}
.glow-2 {
  width: 380px;
  height: 380px;
  background: rgba(64, 190, 255, 0.12);
  bottom: -120px;
  left: -80px;
}

.login-body {
  position: relative;
  display: flex;
  align-items: center;
  gap: 72px;
  padding: 0 32px;
}

/* ==================== 左侧品牌区 ==================== */
.brand {
  max-width: 460px;
  color: #fff;
}

.brand-logo {
  width: 64px;
  height: 64px;
  border-radius: 16px;
  background: linear-gradient(135deg, #2e6bff, #5b8cff);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 28px rgba(46, 107, 255, 0.5);
  margin-bottom: 26px;
}

.brand-title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: 1px;
  background: linear-gradient(90deg, #ffffff, #a9c4ff);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-sub {
  margin: 12px 0 30px;
  color: rgba(174, 194, 232, 0.85);
  font-size: 14px;
  letter-spacing: 0.5px;
}

.brand-features {
  list-style: none;
  margin: 0;
  padding: 0;
}

.brand-features li {
  display: flex;
  align-items: center;
  gap: 10px;
  color: rgba(210, 224, 250, 0.9);
  font-size: 13.5px;
  line-height: 2.4;
}

.brand-features .el-icon {
  color: #5b8cff;
  font-size: 16px;
}

/* ==================== 右侧登录卡片 ==================== */
.login-card {
  width: 400px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.97);
  backdrop-filter: blur(16px);
  border-radius: 16px;
  padding: 38px 38px 26px;
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
  margin: 8px 0 26px;
  color: var(--wms-text-sub);
  font-size: 13px;
}

.captcha-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.captcha-img {
  height: 40px;
  border-radius: 6px;
  cursor: pointer;
  flex-shrink: 0;
  border: 1px solid #e4e9f2;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 6px;
  margin-top: 4px;
}

.links {
  display: flex;
  justify-content: flex-end;
  margin: 14px 0 10px;
}

.demo-tip {
  text-align: center;
  color: #9aa8c7;
  font-size: 12px;
  background: #f4f7fd;
  border-radius: 6px;
  padding: 8px 0;
}

/* 小屏适配：隐藏品牌区 */
@media (max-width: 900px) {
  .brand {
    display: none;
  }
  .login-body {
    gap: 0;
  }
}
</style>
