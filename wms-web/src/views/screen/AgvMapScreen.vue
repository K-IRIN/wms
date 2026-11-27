<template>
  <div class="agv-screen">
    <!-- 顶栏：标题 / 身份 / 时钟 / 连接状态 / 全屏 -->
    <header class="screen-head">
      <div class="brand">
        <span class="logo">WMS</span>
        <div class="titles">
          <h1>AGV 实时调度大屏</h1>
          <p>SMART WAREHOUSE · LIVE FLOOR</p>
        </div>
      </div>
      <div class="meta">
        <span class="who">
          <span class="role">{{ roleText }}</span>
          <span class="name">{{ userStore.userInfo?.nickname || userStore.userInfo?.username || '未登录' }}</span>
        </span>
        <span class="clock">{{ clock }}</span>
        <span class="ws" :class="{ on: agvStore.connected }">
          <i class="dot"></i>{{ agvStore.connected ? '实时连接' : '连接断开' }}
        </span>
        <el-button size="small" plain @click="toggleFull">{{ isFull ? '退出全屏' : '全屏' }}</el-button>
        <el-button size="small" plain @click="backToSystem">返回系统</el-button>
      </div>
    </header>

    <!-- 主体：直接复用现有地图页，零重构（阶段 9.5 方案 A） -->
    <main class="screen-body">
      <AgvMapView />
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../store/user'
import { useAgvStore } from '../../store/agv'
import AgvMapView from '../agv/map/index.vue'

const router = useRouter()
const userStore = useUserStore()
const agvStore = useAgvStore()

const clock = ref('')
const isFull = ref(false)
let timer = null

const roleText = computed(() => (userStore.roles?.length ? userStore.roles.join(' / ') : 'GUEST'))

function tickClock() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  clock.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function toggleFull() {
  if (document.fullscreenElement) {
    document.exitFullscreen?.()
  } else {
    document.documentElement.requestFullscreen?.()
  }
}

function syncFull() {
  isFull.value = !!document.fullscreenElement
}

function backToSystem() {
  router.push('/')
}

onMounted(() => {
  tickClock()
  timer = setInterval(tickClock, 1000)
  document.addEventListener('fullscreenchange', syncFull)
  // 幂等：Layout 里已连过则跳过，直接进大屏时由这里建连
  agvStore.connect()
})

onBeforeUnmount(() => {
  clearInterval(timer)
  document.removeEventListener('fullscreenchange', syncFull)
})
</script>

<style scoped>
.agv-screen {
  min-height: 100vh;
  background: radial-gradient(1200px 700px at 20% -10%, #12305f 0%, #071228 55%, #050b18 100%);
  color: #dbe6ff;
  display: flex;
  flex-direction: column;
  padding: 14px 18px 18px;
  box-sizing: border-box;
}

.screen-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  padding: 2px 6px 14px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.logo {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: 12px;
  font-weight: 800;
  letter-spacing: 1px;
  color: #fff;
  background: linear-gradient(140deg, #2e6bff, #4fd8a5);
  box-shadow: 0 6px 22px rgba(46, 107, 255, 0.35);
}

.titles h1 {
  margin: 0;
  font-size: 22px;
  letter-spacing: 1.5px;
  color: #fff;
}

.titles p {
  margin: 3px 0 0;
  font-size: 11px;
  letter-spacing: 2.4px;
  color: #6f8dbd;
}

.meta {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.who {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 5px 12px;
  border-radius: 18px;
  background: rgba(46, 107, 255, 0.12);
  border: 1px solid rgba(91, 140, 255, 0.3);
}

.who .role {
  color: #7ea6ff;
  font-size: 11px;
  letter-spacing: 0.6px;
}

.who .name {
  color: #eaf1ff;
}

.clock {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 15px;
  color: #9fc0ff;
  letter-spacing: 0.5px;
}

.ws {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 4px 11px;
  border-radius: 16px;
  color: #f89a9a;
  background: rgba(245, 108, 108, 0.14);
}

.ws .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f56c6c;
}

.ws.on {
  color: #4fd8a5;
  background: rgba(34, 176, 125, 0.14);
}

.ws.on .dot {
  background: #22b07d;
  box-shadow: 0 0 8px #22b07d;
}

.screen-body {
  flex: 1;
  min-height: 0;
}

/* 大屏场景去掉页面默认内边距，让地图铺满 */
.screen-body :deep(.page) {
  padding: 0;
}

.screen-body :deep(.map-panel) {
  height: 100%;
}
</style>
