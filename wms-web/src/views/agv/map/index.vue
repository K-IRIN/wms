<template>
  <div class="page map-page">
    <div class="map-panel">
      <!-- 顶部：标题 + 实时统计 + 缩放控制 -->
      <div class="map-header">
        <div class="map-title">
          <span class="title-text">AGV 仓库实时地图</span>
          <span class="ws-badge" :class="{ online: agvStore.connected }">
            <i class="dot"></i>{{ agvStore.connected ? '实时连接中' : '连接断开' }}
          </span>
        </div>
        <div class="map-stats">
          <div class="stat-chip"><span class="num">{{ agvList.length }}</span>设备总数</div>
          <div class="stat-chip" style="--c:#4f8dff"><span class="num">{{ countByStatus(1) }}</span>运行中</div>
          <div class="stat-chip" style="--c:#e6a23c"><span class="num">{{ countByStatus(2) }}</span>充电中</div>
          <div class="stat-chip" style="--c:#f56c6c"><span class="num">{{ countByStatus(3) }}</span>故障</div>
          <div class="stat-chip" style="--c:#22b07d"><span class="num">{{ agvStore.activeTasks.length }}</span>活动任务</div>
        </div>
        <div class="map-tools">
          <el-button size="small" type="primary" plain @click="openScreen">独立大屏</el-button>
          <el-checkbox v-model="showPath" class="path-toggle">任务路径</el-checkbox>
          <el-button-group>
            <el-button size="small" :icon="Minus" @click="zoom(-8)" />
            <el-button size="small" disabled>{{ cellSize }}px</el-button>
            <el-button size="small" :icon="Plus" @click="zoom(8)" />
          </el-button-group>
        </div>
      </div>

      <!-- SVG 地图区 -->
      <div class="map-body" v-loading="loading">
        <svg v-if="!loading" class="map-svg" :viewBox="`0 0 ${svgW} ${svgH}`" preserveAspectRatio="xMidYMid meet"
          :style="{ maxWidth: svgW + 'px' }">
          <defs>
            <!-- 背景网格点 -->
            <pattern id="gridDots" :width="cell" :height="cell" patternUnits="userSpaceOnUse">
              <circle :cx="cell / 2" :cy="cell / 2" r="1" fill="rgba(91,140,255,0.18)" />
            </pattern>
            <!-- 路径箭头 -->
            <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M 0 1 L 9 5 L 0 9 z" fill="#7ea6ff" />
            </marker>
          </defs>

          <rect :width="svgW" :height="svgH" fill="url(#gridDots)" rx="10" />

          <!-- 库位格子 -->
          <g v-for="loc in locations" :key="'loc' + loc.id">
            <rect
              :x="cellX(loc.posX)" :y="cellY(loc.posY)" :width="cell" :height="cell" rx="8"
              :fill="locStyle(loc).fill" :stroke="locStyle(loc).stroke" :stroke-width="loc.goodsType === 'AGV虚拟月台' ? 1.6 : 1.2"
              :stroke-dasharray="loc.goodsType === 'AGV虚拟月台' ? '6 4' : 'none'"
            />
            <text :x="cellX(loc.posX) + cell / 2" :y="cellY(loc.posY) + cell / 2 + 4" class="loc-label"
              :class="{ dock: loc.goodsType === 'AGV虚拟月台' }">
              {{ loc.goodsType === 'AGV虚拟月台' ? '月台' : loc.locationCode.split('-').slice(1).join('-') }}
            </text>
          </g>

          <!-- 任务路径（起点→终点，曼哈顿虚线 + 箭头） -->
          <g v-if="showPath">
            <polyline v-for="t in pathTasks" :key="'path' + t.id"
              :points="pathPoints(t)" fill="none" stroke="#7ea6ff" stroke-width="2"
              stroke-dasharray="7 6" class="task-path" marker-end="url(#arrow)" opacity="0.85" />
          </g>

          <!-- 待命区标记（x=0 一列无库位的 AGV 家位） -->
          <g v-for="agv in agvList" :key="'home' + agv.id">
            <rect v-if="!locAt(agv.homeX, agv.homeY)"
              :x="cellX(agv.homeX)" :y="cellY(agv.homeY)" :width="cell" :height="cell" rx="8"
              fill="rgba(142,162,201,0.06)" stroke="rgba(142,162,201,0.35)" stroke-width="1" stroke-dasharray="3 4" />
          </g>

          <!-- AGV 小车：CSS transition 平滑补间移动；等待让行时加呼吸光圈 + 标签（阶段 4.6） -->
          <g v-for="agv in agvList" :key="'agv' + agv.id"
            :class="['agv-node', { selected: selected?.id === agv.id, fault: agv.status === 3, waiting: isWaiting(agv.id), jumping: jumping[agv.id] }]"
            :style="agvStyle(agv)"
            @click="selected = agv">
            <circle r="19" :fill="agvColor(agv)" opacity="0.18" class="agv-halo" />
            <circle r="12" :fill="agvColor(agv)" :stroke="selected?.id === agv.id ? '#fff' : 'rgba(255,255,255,0.65)'"
              :stroke-width="selected?.id === agv.id ? 2 : 1" class="agv-dot" />
            <text y="3.5" class="agv-no">{{ agv.agvCode.replace('AGV-', '') }}</text>
            <text :y="cell / 2 + 11" class="agv-code">{{ agv.agvCode }}</text>
            <g v-if="isWaiting(agv.id)" class="waiting-badge">
              <rect :x="-21" :y="cell / 2 + 15" width="42" height="14" rx="7"
                fill="rgba(230,162,60,0.16)" stroke="rgba(230,162,60,0.65)" stroke-width="0.8" />
              <text :y="cell / 2 + 25" class="agv-waiting-text">等待中</text>
            </g>
          </g>
        </svg>

        <!-- 点选 AGV 后的详情侧栏 -->
        <transition name="slide">
          <div v-if="selected" class="agv-detail">
            <div class="detail-head">
              <span class="detail-title">{{ selected.agvCode }} · {{ selected.agvName }}</span>
              <el-icon class="close" @click="selected = null"><Close /></el-icon>
            </div>
            <div class="detail-row">
              <span class="k">运行状态</span>
              <el-tag :type="statusMap[selected.status]?.type" size="small">{{ statusMap[selected.status]?.text }}</el-tag>
            </div>
            <div class="detail-row"><span class="k">电量</span>
              <el-progress :percentage="selected.battery" :stroke-width="12" :color="batteryColor" style="flex:1" />
            </div>
            <div class="detail-row"><span class="k">当前坐标</span><span>({{ selected.posX }}, {{ selected.posY }})</span></div>
            <div class="detail-row"><span class="k">待命区</span><span>({{ selected.homeX }}, {{ selected.homeY }})</span></div>
            <div class="detail-row"><span class="k">速度</span><span>{{ selected.speed }} 格/秒</span></div>
            <div class="detail-row" v-if="isWaiting(selected.id)">
              <span class="k">等待原因</span>
              <span class="wait-reason">{{ waitingOf(selected.id)?.reason || '等待让行' }}</span>
            </div>
            <div class="detail-task">
              <span class="k">当前任务</span>
              <template v-if="selectedTask">
                <div class="task-no">{{ selectedTask.taskNo }}</div>
                <div class="task-line">{{ selectedTask.taskType }} · {{ taskStatusText(selectedTask.status) }}</div>
                <div class="task-line">{{ selectedTask.fromCode || locName(selectedTask.fromLocationId) }} →
                  {{ selectedTask.toCode || locName(selectedTask.toLocationId) }}</div>
              </template>
              <span v-else class="none">暂无任务</span>
            </div>
          </div>
        </transition>
      </div>

      <!-- 图例 -->
      <div class="map-legend">
        <span class="legend-group">库位：</span>
        <span class="legend-item"><i style="background:rgba(46,107,255,.5)"></i>空闲</span>
        <span class="legend-item"><i style="background:rgba(34,176,125,.6)"></i>占用</span>
        <span class="legend-item"><i style="background:rgba(245,108,108,.55)"></i>禁用</span>
        <span class="legend-item"><i style="background:rgba(230,162,60,.55)"></i>月台</span>
        <span class="legend-split"></span>
        <span class="legend-group">AGV：</span>
        <span class="legend-item"><i style="background:#22b07d"></i>空闲</span>
        <span class="legend-item"><i style="background:#4f8dff"></i>运行中</span>
        <span class="legend-item"><i style="background:#e6a23c"></i>充电中</span>
        <span class="legend-item"><i style="background:#f56c6c"></i>故障</span>
        <span class="legend-item"><i class="wait-dot"></i>等待让行</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { Close, Minus, Plus } from '@element-plus/icons-vue'
import { useAgvStore } from '../../../store/agv'
import { listAgvDevice, listActiveAgvTask } from '../../../api/agv'
import { listAllLocation } from '../../../api/base'

const agvStore = useAgvStore()
const loading = ref(false)
const locations = ref([])      // 全部库位（含坐标）
const selected = ref(null)     // 当前点选的 AGV
const showPath = ref(true)     // 是否显示任务路径
// 格子边长：支持 ?cell=84（独立大屏用更大格子），缺省 64，范围 40~110
const route = useRoute()
const initialCell = Number(route.query.cell)
const cellSize = ref(Number.isFinite(initialCell) ? Math.min(110, Math.max(40, initialCell)) : 64)
const GAP = 10                 // 格子间距
const PAD = 26                 // 画布内边距

// 运行状态：0空闲 1运行中 2充电中 3故障
const statusMap = {
  0: { text: '空闲', type: 'success' },
  1: { text: '运行中', type: 'primary' },
  2: { text: '充电中', type: 'warning' },
  3: { text: '故障', type: 'danger' }
}
const agvList = computed(() => agvStore.agvList)
// 阶段 4.6：等待让行状态（WS waiting 消息维护）
const isWaiting = (id) => agvStore.isWaiting(id)
const waitingOf = (id) => agvStore.waiting[id]

// ==================== 画布几何计算 ====================
const cell = computed(() => cellSize.value)
const step = computed(() => cell.value + GAP)
// 网格范围：库位坐标 + AGV 当前/待命坐标取最大
const maxX = computed(() => Math.max(2, ...locations.value.map(l => l.posX ?? 0), ...agvList.value.map(a => Math.max(a.posX ?? 0, a.homeX ?? 0))))
const maxY = computed(() => Math.max(2, ...locations.value.map(l => l.posY ?? 0), ...agvList.value.map(a => Math.max(a.posY ?? 0, a.homeY ?? 0))))
const svgW = computed(() => PAD * 2 + (maxX.value + 1) * step.value - GAP)
const svgH = computed(() => PAD * 2 + (maxY.value + 1) * step.value - GAP + 16) // 底部留 AGV 编号文字空间

const cellX = (x) => PAD + (x ?? 0) * step.value
const cellY = (y) => PAD + (y ?? 0) * step.value
const centerX = (x) => cellX(x) + cell.value / 2
const centerY = (y) => cellY(y) + cell.value / 2

// ==================== 样式映射 ====================
// 库位：0空闲 1占用 2禁用；月台（A01-DOCK）单独虚线高亮
function locStyle(loc) {
  if (loc.goodsType === 'AGV虚拟月台') return { fill: 'rgba(230,162,60,0.16)', stroke: '#e6a23c' }
  return {
    0: { fill: 'rgba(46,107,255,0.16)', stroke: 'rgba(91,140,255,0.75)' },
    1: { fill: 'rgba(34,176,125,0.20)', stroke: 'rgba(34,176,125,0.8)' },
    2: { fill: 'rgba(245,108,108,0.16)', stroke: 'rgba(245,108,108,0.7)' }
  }[loc.status] || { fill: 'rgba(142,162,201,0.1)', stroke: 'rgba(142,162,201,0.4)' }
}
function agvColor(agv) {
  if (agv.enabled === 0) return '#6b7a99'
  return { 0: '#22b07d', 1: '#4f8dff', 2: '#e6a23c', 3: '#f56c6c' }[agv.status] || '#6b7a99'
}
const batteryColor = (b) => (b > 50 ? '#67c23a' : b > 20 ? '#e6a23c' : '#f56c6c')
const countByStatus = (s) => agvList.value.filter(a => a.status === s).length

// ==================== 任务路径 ====================
const locMap = computed(() => Object.fromEntries(locations.value.map(l => [l.id, l])))
const locAt = (x, y) => locations.value.find(l => l.posX === x && l.posY === y)
const locName = (id) => locMap.value[id]?.locationCode || '月台'
// 有执行 AGV 且能解析出起终点坐标的进行中任务
const pathTasks = computed(() =>
  agvStore.activeTasks.filter(t => t.agvId != null && (t.status === 1 || t.status === 3 || t.status === 5))
)
// 曼哈顿折线：先横后纵（与调度引擎路径一致）
function pathPoints(t) {
  const from = locMap.value[t.fromLocationId] || { posX: 0, posY: 0 }
  const to = locMap.value[t.toLocationId] || { posX: 0, posY: 0 }
  const x1 = centerX(from.posX), y1 = centerY(from.posY)
  const x2 = centerX(to.posX), y2 = centerY(to.posY)
  return `${x1},${y1} ${x2},${y1} ${x2},${y2}`
}
const taskStatusText = (s) => ({ 0: '待分配', 1: '前往取货', 2: '取货中', 3: '前往卸货', 4: '卸货中', 5: '返回待命区' }[s] || '')
const selectedTask = computed(() => (selected.value ? agvStore.taskOfAgv(selected.value.id) : null))

function zoom(delta) {
  cellSize.value = Math.min(110, Math.max(40, cellSize.value + delta))
}

function openScreen() {
  window.open('/screen/agv-map?cell=84', '_blank')
}

// ==================== AGV 移动动画（阶段 9.5：按格走，不斜走） ====================
// 引擎 1 秒推一次坐标：单格移动用 0.9s 线性补间；跨多格（死锁让行/回收归位/重置）
// 一律直接落位并淡入——否则浏览器会把 (x,y)→(x+1,y+1) 插值成一条斜线。
const prevPos = {}
const jumping = reactive({})

function agvStyle(agv) {
  const p = prevPos[agv.id]
  const dist = p ? Math.abs(p.x - (agv.posX ?? 0)) + Math.abs(p.y - (agv.posY ?? 0)) : 0
  return {
    transform: `translate(${centerX(agv.posX)}px, ${centerY(agv.posY)}px)`,
    transition: dist === 1 ? 'transform 0.9s linear' : 'none'
  }
}

watch(
  () => agvList.value.map((a) => `${a.id}:${a.posX},${a.posY}`).join('|'),
  () => {
    const snaps = []
    for (const a of agvList.value) {
      const p = prevPos[a.id]
      if (p && Math.abs(p.x - (a.posX ?? 0)) + Math.abs(p.y - (a.posY ?? 0)) > 1) snaps.push(a.id)
    }
    if (snaps.length) {
      for (const id of snaps) jumping[id] = true
      setTimeout(() => { for (const id of snaps) jumping[id] = false }, 240)
    }
    // DOM 打完补丁后再记坐标，保证下一次渲染比较的是"上一帧位置"
    nextTick(() => {
      for (const a of agvList.value) prevPos[a.id] = { x: a.posX ?? 0, y: a.posY ?? 0 }
    })
  }
)

// 初始数据：HTTP 全量拉取灌入 store，之后由 WebSocket 增量实时刷新
onMounted(async () => {
  loading.value = true
  try {
    const [locRes, agvRes, taskRes] = await Promise.all([listAllLocation(), listAgvDevice(), listActiveAgvTask()])
    locations.value = locRes.data.filter(l => l.posX != null && l.posY != null)
    agvStore.seedAgvs(agvRes.data)
    agvStore.seedTasks(taskRes.data)
    await nextTick()
    for (const a of agvList.value) prevPos[a.id] = { x: a.posX ?? 0, y: a.posY ?? 0 }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ==================== 深色科技风地图面板 ==================== */
.map-panel {
  border-radius: var(--wms-card-radius);
  background: linear-gradient(160deg, #0b1a36 0%, #10264f 60%, #133058 100%);
  box-shadow: var(--wms-card-shadow);
  padding: 16px 18px 12px;
  color: #dbe6ff;
}

.map-header {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.map-title {
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

.map-stats {
  display: flex;
  gap: 10px;
  flex: 1;
  flex-wrap: wrap;
}

.stat-chip {
  --c: #5b8cff;
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 12px;
  color: rgba(219, 230, 255, 0.75);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.07);
  padding: 5px 12px;
  border-radius: 8px;
}

.stat-chip .num {
  font-size: 16px;
  font-weight: 700;
  color: var(--c);
}

.map-tools {
  display: flex;
  align-items: center;
  gap: 14px;
}

.path-toggle :deep(.el-checkbox__label) {
  color: rgba(219, 230, 255, 0.8);
  font-size: 12.5px;
}

/* ==================== SVG 画布 ==================== */
.map-body {
  position: relative;
  border-radius: 10px;
  background: rgba(4, 12, 30, 0.55);
  border: 1px solid rgba(91, 140, 255, 0.12);
  min-height: 380px;
  padding: 6px;
}

.map-svg {
  width: 100%;
  /* 不放大：viewBox 已是格子自然尺寸，max-width 限制后地图按 1:1 渲染，
     宽屏下不会被拉成两倍导致要滚动才能看全 */
  display: block;
  margin: 0 auto;
}

.loc-label {
  font-size: 11px;
  fill: rgba(219, 230, 255, 0.82);
  text-anchor: middle;
  pointer-events: none;
}

.loc-label.dock {
  fill: #f0c578;
  font-weight: 700;
}

/* 任务路径：蚂蚁线动画 */
.task-path {
  animation: dashMove 1.2s linear infinite;
}

@keyframes dashMove {
  to { stroke-dashoffset: -13; }
}

/* AGV 节点：单格移动由内联 transition 补间；跨格跳变直接落位并淡入（阶段 9.5） */
.agv-node {
  transition: opacity 0.2s ease;
  cursor: pointer;
}

.agv-node.jumping {
  animation: agvFadeIn 0.24s ease;
}

@keyframes agvFadeIn {
  from { opacity: 0.35; }
  to { opacity: 1; }
}

.agv-node .agv-halo {
  transition: r 0.2s;
}

.agv-node:hover .agv-halo,
.agv-node.selected .agv-halo {
  r: 23;
}

/* 故障 AGV 光圈闪烁告警 */
.agv-node.fault .agv-halo {
  animation: faultPulse 1s infinite;
}

@keyframes faultPulse {
  50% { opacity: 0.05; }
}

/* 等待让行：橙色呼吸光圈 + "等待中"标签（阶段 4.6） */
.agv-node.waiting .agv-halo {
  fill: #e6a23c;
  animation: waitPulse 1.5s ease-in-out infinite;
}

@keyframes waitPulse {
  0%, 100% { r: 19; opacity: 0.16; }
  50% { r: 27; opacity: 0.45; }
}

.agv-waiting-text {
  font-size: 9.5px;
  font-weight: 700;
  fill: #f0c578;
  text-anchor: middle;
  pointer-events: none;
}

.wait-reason {
  color: #f0c578;
  font-size: 12px;
}

.legend-item .wait-dot {
  background: #e6a23c;
  box-shadow: 0 0 6px rgba(230, 162, 60, 0.9);
}

.agv-no {
  font-size: 9.5px;
  font-weight: 700;
  fill: #fff;
  text-anchor: middle;
  pointer-events: none;
}

.agv-code {
  font-size: 10.5px;
  fill: rgba(219, 230, 255, 0.75);
  text-anchor: middle;
  pointer-events: none;
}

/* ==================== AGV 详情侧栏 ==================== */
.agv-detail {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 264px;
  background: rgba(10, 24, 52, 0.92);
  backdrop-filter: blur(6px);
  border: 1px solid rgba(91, 140, 255, 0.25);
  border-radius: 10px;
  padding: 14px 16px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.4);
}

.detail-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.detail-title {
  font-size: 14px;
  font-weight: 700;
  color: #fff;
}

.detail-head .close {
  cursor: pointer;
  color: rgba(219, 230, 255, 0.6);
}

.detail-head .close:hover {
  color: #fff;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  margin-bottom: 10px;
  color: rgba(219, 230, 255, 0.85);
}

.detail-row .k,
.detail-task .k {
  color: rgba(142, 162, 201, 0.9);
  width: 60px;
  flex-shrink: 0;
  font-size: 12px;
}

.detail-task {
  border-top: 1px dashed rgba(91, 140, 255, 0.2);
  padding-top: 10px;
  font-size: 12.5px;
  color: rgba(219, 230, 255, 0.85);
}

.task-no {
  font-weight: 700;
  color: #8aabff;
  margin: 6px 0 4px;
}

.task-line {
  color: rgba(219, 230, 255, 0.75);
  font-size: 12px;
  line-height: 1.7;
}

.detail-task .none {
  color: rgba(142, 162, 201, 0.6);
}

.slide-enter-active,
.slide-leave-active {
  transition: all 0.25s ease;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateX(16px);
}

/* ==================== 图例 ==================== */
.map-legend {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  padding: 12px 4px 2px;
  font-size: 12px;
  color: rgba(219, 230, 255, 0.7);
}

.legend-group {
  color: rgba(142, 162, 201, 0.9);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.legend-item i {
  width: 10px;
  height: 10px;
  border-radius: 3px;
  display: inline-block;
}

.legend-split {
  width: 1px;
  height: 14px;
  background: rgba(219, 230, 255, 0.2);
}
</style>
