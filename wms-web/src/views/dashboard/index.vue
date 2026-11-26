<template>
  <div class="dashboard">
    <!-- 顶部统计卡片 -->
    <el-row :gutter="12">
      <el-col :span="4" v-for="card in cards" :key="card.title">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
            <el-icon :size="20"><component :is="card.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-title">{{ card.title }}</div>
            <div class="stat-num" :style="{ color: card.color }">{{ card.value }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 趋势图 + 品类占比 -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="16">
        <el-card shadow="never">
          <div class="chart-header">
            <span class="chart-title">出入库总量趋势</span>
            <el-radio-group v-model="trendPeriod" size="small" @change="loadTrend">
              <el-radio-button value="day">近30天</el-radio-button>
              <el-radio-button value="week">近12周</el-radio-button>
              <el-radio-button value="month">近12月</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="trendChartRef" class="chart" style="height: 300px"></div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never">
          <div class="chart-header">
            <span class="chart-title">品类占比</span>
            <el-radio-group v-model="ratioType" size="small" @change="renderPie">
              <el-radio-button value="stockRatio">库存</el-radio-button>
              <el-radio-button value="inboundRatio">入库</el-radio-button>
              <el-radio-button value="outboundRatio">出库</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="pieChartRef" class="chart" style="height: 300px"></div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 周转分析 + 盘点分析 + 预警 -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="10">
        <el-card shadow="never">
          <div class="chart-header">
            <span class="chart-title">库存周转分析</span>
            <el-button type="primary" link size="small" @click="doExport" :loading="exporting">导出报表</el-button>
          </div>
          <el-tabs v-model="turnoverTab">
            <el-tab-pane label="高频物料 TOP10" name="high">
              <el-table :data="turnover.highFreq" size="small" border max-height="260">
                <el-table-column prop="materialCode" label="编码" width="90" />
                <el-table-column prop="materialName" label="名称" min-width="110" show-overflow-tooltip />
                <el-table-column prop="outCount" label="出库次数" width="80" sortable />
                <el-table-column prop="outQty" label="出库总量" width="80" sortable />
                <el-table-column prop="currentStock" label="当前库存" width="80" />
              </el-table>
            </el-tab-pane>
            <el-tab-pane :label="`滞销物料（${turnover.slowDays}天无出库）`" name="slow">
              <el-table :data="turnover.slowMoving" size="small" border max-height="260">
                <el-table-column prop="materialCode" label="编码" width="90" />
                <el-table-column prop="materialName" label="名称" min-width="110" show-overflow-tooltip />
                <el-table-column prop="currentStock" label="积压库存" width="80" sortable />
                <el-table-column prop="lastOutTime" label="最近出库" width="110" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
      <el-col :span="7">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">盘点分析</span></div>
          <div class="check-panel">
            <div ref="accuracyChartRef" style="height: 150px; width: 150px"></div>
            <div class="check-nums">
              <div>盘点总次数：<b>{{ check.totalOrders ?? 0 }}</b>（已完成 {{ check.doneOrders ?? 0 }}）</div>
              <div>盈亏明细：<b>{{ check.diffCount ?? 0 }}</b> 条，共 <b>{{ check.diffTotal ?? 0 }}</b> 件</div>
              <div>盘盈 <b class="profit">{{ check.profitCount ?? 0 }}</b> 次 / 盘亏 <b class="loss">{{ check.lossCount ?? 0 }}</b> 次</div>
              <div v-for="(count, wh) in check.countByWarehouse" :key="wh" class="wh-line">{{ wh }}：{{ count }} 次</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="7">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">预警统计</span></div>
          <div class="warn-panel">
            <div class="warn-item" v-for="w in warnCards" :key="w.title" @click="warnDialogType = w.type; warnVisible = true">
              <div class="warn-num" :style="{ color: w.color }">{{ w.count }}</div>
              <div class="warn-title">{{ w.title }}</div>
            </div>
          </div>
          <div class="warn-tip">点击查看明细</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- AGV 搬运统计（v2.0 E3.4） -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="10">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">AGV 任务完成量（近14天）</span></div>
          <div ref="agvDoneChartRef" class="chart" style="height: 260px"></div>
        </el-card>
      </el-col>
      <el-col :span="7">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">AGV 任务类型占比</span></div>
          <div ref="agvTypeChartRef" class="chart" style="height: 260px"></div>
        </el-card>
      </el-col>
      <el-col :span="7">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">AGV 运行质量</span></div>
          <div class="agv-quality">
            <div class="q-item">
              <div class="q-num" style="color:#f56c6c">{{ agvStat.faultCount ?? '-' }}</div>
              <div class="q-title">故障次数</div>
            </div>
            <div class="q-item">
              <div class="q-num" style="color:#e6a23c">{{ agvStat.alertCount ?? '-' }}</div>
              <div class="q-title">告警次数</div>
            </div>
            <div class="q-item">
              <div class="q-num" style="color:#2e6bff">{{ agvStat.avgDurationSec ?? '-' }}<span class="q-unit">秒</span></div>
              <div class="q-title">平均任务耗时</div>
            </div>
            <div class="q-item">
              <div class="q-num" style="color:#22b07d">{{ agvStat.totalDone ?? '-' }}</div>
              <div class="q-title">累计完成任务</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 员工绩效 -->
    <el-row :gutter="12" style="margin-top: 12px">
      <el-col :span="24">
        <el-card shadow="never">
          <div class="chart-header"><span class="chart-title">员工绩效统计</span></div>
          <el-table :data="staff" size="small" border max-height="240">
            <el-table-column prop="operator" label="操作人" width="160" />
            <el-table-column prop="inboundQty" label="入库量（件）" sortable />
            <el-table-column prop="outboundQty" label="出库量（件）" sortable />
            <el-table-column prop="checkCount" label="盘点次数" sortable />
            <el-table-column prop="docCount" label="制单数" sortable />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 预警明细弹窗 -->
    <el-dialog v-model="warnVisible" :title="warnDialogTitle" width="720px" destroy-on-close>
      <el-table :data="warnDialogRows" size="small" border max-height="420">
        <template v-if="warnDialogType === 'expiry'">
          <el-table-column prop="materialCode" label="物料编码" width="100" />
          <el-table-column prop="materialName" label="物料名称" min-width="120" />
          <el-table-column prop="locationCode" label="库位" width="110" />
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column prop="expiryDate" label="到期日" width="110" />
          <el-table-column label="剩余天数" width="90">
            <template #default="{ row }">
              <el-tag :type="row.daysLeft <= 7 ? 'danger' : 'warning'" size="small">{{ row.daysLeft }} 天</el-tag>
            </template>
          </el-table-column>
        </template>
        <template v-else>
          <el-table-column prop="materialCode" label="物料编码" width="100" />
          <el-table-column prop="materialName" label="物料名称" min-width="120" />
          <el-table-column prop="quantity" label="当前总量" width="90" />
          <el-table-column prop="stockLower" label="下限" width="70" />
          <el-table-column prop="stockUpper" label="上限" width="70" />
          <el-table-column label="预警类型" width="90">
            <template #default="{ row }">
              <el-tag :type="row.warnType === '低于下限' ? 'danger' : 'warning'" size="small">{{ row.warnType }}</el-tag>
            </template>
          </el-table-column>
        </template>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import {
  getSummary, getInoutTrend, getCategoryRatio, getCheckAnalysis,
  getTurnover, getStaffPerformance, getWarningReport, exportDashboard
} from '../../api/dashboard.js'
import { getAgvTaskStat } from '../../api/agv.js'
import { downloadBlob } from '../../api/base.js'

const cards = ref([
  { title: '物料总数', value: '-', color: '#2e6bff', bg: 'rgba(46,107,255,.12)', icon: 'Goods' },
  { title: '库存总量', value: '-', color: '#22b07d', bg: 'rgba(34,176,125,.12)', icon: 'Box' },
  { title: '今日入库单', value: '-', color: '#e6a23c', bg: 'rgba(230,162,60,.13)', icon: 'Download' },
  { title: '今日出库单', value: '-', color: '#f56c6c', bg: 'rgba(245,108,108,.12)', icon: 'Upload' },
  { title: '待审核单据', value: '-', color: '#7c5cff', bg: 'rgba(124,92,255,.12)', icon: 'DocumentChecked' },
  { title: '预警总数', value: '-', color: '#b88230', bg: 'rgba(184,130,48,.14)', icon: 'Warning' }
])
const trendPeriod = ref('day')
const ratioType = ref('stockRatio')
const ratioData = ref({})
const turnoverTab = ref('high')
const turnover = ref({ highFreq: [], slowMoving: [], slowDays: 30 })
const check = ref({})
const staff = ref([])
const warning = ref({ shortageCount: 0, overstockCount: 0, expiryCount: 0, stockAlerts: [], expiryAlerts: [] })
const exporting = ref(false)
const warnVisible = ref(false)
const warnDialogType = ref('shortage')

const trendChartRef = ref(null)
const pieChartRef = ref(null)
const accuracyChartRef = ref(null)
const agvDoneChartRef = ref(null)
const agvTypeChartRef = ref(null)
let trendChart = null
let pieChart = null
let accuracyChart = null
let agvDoneChart = null
let agvTypeChart = null
// AGV 运行质量统计（E3.4）
const agvStat = ref({})

const warnCards = computed(() => [
  { title: '库存短缺', count: warning.value.shortageCount, color: '#f56c6c', type: 'shortage' },
  { title: '库存积压', count: warning.value.overstockCount, color: '#e6a23c', type: 'overstock' },
  { title: '临期预警', count: warning.value.expiryCount, color: '#b88230', type: 'expiry' }
])
const warnDialogTitle = computed(() => ({
  shortage: '库存短缺明细', overstock: '库存积压明细', expiry: '临期预警明细'
}[warnDialogType.value]))
const warnDialogRows = computed(() => {
  if (warnDialogType.value === 'expiry') return warning.value.expiryAlerts
  const type = warnDialogType.value === 'shortage' ? '低于下限' : '超上限'
  return warning.value.stockAlerts.filter(r => r.warnType === type)
})

async function loadSummary() {
  const res = await getSummary()
  const d = res.data
  cards.value[0].value = d.materialCount
  cards.value[1].value = d.totalStock
  cards.value[2].value = d.todayInbound
  cards.value[3].value = d.todayOutbound
  cards.value[4].value = d.pendingAudit
  cards.value[5].value = d.warningCount
}

async function loadTrend() {
  const res = await getInoutTrend(trendPeriod.value)
  const d = res.data
  trendChart?.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['入库量', '出库量'], top: 0 },
    grid: { left: 50, right: 20, top: 35, bottom: 30 },
    xAxis: { type: 'category', data: d.labels, axisLabel: { rotate: d.labels.length > 15 ? 45 : 0 } },
    yAxis: { type: 'value', name: '数量' },
    series: [
      { name: '入库量', type: 'line', smooth: true, data: d.inbound, areaStyle: { opacity: 0.12 }, itemStyle: { color: '#2e6bff' }, lineStyle: { width: 2.5 } },
      { name: '出库量', type: 'line', smooth: true, data: d.outbound, areaStyle: { opacity: 0.12 }, itemStyle: { color: '#ff7a45' }, lineStyle: { width: 2.5 } }
    ]
  }, true)
}

async function loadRatio() {
  const res = await getCategoryRatio()
  ratioData.value = res.data
  renderPie()
}

function renderPie() {
  const data = ratioData.value[ratioType.value] || []
  pieChart?.setOption({
    color: ['#2e6bff', '#22b07d', '#e6a23c', '#7c5cff', '#ff7a45', '#40beff', '#f56c6c'],
    tooltip: { trigger: 'item', formatter: '{b}: {c} 件（{d}%）' },
    legend: { bottom: 0, type: 'scroll' },
    series: [{
      type: 'pie', radius: ['40%', '68%'], center: ['50%', '46%'],
      data, label: { formatter: '{b}\n{d}%' },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.3)' } }
    }]
  }, true)
}

async function loadCheck() {
  const res = await getCheckAnalysis()
  check.value = res.data
  const acc = res.data.accuracy ?? 100
  accuracyChart?.setOption({
    series: [{
      type: 'gauge', startAngle: 210, endAngle: -30, min: 0, max: 100,
      radius: '100%', center: ['50%', '60%'],
      progress: { show: true, width: 12, itemStyle: { color: acc >= 95 ? '#67c23a' : acc >= 80 ? '#e6a23c' : '#f56c6c' } },
      axisLine: { lineStyle: { width: 12 } },
      axisTick: { show: false }, splitLine: { show: false }, axisLabel: { show: false },
      pointer: { show: false }, anchor: { show: false },
      title: { show: true, offsetCenter: [0, '30%'], fontSize: 12, color: '#909399' },
      detail: { valueAnimation: true, fontSize: 22, offsetCenter: [0, '-5%'], formatter: '{value}%' },
      data: [{ value: acc, name: '盘点准确率' }]
    }]
  }, true)
}

async function loadTurnover() {
  const res = await getTurnover(30)
  turnover.value = res.data
}

async function loadStaff() {
  const res = await getStaffPerformance()
  staff.value = res.data
}

async function loadWarning() {
  const res = await getWarningReport()
  warning.value = res.data
}

// AGV 搬运统计：近14天完成量柱状图 + 任务类型占比饼图 + 运行质量指标
async function loadAgvStat() {
  const res = await getAgvTaskStat()
  agvStat.value = res.data
  agvDoneChart?.setOption({
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 30, bottom: 28 },
    xAxis: { type: 'category', data: res.data.labels },
    yAxis: { type: 'value', name: '任务数', minInterval: 1 },
    series: [{
      name: '完成任务', type: 'bar', data: res.data.donePerDay, barMaxWidth: 22,
      itemStyle: { color: '#2e6bff', borderRadius: [4, 4, 0, 0] }
    }]
  }, true)
  agvTypeChart?.setOption({
    color: ['#2e6bff', '#22b07d', '#e6a23c', '#7c5cff', '#ff7a45'],
    tooltip: { trigger: 'item', formatter: '{b}: {c} 个（{d}%）' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie', radius: ['38%', '64%'], center: ['50%', '44%'],
      data: res.data.typeRatio, label: { formatter: '{b}\n{d}%' }
    }]
  }, true)
}

async function doExport() {
  exporting.value = true
  try {
    const res = await exportDashboard()
    downloadBlob(res, 'WMS统计报表.xlsx')
    ElMessage.success('报表已导出')
  } finally {
    exporting.value = false
  }
}

function handleResize() {
  trendChart?.resize()
  pieChart?.resize()
  accuracyChart?.resize()
  agvDoneChart?.resize()
  agvTypeChart?.resize()
}

onMounted(() => {
  trendChart = echarts.init(trendChartRef.value)
  pieChart = echarts.init(pieChartRef.value)
  accuracyChart = echarts.init(accuracyChartRef.value)
  agvDoneChart = echarts.init(agvDoneChartRef.value)
  agvTypeChart = echarts.init(agvTypeChartRef.value)
  loadSummary()
  loadTrend()
  loadRatio()
  loadCheck()
  loadTurnover()
  loadStaff()
  loadWarning()
  loadAgvStat()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  trendChart?.dispose()
  pieChart?.dispose()
  accuracyChart?.dispose()
  agvDoneChart?.dispose()
  agvTypeChart?.dispose()
})
</script>

<style scoped>
.dashboard {
  padding: 12px;
}

.stat-card :deep(.el-card__body) {
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
}

.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.stat-title {
  font-size: 12.5px;
  color: var(--wms-text-sub);
}

.stat-num {
  font-size: 24px;
  font-weight: 700;
  margin-top: 4px;
  line-height: 1.1;
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.chart-title {
  font-size: 15px;
  font-weight: 600;
}

.check-panel {
  display: flex;
  align-items: center;
  gap: 16px;
}

.check-nums {
  font-size: 13px;
  line-height: 2;
  color: #606266;
}

.profit {
  color: #67c23a;
}

.loss {
  color: #f56c6c;
}

.wh-line {
  color: #909399;
  font-size: 12px;
}

.warn-panel {
  display: flex;
  justify-content: space-around;
  padding: 24px 0 12px;
}

.warn-item {
  text-align: center;
  cursor: pointer;
  padding: 8px 18px;
  border-radius: 8px;
  transition: background 0.2s;
}

.warn-item:hover {
  background: #f5f7fa;
}

.warn-num {
  font-size: 32px;
  font-weight: 700;
}

.warn-title {
  font-size: 13px;
  color: #606266;
  margin-top: 4px;
}

.warn-tip {
  text-align: center;
  color: #c0c4cc;
  font-size: 12px;
  padding-bottom: 8px;
}

/* AGV 运行质量指标块 */
.agv-quality {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  padding: 14px 6px;
}

.q-item {
  text-align: center;
  background: #f6f9ff;
  border-radius: 10px;
  padding: 16px 0;
}

.q-num {
  font-size: 26px;
  font-weight: 700;
  line-height: 1.1;
}

.q-unit {
  font-size: 12px;
  font-weight: 500;
  margin-left: 2px;
}

.q-title {
  font-size: 12.5px;
  color: var(--wms-text-sub);
  margin-top: 6px;
}
</style>
