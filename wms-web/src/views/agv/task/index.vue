<template>
  <div class="page">
    <el-card shadow="never">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="任务号/单据号" clearable style="width: 190px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="query.taskType" placeholder="全部" clearable style="width: 140px">
            <el-option label="入库搬运" value="入库搬运" />
            <el-option label="出库搬运" value="出库搬运" />
            <el-option label="移库搬运" value="移库搬运" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
            <el-option v-for="(s, k) in statusMap" :key="k" :label="s.text" :value="Number(k)" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px; display: flex; align-items: center; gap: 12px">
        <el-button type="primary" :icon="Plus" @click="openCreate">新建搬运任务</el-button>
        <el-tag v-if="live.total" type="info" effect="plain">
          实时：待分配 {{ live.pending }} · 执行中 {{ live.running }} · 紧急 {{ live.urgent }}
        </el-tag>
        <el-tag v-if="agvStore.connected" type="success" effect="plain" size="small">实时推送已连接</el-tag>
        <el-tag v-else type="danger" effect="plain" size="small">实时推送断开</el-tag>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="taskNo" label="任务号" width="165" />
        <el-table-column prop="taskType" label="类型" width="95" align="center" />
        <el-table-column prop="orderNo" label="关联单据" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.orderNo || '手动创建' }}</template>
        </el-table-column>
        <el-table-column label="优先级" width="85" align="center">
          <template #default="{ row }">
            <el-tag :type="row.priority === 1 ? 'danger' : 'info'" size="small" effect="dark">
              {{ row.priority === 1 ? '紧急' : '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fromCode" label="起点" width="110" />
        <el-table-column prop="toCode" label="终点" width="110" />
        <el-table-column prop="agvCode" label="执行 AGV" width="100" align="center" />
        <el-table-column label="状态" width="105" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="95" align="center">
          <template #default="{ row }">{{ durationOf(row) }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="异常信息" min-width="150" show-overflow-tooltip />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openTimeline(row)">时间线</el-button>
            <el-popconfirm v-if="row.status < 6" title="确定取消该任务？取消后车辆立即释放" @confirm="cancel(row)">
              <template #reference>
                <el-button link type="danger">取消</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <!-- 新建任务 -->
    <el-dialog v-model="createVisible" title="新建搬运任务" width="520px" destroy-on-close>
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item label="任务类型" prop="taskType">
          <el-select v-model="createForm.taskType" placeholder="请选择" style="width: 100%">
            <el-option label="入库搬运" value="入库搬运" />
            <el-option label="出库搬运" value="出库搬运" />
            <el-option label="移库搬运" value="移库搬运" />
          </el-select>
        </el-form-item>
        <el-form-item label="起点库位" prop="fromLocationId">
          <el-select v-model="createForm.fromLocationId" placeholder="请选择起点" style="width: 100%" filterable>
            <el-option v-for="l in locations" :key="l.id" :label="l.locationCode" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="终点库位" prop="toLocationId">
          <el-select v-model="createForm.toLocationId" placeholder="请选择终点" style="width: 100%" filterable>
            <el-option v-for="l in locations" :key="l.id" :label="l.locationCode" :value="l.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-radio-group v-model="createForm.priority">
            <el-radio-button :value="0">普通</el-radio-button>
            <el-radio-button :value="1">紧急</el-radio-button>
          </el-radio-group>
          <div class="hint">紧急任务在待分配队列中排在普通任务之前，并被优先派单</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">确定</el-button>
      </template>
    </el-dialog>

    <!-- 状态流转时间线 -->
    <el-dialog v-model="timelineVisible" title="任务状态流转时间线" width="620px">
      <div v-if="current" class="timeline-wrap">
        <div class="tl-head">
          <span class="tl-no">{{ current.taskNo }}</span>
          <el-tag :type="statusMap[current.status]?.type" size="small">{{ statusMap[current.status]?.text }}</el-tag>
        </div>
        <el-timeline>
          <el-timeline-item v-for="s in stagesOf(current)" :key="s.key" :timestamp="s.time" placement="top"
            :type="s.type" :hollow="s.hollow">
            <div class="tl-title">{{ s.title }}</div>
            <div class="tl-desc">{{ s.desc }}</div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { pageAgvTask, createAgvTask, cancelAgvTask, listActiveAgvTask } from '../../../api/agv'
import { listAllLocation } from '../../../api/base'
import { useAgvStore } from '../../../store/agv'

const agvStore = useAgvStore()
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', taskType: null, status: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const locations = ref([])

const createVisible = ref(false)
const saving = ref(false)
const createRef = ref()
const createForm = reactive({ taskType: '', fromLocationId: null, toLocationId: null, priority: 0 })
const createRules = {
  taskType: [{ required: true, message: '请选择任务类型', trigger: 'change' }],
  fromLocationId: [{ required: true, message: '请选择起点库位', trigger: 'change' }],
  toLocationId: [{ required: true, message: '请选择终点库位', trigger: 'change' }]
}

const timelineVisible = ref(false)
const current = ref(null)

// 8 态状态机文案（与后端 AgvEngineService 常量一一对应）
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

/** 实时概览：直接读 WebSocket 推送的任务流，页面停留不动也能看到变化 */
const live = computed(() => {
  const list = Object.values(agvStore.tasks)
  return {
    total: list.length,
    pending: list.filter(t => t.status === 0).length,
    running: list.filter(t => t.status >= 1 && t.status <= 5).length,
    urgent: list.filter(t => t.priority === 1 && t.status < 6).length
  }
})

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageAgvTask(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.taskType = null
  query.status = null
  load(1)
}

function openCreate() {
  Object.assign(createForm, { taskType: '', fromLocationId: null, toLocationId: null, priority: 0 })
  createVisible.value = true
}

async function submitCreate() {
  await createRef.value.validate()
  saving.value = true
  try {
    const res = await createAgvTask(createForm)
    ElMessage.success('任务已创建：' + res.data)
    createVisible.value = false
    load(1)
  } finally {
    saving.value = false
  }
}

async function cancel(row) {
  await cancelAgvTask(row.id)
  ElMessage.success('任务已取消')
  load()
}

function openTimeline(row) {
  current.value = row
  timelineVisible.value = true
}

const fmt = (t) => (t ? String(t).replace('T', ' ').slice(0, 19) : '—')
const diffSec = (a, b) => {
  if (!a || !b) return null
  const d = (new Date(b) - new Date(a)) / 1000
  return Number.isFinite(d) ? Math.max(0, Math.round(d)) : null
}

/** 任务耗时：已完成取「开始→完成」，进行中取「开始→现在」 */
function durationOf(row) {
  const end = row.finishTime || new Date().toISOString().slice(0, 19)
  const s = diffSec(row.startTime, end)
  return s === null ? '—' : s + ' 秒'
}

/**
 * 状态流转时间线：把任务在各阶段的时刻排成一条链，
 * 每个阶段显示停留时长——可直接作为论文"任务生命周期"配图。
 */
function stagesOf(row) {
  const out = []
  const push = (key, title, time, desc, type, hollow = false) => {
    out.push({ key, title, time: time ? fmt(time) : '—', desc, type, hollow })
  }
  push('create', '任务生成', row.createTime, '任务写入待分配队列，等待调度引擎派单', 'info', true)
  const w1 = diffSec(row.createTime, row.startTime)
  push('assign', '分配车辆 / 开始执行', row.startTime,
    w1 === null ? '尚未分配' : `排队等待 ${w1} 秒后由调度引擎分配给执行车辆`, 'primary')
  const w2 = diffSec(row.startTime, row.pickTime)
  push('pick', '到达取货点', row.pickTime,
    w2 === null ? '尚未到达' : `行驶 ${w2} 秒`, 'primary')
  const w3 = diffSec(row.pickTime, row.pickDoneTime)
  push('pickDone', '取货完成', row.pickDoneTime,
    w3 === null ? '尚未取货' : `取货作业耗时 ${w3} 秒`, 'primary')
  const w4 = diffSec(row.pickDoneTime, row.dropTime)
  push('drop', '到达卸货点', row.dropTime,
    w4 === null ? '尚未到达' : `载货行驶 ${w4} 秒`, 'primary')
  const w5 = diffSec(row.dropTime, row.dropDoneTime)
  push('dropDone', '卸货完成', row.dropDoneTime,
    w5 === null ? '尚未卸货' : `卸货作业耗时 ${w5} 秒`, 'primary')
  const w6 = diffSec(row.dropDoneTime, row.finishTime)
  push('finish', '返回待命区 / 任务完成', row.finishTime,
    w6 === null ? '尚未完成' : `返回待命区耗时 ${w6} 秒`, 'success')
  if (row.status === 7) {
    push('cancel', '任务取消', row.updateTime, row.errorMsg || '任务被取消', 'danger')
  }
  return out
}

let timer = null
onMounted(async () => {
  await load()
  const [locRes, taskRes] = await Promise.all([listAllLocation(), listActiveAgvTask()])
  locations.value = locRes.data.filter(l => l.posX != null && l.posY != null)
  agvStore.seedTasks(taskRes.data)
  // 兜底轮询：WebSocket 断线时页面数据不至于完全静止
  timer = setInterval(() => load(), 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
  margin-top: 2px;
}

.tl-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.tl-no {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.4px;
}

.tl-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #303133;
}

.tl-desc {
  font-size: 12.5px;
  color: #909399;
  margin-top: 3px;
  line-height: 1.6;
}
</style>
