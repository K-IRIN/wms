<template>
  <div class="page">
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- ========== Tab1 库存查询 ========== -->
        <el-tab-pane label="库存查询" name="stock">
          <el-form :inline="true" @submit.prevent>
            <el-form-item label="维度">
              <el-radio-group v-model="dim" @change="loadStock">
                <el-radio-button value="summary">按物料汇总</el-radio-button>
                <el-radio-button value="location">按库位明细</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="仓库">
              <el-select v-model="stockQuery.warehouseId" placeholder="全部" clearable style="width: 130px"
                @change="loadStock">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="关键字">
              <el-input v-model="stockQuery.keyword" placeholder="物料编号/名称" clearable style="width: 160px"
                @keyup.enter="loadStock" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadStock">查询</el-button>
            </el-form-item>
          </el-form>

          <el-table :data="stockRows" v-loading="stockLoading" border>
            <el-table-column v-if="dim === 'location'" prop="warehouseName" label="仓库" width="100" />
            <el-table-column v-if="dim === 'location'" prop="locationCode" label="库位" width="110">
              <template #default="{ row }">
                <el-tag size="small" type="warning">{{ row.locationCode }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="materialCode" label="物料编号" width="110" />
            <el-table-column prop="materialName" label="物料名称" min-width="130" />
            <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="65" />
            <el-table-column prop="quantity" label="库存数量" width="95" align="right" />
            <el-table-column prop="frozenQty" label="冻结" width="80" align="right">
              <template #default="{ row }">
                <span :style="row.frozenQty > 0 ? 'color:#e6a23c;font-weight:bold' : ''">{{ row.frozenQty }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="available" label="可用" width="90" align="right" />
            <el-table-column label="预警" width="100" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.warnType" type="danger" size="small">{{ row.warnType }}</el-tag>
                <span v-else style="color: #67c23a">正常</span>
              </template>
            </el-table-column>
            <el-table-column v-if="dim === 'location'" label="操作" width="140" align="center">
              <template #default="{ row }">
                <el-button link type="warning" size="small" @click="openFreeze(row, false)">冻结</el-button>
                <el-button link type="success" size="small" :disabled="!row.frozenQty"
                  @click="openFreeze(row, true)">解冻</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== Tab2 变动流水 ========== -->
        <el-tab-pane label="变动流水" name="logs">
          <el-form :inline="true" @submit.prevent>
            <el-form-item label="类型">
              <el-select v-model="logQuery.changeType" placeholder="全部" clearable style="width: 110px">
                <el-option v-for="t in ['入库', '出库', '移库', '盘点', '冻结', '解冻']" :key="t" :label="t" :value="t" />
              </el-select>
            </el-form-item>
            <el-form-item label="关联单号">
              <el-input v-model="logQuery.orderNo" placeholder="单号模糊查询" clearable style="width: 160px"
                @keyup.enter="loadLogs" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadLogs">查询</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="logRows" v-loading="logLoading" border size="small">
            <el-table-column prop="createTime" label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column prop="changeType" label="类型" width="75" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="{ 入库: 'success', 出库: 'danger' }[row.changeType] || 'info'">
                  {{ row.changeType }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="materialCode" label="物料编号" width="105" />
            <el-table-column prop="materialName" label="物料名称" min-width="120" />
            <el-table-column prop="locationCode" label="库位" width="105" />
            <el-table-column prop="changeQty" label="变动" width="80" align="right">
              <template #default="{ row }">
                <span :style="{ color: row.changeQty > 0 ? '#67c23a' : '#f56c6c', fontWeight: 'bold' }">
                  {{ row.changeQty > 0 ? '+' : '' }}{{ row.changeQty }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="beforeQty" label="变动前" width="75" align="right" />
            <el-table-column prop="afterQty" label="变动后" width="75" align="right" />
            <el-table-column prop="orderNo" label="关联单号" width="150" />
            <el-table-column prop="operator" label="操作人" width="100" />
          </el-table>
        </el-tab-pane>

        <!-- ========== Tab3 移库管理 ========== -->
        <el-tab-pane label="移库管理" name="move">
          <div style="margin-bottom: 12px">
            <el-button type="primary" @click="openMove">新建移库单</el-button>
          </div>
          <el-table :data="moveRows" v-loading="moveLoading" border>
            <el-table-column prop="orderNo" label="移库单号" width="150" />
            <el-table-column label="物料" min-width="140">
              <template #default="{ row }">{{ materialLabel(row.materialId) }}</template>
            </el-table-column>
            <el-table-column label="源库位 → 目标库位" min-width="200" align="center">
              <template #default="{ row }">
                <el-tag size="small">{{ locationLabel(row.fromLocationId) }}</el-tag>
                <span style="margin: 0 8px">→</span>
                <el-tag size="small" type="success">{{ locationLabel(row.toLocationId) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="quantity" label="数量" width="80" align="right" />
            <el-table-column label="状态" width="90" align="center">
              <template #default>
                <el-tag type="success" size="small">已完成</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operator" label="操作人" width="100" />
            <el-table-column prop="createTime" label="时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="110" show-overflow-tooltip />
          </el-table>
          <el-pagination style="margin-top: 12px; justify-content: flex-end" background
            layout="total, prev, pager, next" :total="moveTotal" v-model:current-page="moveQuery.pageNum"
            :page-size="moveQuery.pageSize" @current-change="loadMoves" />
        </el-tab-pane>

        <!-- ========== Tab4 预警中心 ========== -->
        <el-tab-pane name="warn">
          <template #label>
            预警中心
            <el-badge v-if="warnCount" :value="warnCount" type="danger" style="margin-left: 4px" />
          </template>
          <el-row :gutter="12">
            <el-col :span="12">
              <h4 style="margin: 4px 0 10px">📦 库存上下限预警（{{ warnings.stockAlerts.length }}）</h4>
              <el-table :data="warnings.stockAlerts" border size="small">
                <el-table-column prop="materialCode" label="编号" width="100" />
                <el-table-column prop="materialName" label="物料" min-width="110" />
                <el-table-column prop="quantity" label="当前库存" width="90" align="right" />
                <el-table-column label="范围" width="130" align="center">
                  <template #default="{ row }">{{ row.stockLower ?? '—' }} ~ {{ row.stockUpper ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="预警" width="95" align="center">
                  <template #default="{ row }">
                    <el-tag type="danger" size="small">{{ row.warnType }}</el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-col>
            <el-col :span="12">
              <h4 style="margin: 4px 0 10px">⏰ 临期物料预警（30 天内到期，{{ warnings.expiryAlerts.length }}）</h4>
              <el-table :data="warnings.expiryAlerts" border size="small">
                <el-table-column prop="materialCode" label="编号" width="100" />
                <el-table-column prop="materialName" label="物料" min-width="100" />
                <el-table-column prop="locationCode" label="库位" width="100" />
                <el-table-column prop="quantity" label="数量" width="70" align="right" />
                <el-table-column prop="expiryDate" label="到期日" width="105" />
                <el-table-column label="剩余" width="90" align="center">
                  <template #default="{ row }">
                    <el-tag :type="row.daysLeft <= 0 ? 'danger' : 'warning'" size="small">
                      {{ row.daysLeft <= 0 ? '已过期' : row.daysLeft + ' 天' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>
            </el-col>
          </el-row>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 移库弹窗 -->
    <el-dialog v-model="moveVisible" title="新建移库单" width="480px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="物料" required>
          <el-select v-model="moveForm.materialId" filterable style="width: 100%" @change="onMoveMaterial">
            <el-option v-for="m in inStockMaterials" :key="m.materialId" :value="m.materialId"
              :label="`${m.materialCode} ${m.materialName}（可用 ${m.available}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="源库位" required>
          <el-select v-model="moveForm.fromLocationId" style="width: 100%">
            <el-option v-for="l in fromLocations" :key="l.locationId" :value="l.locationId"
              :label="`${l.locationCode}（可用 ${l.available}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标库位" required>
          <el-select v-model="moveForm.toLocationId" filterable style="width: 100%">
            <el-option v-for="l in freeLocations" :key="l.id" :value="l.id"
              :label="`${l.locationCode}（${l.status === 0 ? '空闲' : '占用中'}）`" />
          </el-select>
        </el-form-item>
        <el-form-item label="移库数量" required>
          <el-input-number v-model="moveForm.quantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="moveForm.remark" maxlength="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doMove">确认移库</el-button>
      </template>
    </el-dialog>

    <!-- 冻结/解冻弹窗 -->
    <el-dialog v-model="freezeVisible" :title="freezeForm.unfreeze ? '解冻库存' : '冻结库存'" width="380px" destroy-on-close>
      <p style="margin-top: 0">
        {{ freezeRow?.materialName }} @ {{ freezeRow?.locationCode }}：
        {{ freezeForm.unfreeze ? `当前冻结 ${freezeRow?.frozenQty}` : `当前可用 ${freezeRow?.available}` }}
      </p>
      <el-input-number v-model="freezeForm.qty" :min="1"
        :max="freezeForm.unfreeze ? freezeRow?.frozenQty : freezeRow?.available" style="width: 100%" />
      <template #footer>
        <el-button @click="freezeVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="doFreeze">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getInventorySummary, getInventoryByLocation, getInventoryLogs,
  moveInventory, pageMoveOrder, getWarnings, freezeInventory, unfreezeInventory
} from '../../api/inventory'
import { listAllWarehouse, getLocationTree } from '../../api/base'
import { formatTime } from '../../utils/format.js'
import request from '../../utils/request'

const tab = ref('stock')
const dim = ref('summary')
const warehouses = ref([])
const materials = ref([])
const locationMap = ref({})

// ==================== 库存查询 ====================
const stockQuery = reactive({ keyword: '', warehouseId: null })
const stockRows = ref([])
const stockLoading = ref(false)

async function loadStock() {
  stockLoading.value = true
  try {
    const fn = dim.value === 'summary' ? getInventorySummary : getInventoryByLocation
    const res = await fn(stockQuery)
    stockRows.value = res.data
  } finally {
    stockLoading.value = false
  }
}

// ==================== 流水 ====================
const logQuery = reactive({ changeType: null, orderNo: '' })
const logRows = ref([])
const logLoading = ref(false)

async function loadLogs() {
  logLoading.value = true
  try {
    const res = await getInventoryLogs(logQuery)
    logRows.value = res.data
  } finally {
    logLoading.value = false
  }
}

// ==================== 移库 ====================
const moveQuery = reactive({ pageNum: 1, pageSize: 10 })
const moveRows = ref([])
const moveTotal = ref(0)
const moveLoading = ref(false)
const moveVisible = ref(false)
const acting = ref(false)
const moveForm = reactive({ materialId: null, fromLocationId: null, toLocationId: null, quantity: 1, remark: '' })
const inStockMaterials = ref([])
const fromLocations = ref([])
const freeLocations = ref([])

const materialLabel = (id) => {
  const m = materials.value.find((x) => x.id === id)
  return m ? `${m.materialCode} ${m.materialName}` : id
}
const locationLabel = (id) => locationMap.value[id] || id

async function loadMoves() {
  moveLoading.value = true
  try {
    const res = await pageMoveOrder(moveQuery)
    moveRows.value = res.data.records
    moveTotal.value = res.data.total
  } finally {
    moveLoading.value = false
  }
}

async function openMove() {
  Object.assign(moveForm, { materialId: null, fromLocationId: null, toLocationId: null, quantity: 1, remark: '' })
  fromLocations.value = []
  moveVisible.value = true
  // 有库存的物料（汇总维度，可用>0）
  const res = await getInventorySummary({})
  inStockMaterials.value = res.data.filter((r) => r.available > 0)
  // 全部非禁用库位
  const tree = await getLocationTree()
  const locs = []
  for (const w of tree.data) {
    for (const a of w.children || []) {
      for (const l of a.children || []) {
        if (l.status !== 2) locs.push(l)
      }
    }
  }
  freeLocations.value = locs
}

async function onMoveMaterial(materialId) {
  moveForm.fromLocationId = null
  const res = await getInventoryByLocation({})
  fromLocations.value = res.data.filter((r) => r.materialId === materialId && r.available > 0)
}

async function doMove() {
  if (!moveForm.materialId || !moveForm.fromLocationId || !moveForm.toLocationId) {
    ElMessage.warning('请完整选择物料、源库位和目标库位')
    return
  }
  acting.value = true
  try {
    const res = await moveInventory(moveForm)
    ElMessage.success(`移库完成，单号：${res.data}`)
    moveVisible.value = false
    loadMoves()
    loadStock()
  } finally {
    acting.value = false
  }
}

// ==================== 预警 ====================
const warnings = ref({ stockAlerts: [], expiryAlerts: [] })
const warnCount = computed(() => warnings.value.stockAlerts.length + warnings.value.expiryAlerts.length)

async function loadWarnings() {
  const res = await getWarnings()
  warnings.value = res.data
}

// ==================== 冻结/解冻 ====================
const freezeVisible = ref(false)
const freezeRow = ref(null)
const freezeForm = reactive({ qty: 1, unfreeze: false })

function openFreeze(row, unfreeze) {
  freezeRow.value = row
  freezeForm.qty = 1
  freezeForm.unfreeze = unfreeze
  freezeVisible.value = true
}

async function doFreeze() {
  acting.value = true
  try {
    const fn = freezeForm.unfreeze ? unfreezeInventory : freezeInventory
    await fn({ inventoryId: freezeRow.value.inventoryId, qty: freezeForm.qty })
    ElMessage.success(freezeForm.unfreeze ? '解冻成功' : '冻结成功')
    freezeVisible.value = false
    loadStock()
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  loadStock()
  loadLogs()
  loadMoves()
  loadWarnings()
  const [w, m, tree] = await Promise.all([
    listAllWarehouse(),
    request.get('/base/material/all'),
    getLocationTree()
  ])
  warehouses.value = w.data
  materials.value = m.data
  const map = {}
  for (const wh of tree.data) {
    for (const a of wh.children || []) {
      for (const l of a.children || []) map[l.id] = l.locationCode
    }
  }
  locationMap.value = map
})
</script>
