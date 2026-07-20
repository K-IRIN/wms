<template>
  <div class="page monitor-page">
    <div class="monitor-panel">
      <!-- 顶部：标题 + WS 状态 + 最近告警条 -->
      <div class="monitor-header">
        <div class="title-wrap">
          <span class="title-text">AGV 设备运行监控</span>
          <span class="ws-badge" :class="{ online: agvStore.connected }">
            <i class="dot"></i>{{ agvStore.connected ? '实时连接中' : '连接断开' }}
          </span>
        </div>
        <span class="last-msg" v-if="agvStore.lastMsgAt">最近推送 {{ agvStore.lastMsgAt }}</span>
      </div>

      <!-- 告警条：有新告警（错误/警告级）时置顶展示 -->
      <transition name="el-fade-in">
        <div v-if="latestAlert" class="alert-bar" :class="latestAlert.level">
          <el-icon><WarningFilled /></el-icon>
          <span class="alert-msg">[{{ latestAlert.time }}] {{ latestAlert.msg }}</span>
          <el-icon class="close" @click="dismissAlert"><Close /></el-icon>
        </div>
      </transition>

      <!-- AGV 状态卡片 -->
      <div class="card-grid" v-loading="loading">
        <div v-for="agv in agvStore.agvList" :key="agv.id" class="agv-card" :class="{ fault: agv.status === 3 }">
          <div class="card-head">
            <div class="card-title">
              <span class="agv-code">{{ agv.agvCode }}</span>
              <span class="agv-name">{{ agv.agvName }}</span>
            </div>
            <span class="status-badge" :style="badgeStyle(agv)">{{ statusMap[agv.status]?.text }}</span>
          </div>

          <div class="battery-row">
            <span class="k">电量</span>
            <div class="battery-track">
              <div class="battery-fill" :style="{ width: agv.battery + '%', background: batteryColor(agv.battery) }"></div>
            </div>
            <span class="battery-num" :style="{ color: batteryColor(agv.battery) }">{{ agv.battery }}%</span>
          </div>

          <div class="info-rows">
            <div class="info-row"><span class="k">当前坐标</span><span>({{ agv.posX }}, {{ agv.posY }})</span></div>
            <div class="info-row"><span class="k">行驶速度</span><span>{{ agv.speed }} 格/秒</span></div>
            <div class="info-row">
              <span class="k">当前任务</span>
              <span v-if="taskOf(agv.id)" class="task-brief">
                {{ taskOf(agv.id).taskNo }} · {{ taskStatusText(taskOf(agv.id).status) }}
              </span>
              <span v-else class="none">—</span>
            </div>
          </div>

          <!-- E3.3 异常模拟操作区：仅管理员/设备运维员可见 -->
          <div v-if="canOperate" class="ops">
            <el-button size="small" type="danger" plain :disabled="agv.status === 3" @click="inject(agv, 'fault')">故障</el-button>
            <el-button size="small" type="warning" plain @click="inject(agv, 'lowBattery')">低电量</el-button>
            <el-button size="small" type="warning" plain :disabled="agv.status !== 1" @click="inject(agv, 'block')">堵塞</el-button>
            <el-button size="small" type="warning" plain :disabled="agv.status !== 1" @click="inject(agv, 'pickFail')">拣货失败</el-button>
            <el-button size="small" type="success" plain :disabled="agv.status !== 3" @click="repair(agv)">修复</el-button>
            <el-button size="small" plain class="log-btn" @click="openLogs(agv)">日志</el-button>
          </div>
          <div v-else class="ops readonly">
            <el-button size="small" plain class="log-btn" @click="openLogs(agv)">运行日志</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 运行日志抽屉 -->
    <el-drawer v-model="logVisible" :title="`${logAgv?.agvCode || ''} 运行日志`" size="460px">
      <el-table :data="logRows" v-loading="logLoading" size="small" border>
        <el-table-column prop="createTime" label="时间" width="150">
          <template #default="{ row }">{{ (row.createTime || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="logType" label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="logTypeTag(row.logType)" size="small">{{ row.logType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="content" label="内容" min-width="180" show-overflow-tooltip />
      </el-table>
      <el-pagination style="margin-top: 12px; justify-content: flex-end" background small
        layout="total, prev, pager, next" :total="logTotal" v-model:current-page="logPage" :page-size="8"
        @current-change="loadLogs" />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Close, WarningFilled } from '@element-plus/icons-vue'
import { useAgvStore } from '../../../store/agv'
import { useUserStore } from '../../../store/user'
import { listAgvDevice, listActiveAgvTask, injectAgvFault, repairAgvFault, pageAgvLog } from '../../../api/agv'

const agvStore = useAgvStore()
const userStore = useUserStore()
const loading = ref(false)

// 运行状态：0空闲 1运行中 2充电中 3故障
const statusMap = {
  0: { text: '空闲', color: '#22b07d' },
  1: { text: '运行中', color: '#4f8dff' },
  2: { text: '充电中', color: '#e6a23c' },
  3: { text: '故障', color: '#f56c6c' }
}
const batteryColor = (b) => (b > 50 ? '#22b07d' : b > 20 ? '#e6a23c' : '#f56c6c')
const badgeStyle = (agv) => {
  const c = statusMap[agv.status]?.color || '#6b7a99'
  return { color: c, background: c + '22', border: `1px solid ${c}55` }
}
const taskOf = (id) => agvStore.taskOfAgv(id)
const taskStatusText = (s) => ({ 0: '待分配', 1: '前往取货', 2: '取货中', 3: '前往卸货', 4: '卸货中', 5: '返回待命区' }[s] || '')

// E3.3 仅管理员 / 设备运维员可进行异常模拟
const canOperate = computed(() => userStore.roles.includes('ADMIN') || userStore.roles.includes('MAINTENANCE'))

// ==================== 告警提醒 ====================
const dismissedSeq = ref(null) // 手动关闭后不再重复展示同一条
const latestAlert = computed(() => {
  const a = agvStore.alerts.find((x) => x.level === 'error' || x.level === 'warn')
  return a && a._seq !== dismissedSeq.value ? a : null
})
const dismissAlert = () => { dismissedSeq.value = latestAlert.value?._seq }

// 新告警到达时右上角弹通知（error 红 / warn 黄 / info 蓝）
watch(() => agvStore.alerts[0]?._seq, (seq) => {
  if (!seq) return
  const a = agvStore.alerts[0]
  ElNotification({
    title: a.level === 'error' ? 'AGV 故障告警' : a.level === 'warn' ? 'AGV 运行提醒' : 'AGV 状态通知',
    message: `[${a.time}] ${a.msg}`,
    type: a.level === 'error' ? 'error' : a.level === 'warn' ? 'warning' : 'info',
    duration: a.level === 'info' ? 3000 : 5000
  })
})

// ==================== E3.3 异常模拟操作 ====================
const injectText = {
  fault: '故障停机（其执行中的任务将自动改派）',
  lowBattery: '电量不足（降至 8%）',
  block: '通道堵塞（暂停行驶 10 秒）',
  pickFail: '取货失败（任务退回待分配）'
}

async function inject(agv, type) {
  await ElMessageBox.confirm(
    `确定向 ${agv.agvCode} 注入「${injectText[type]}」吗？`,
    '异常模拟确认',
    { type: 'warning', confirmButtonText: '确定注入', cancelButtonText: '取消' }
  )
  await injectAgvFault(agv.id, type)
  ElMessage.success(`已向 ${agv.agvCode} 注入异常，设备状态将通过实时推送刷新`)
}

async function repair(agv) {
  await ElMessageBox.confirm(
    `确定修复 ${agv.agvCode} 的故障吗？修复后设备恢复空闲待命。`,
    '故障修复确认',
    { type: 'info', confirmButtonText: '确定修复', cancelButtonText: '取消' }
  )
  await repairAgvFault(agv.id)
  ElMessage.success(`${agv.agvCode} 已提交修复`)
}

// ==================== 运行日志 ====================
const logVisible = ref(false)
const logLoading = ref(false)
const logAgv = ref(null)
const logRows = ref([])
const logTotal = ref(0)
const logPage = ref(1)

function openLogs(agv) {
  logAgv.value = agv
  logPage.value = 1
  logVisible.value = true
  loadLogs()
}

async function loadLogs() {
  logLoading.value = true
  try {
    const res = await pageAgvLog({ agvId: logAgv.value.id, pageNum: logPage.value, pageSize: 8 })
    logRows.value = res.data.records
    logTotal.value = res.data.total
  } finally {
    logLoading.value = false
  }
}

const logTypeTag = (t) => ({ 故障: 'danger', 告警: 'warning', 充电: 'success', 状态变更: 'info' }[t] || 'info')

// 初始数据：HTTP 全量拉取灌入 store，之后由 WebSocket 实时刷新
onMounted(async () => {
  loading.value = true
  try {
    const [agvRes, taskRes] = await Promise.all([listAgvDevice(), listActiveAgvTask()])
    agvStore.seedAgvs(agvRes.data)
    agvStore.seedTasks(taskRes.data)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ==================== 深色科技风监控面板 ==================== */
.monitor-panel {
  border-radius: var(--wms-card-radius);
  background: linear-gradient(160deg, #0b1a36 0%, #10264f 60%, #133058 100%);
  box-shadow: var(--wms-card-shadow);
  padding: 16px 18px 18px;
  color: #dbe6ff;
  min-height: calc(100vh - 110px);
}

.monitor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title-text {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: #fff;
}

.last-msg {
  font-size: 12px;
  color: rgba(142, 162, 201, 0.8);
}

.ws-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 20px;
  background: rgba(245, 108, 108, 0.14);
  color: #f89a9a;
}

.ws-badge .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #f56c6c;
}

.ws-badge.online {
  background: rgba(34, 176, 125, 0.14);
  color: #4fd8a5;
}

.ws-badge.online .dot {
  background: #22b07d;
  box-shadow: 0 0 6px #22b07d;
  animation: blink 1.6s infinite;
}

@keyframes blink {
  50% { opacity: 0.35; }
}

/* ==================== 告警条 ==================== */
.alert-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: 8px;
  font-size: 13px;
  margin-bottom: 14px;
}

.alert-bar.error {
  background: rgba(245, 108, 108, 0.14);
  border: 1px solid rgba(245, 108, 108, 0.4);
  color: #ffb3b3;
}

.alert-bar.warn {
  background: rgba(230, 162, 60, 0.13);
  border: 1px solid rgba(230, 162, 60, 0.4);
  color: #f5cd8d;
}

.alert-bar .alert-msg {
  flex: 1;
}

.alert-bar .close {
  cursor: pointer;
  opacity: 0.7;
}

.alert-bar .close:hover {
  opacity: 1;
}

/* ==================== AGV 卡片 ==================== */
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 14px;
}

.agv-card {
  background: rgba(255, 255, 255, 0.045);
  border: 1px solid rgba(91, 140, 255, 0.16);
  border-radius: 12px;
  padding: 14px 16px;
  transition: border-color 0.25s, box-shadow 0.25s;
}

.agv-card:hover {
  border-color: rgba(91, 140, 255, 0.45);
  box-shadow: 0 6px 20px rgba(4, 14, 38, 0.5);
}

.agv-card.fault {
  border-color: rgba(245, 108, 108, 0.55);
  box-shadow: 0 0 18px rgba(245, 108, 108, 0.15);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.card-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.agv-code {
  font-size: 15px;
  font-weight: 700;
  color: #fff;
}

.agv-name {
  font-size: 12px;
  color: rgba(142, 162, 201, 0.9);
}

.status-badge {
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 20px;
}

/* 电量条 */
.battery-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.battery-row .k {
  font-size: 12px;
  color: rgba(142, 162, 201, 0.9);
}

.battery-track {
  flex: 1;
  height: 10px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.battery-fill {
  height: 100%;
  border-radius: 6px;
  transition: width 0.6s ease, background 0.6s ease;
}

.battery-num {
  font-size: 13px;
  font-weight: 700;
  width: 42px;
  text-align: right;
}

.info-rows {
  border-top: 1px dashed rgba(91, 140, 255, 0.18);
  padding-top: 10px;
  margin-bottom: 12px;
}

.info-row {
  display: flex;
  gap: 10px;
  font-size: 12.5px;
  line-height: 2;
  color: rgba(219, 230, 255, 0.85);
}

.info-row .k {
  color: rgba(142, 162, 201, 0.9);
  width: 60px;
  flex-shrink: 0;
}

.info-row .task-brief {
  color: #8aabff;
}

.info-row .none {
  color: rgba(142, 162, 201, 0.55);
}

/* 异常模拟操作区 */
.ops {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  border-top: 1px dashed rgba(91, 140, 255, 0.18);
  padding-top: 12px;
}

.ops .el-button {
  margin-left: 0;
}

.ops.readonly {
  justify-content: flex-end;
}
</style>
