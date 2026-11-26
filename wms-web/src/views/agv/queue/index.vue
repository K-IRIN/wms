<template>
  <div class="page">
    <el-card shadow="never">
      <!-- 队列概览 -->
      <div class="queue-head">
        <div class="qh-left">
          <span class="qh-title">待分配队列</span>
          <span class="qh-sub">按「紧急优先 → 创建时间」排序，调度引擎每 1 秒扫描一次并派单</span>
        </div>
        <div class="qh-stats">
          <div class="qh-chip"><span class="num">{{ pending.length }}</span>待分配</div>
          <div class="qh-chip urgent"><span class="num">{{ urgentCount }}</span>紧急</div>
          <div class="qh-chip"><span class="num">{{ availableCount }}</span>可用车辆</div>
          <div class="qh-chip"><span class="num">{{ runningCount }}</span>执行中</div>
        </div>
      </div>

      <el-alert v-if="pending.length === 0" type="info" :closable="false" show-icon
        title="当前没有待分配任务" description="新建搬运任务后，任务会立即出现在这里，并被调度引擎优先分配给最近的空闲车辆。" />

      <el-table v-else :data="pending" v-loading="loading" border row-key="id">
        <el-table-column label="排队位次" width="90" align="center">
          <template #default="{ $index }">
            <span class="rank" :class="{ top: $index === 0 }">{{ $index + 1 }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="taskNo" label="任务号" width="165" />
        <el-table-column prop="taskType" label="类型" width="95" align="center" />
        <el-table-column label="优先级" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.priority === 1 ? 'danger' : 'info'" size="small" effect="dark">
              {{ row.priority === 1 ? '紧急' : '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fromCode" label="起点" width="120" />
        <el-table-column prop="toCode" label="终点" width="120" />
        <el-table-column label="等待时长" width="110" align="center">
          <template #default="{ row }">
            <span class="wait" :class="{ long: waitSec(row) >= 30 }">{{ waitSec(row) }} 秒</span>
          </template>
        </el-table-column>
        <el-table-column label="最近车辆距离" width="130" align="center">
          <template #default="{ row }">{{ nearest(row) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.priority !== 1" link type="danger" @click="promote(row)">紧急插队</el-button>
            <el-button v-else link type="info" @click="demote(row)">取消紧急</el-button>
            <el-popconfirm title="确定取消该任务？" @confirm="cancel(row)">
              <template #reference>
                <el-button link type="warning">取消</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <div class="queue-foot">
        <el-tag v-if="agvStore.connected" type="success" size="small" effect="plain">实时推送已连接</el-tag>
        <el-tag v-else type="danger" size="small" effect="plain">实时推送断开</el-tag>
        <span class="foot-hint">队列每 3 秒自动刷新；紧急插队后下一次调度周期即优先派单</span>
      </div>
    </el-card>

    <!-- 执行中任务（上下文参考） -->
    <el-card shadow="never" style="margin-top: 14px">
      <template #header>
        <div class="card-head">
          <span>执行中的任务</span>
          <span class="card-sub">已派单车辆正在执行，状态随调度引擎每秒推进</span>
        </div>
      </template>
      <el-table :data="running" border size="small">
        <el-table-column prop="taskNo" label="任务号" width="165" />
        <el-table-column prop="agvCode" label="执行 AGV" width="100" align="center" />
        <el-table-column prop="fromCode" label="起点" width="120" />
        <el-table-column prop="toCode" label="终点" width="120" />
        <el-table-column label="当前状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="已耗时" width="100" align="center">
          <template #default="{ row }">{{ elapsed(row) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listActiveAgvTask, listAgvDevice, updateAgvTaskPriority, cancelAgvTask } from '../../../api/agv'
import { listAllLocation } from '../../../api/base'
import { useAgvStore } from '../../../store/agv'

const agvStore = useAgvStore()
const loading = ref(false)
const tasks = ref([])
const devices = ref([])
const locations = ref([])

const statusMap = {
  0: { text: '待分配', type: 'info' },
  1: { text: '前往取货', type: 'primary' },
  2: { text: '取货中', type: 'primary' },
  3: { text: '前往卸货', type: 'primary' },
  4: { text: '卸货中', type: 'primary' },
  5: { text: '返回待命区', type: 'primary' },
  6: { text: '已完成', type: 'success' },
  7: { text: '已取消', type: 'danger' }
}

/** 待分配队列：后端已按 priority desc, id asc 排好序，这里只做状态过滤 */
const pending = computed(() => tasks.value.filter(t => t.status === 0))
const running = computed(() => tasks.value.filter(t => t.status >= 1 && t.status <= 5))
const urgentCount = computed(() => pending.value.filter(t => t.priority === 1).length)
/** 可用车辆：空闲 + 已启用 + 电量高于派单阈值（后端 MIN_ASSIGN_BATTERY = 20） */
const availableCount = computed(() =>
  devices.value.filter(d => d.status === 0 && d.enabled === 1 && d.battery > 20).length
)
const runningCount = computed(() => running.value.length)

const waitSec = (row) => {
  if (!row.createTime) return 0
  return Math.max(0, Math.round((Date.now() - new Date(row.createTime).getTime()) / 1000))
}

const elapsed = (row) => {
  if (!row.startTime) return '—'
  const end = row.finishTime || new Date().toISOString().slice(0, 19)
  const s = Math.max(0, Math.round((new Date(end) - new Date(row.startTime)) / 1000))
  return s + ' 秒'
}

/** 最近空闲车辆到取货点的曼哈顿距离（与后端分配策略一致） */
function nearest(row) {
  const target = coordOf(row.fromLocationId)
  if (!target) return '—'
  const free = devices.value.filter(d => d.status === 0 && d.enabled === 1 && d.battery > 20)
  if (!free.length) return '无可用车辆'
  const d = Math.min(...free.map(a => Math.abs((a.posX ?? 0) - target.x) + Math.abs((a.posY ?? 0) - target.y)))
  return d + ' 格'
}

/** 起终点坐标：从库位表换算，用于估算最近空闲车辆的曼哈顿距离 */
function coordOf(locationId) {
  if (locationId == null) return { x: 0, y: 0 }
  const loc = locations.value.find(l => l.id === locationId)
  if (loc && loc.posX != null) return { x: loc.posX, y: loc.posY }
  return null
}

async function load() {
  loading.value = true
  try {
    const [taskRes, devRes, locRes] = await Promise.all([
      listActiveAgvTask(), listAgvDevice(), listAllLocation()
    ])
    tasks.value = taskRes.data
    devices.value = devRes.data
    locations.value = locRes.data
    agvStore.seedTasks(taskRes.data)
    agvStore.seedAgvs(devRes.data)
  } finally {
    loading.value = false
  }
}

async function promote(row) {
  await updateAgvTaskPriority(row.id, 1)
  ElMessage.success(`任务「${row.taskNo}」已置为紧急，将插到队首`)
  load()
}

async function demote(row) {
  await updateAgvTaskPriority(row.id, 0)
  ElMessage.success(`任务「${row.taskNo}」已恢复为普通`)
  load()
}

async function cancel(row) {
  await cancelAgvTask(row.id)
  ElMessage.success('任务已取消')
  load()
}

let timer = null
onMounted(async () => {
  await load()
  timer = setInterval(load, 3000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.queue-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.qh-title {
  font-size: 16px;
  font-weight: 700;
  color: #303133;
}

.qh-sub {
  font-size: 12.5px;
  color: #909399;
  margin-left: 10px;
}

.qh-stats {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.qh-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: #606266;
  background: #f4f4f5;
  border-radius: 8px;
  padding: 5px 12px;
}

.qh-chip .num {
  font-size: 16px;
  font-weight: 700;
  color: #409eff;
}

.qh-chip.urgent .num {
  color: #f56c6c;
}

.rank {
  display: inline-block;
  width: 22px;
  height: 22px;
  line-height: 22px;
  border-radius: 50%;
  background: #f4f4f5;
  color: #909399;
  font-size: 12px;
  font-weight: 700;
  text-align: center;
}

.rank.top {
  background: #f56c6c;
  color: #fff;
}

.wait {
  font-variant-numeric: tabular-nums;
  color: #409eff;
  font-weight: 600;
}

.wait.long {
  color: #f56c6c;
}

.queue-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.foot-hint {
  font-size: 12px;
  color: #909399;
}

.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-weight: 600;
}

.card-sub {
  font-size: 12px;
  font-weight: 400;
  color: #909399;
}
</style>
