<template>
  <div class="page">
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="单号">
          <el-input v-model="query.orderNo" placeholder="盘点单号" clearable style="width: 160px" @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="仓库">
          <el-select v-model="query.warehouseId" placeholder="全部" clearable style="width: 130px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="周期">
          <el-select v-model="query.checkPeriod" placeholder="全部" clearable style="width: 100px">
            <el-option v-for="p in ['月度', '季度', '年度']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="待盘点" :value="0" />
            <el-option label="待审核" :value="1" />
            <el-option label="已完成" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openCreate">新建盘点单</el-button>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <!-- 盘点单列表 -->
      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="orderNo" label="盘点单号" width="150" />
        <el-table-column label="仓库" width="110">
          <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column label="范围" width="100" align="center">
          <template #default="{ row }">{{ ['', '按仓库', '按库区', '按分类'][row.scopeType] }}</template>
        </el-table-column>
        <el-table-column prop="checkPeriod" label="周期" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" v-if="row.checkPeriod">{{ row.checkPeriod }}</el-tag>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="checker" label="盘点人" width="100" />
        <el-table-column prop="auditor" label="审核人" width="100">
          <template #default="{ row }">{{ row.auditor || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">
              {{ row.status === 0 ? '录入' : '详情' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <!-- 新建盘点单 -->
    <el-dialog v-model="createVisible" title="新建盘点单" width="460px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="盘点仓库" required>
          <el-select v-model="form.warehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="盘点范围" required>
          <el-radio-group v-model="form.scopeType">
            <el-radio :value="1">按仓库</el-radio>
            <el-radio :value="2">按库区</el-radio>
            <el-radio :value="3">按物料分类</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scopeType === 2" label="选择库区" required>
          <el-select v-model="form.areaId" style="width: 100%">
            <el-option v-for="a in areas" :key="a.id" :label="a.areaName" :value="a.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.scopeType === 3" label="选择分类" required>
          <el-tree-select v-model="form.categoryId" :data="categoryTree" node-key="id"
            :props="{ label: 'categoryName' }" check-strictly style="width: 100%" />
        </el-form-item>
        <el-form-item label="盘点周期">
          <el-select v-model="form.checkPeriod" style="width: 100%">
            <el-option v-for="p in ['月度', '季度', '年度']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="acting" @click="saveCreate">创建（自动带出账面库存）</el-button>
      </template>
    </el-dialog>

    <!-- 盘点明细抽屉 -->
    <el-drawer v-model="detailVisible" title="盘点单详情" size="820px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="周期">{{ detail.checkPeriod || '—' }}</el-descriptions-item>
          <el-descriptions-item label="盘点人">{{ detail.checker }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.auditor || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">
          盘点明细（{{ detail.items.length }} 条）
          <span v-if="diffCount" style="color: #f56c6c; font-size: 13px; margin-left: 8px">
            差异 {{ diffCount }} 条（盘盈 {{ profitCount }} / 盘亏 {{ lossCount }}）
          </span>
        </h4>
        <el-table :data="detail.items" border size="small" :row-class-name="diffRowClass">
          <el-table-column prop="materialCode" label="编号" width="90" />
          <el-table-column prop="materialName" label="物料" min-width="100" />
          <el-table-column prop="locationCode" label="库位" width="100" />
          <el-table-column prop="bookQty" label="账面" width="70" align="right" />
          <el-table-column label="实际" width="130" align="center">
            <template #default="{ row }">
              <el-input-number v-if="detail.status === 0" v-model="row.actualQty" :min="0" size="small"
                style="width: 110px" @change="onActualChange(row)" placeholder="录入" />
              <span v-else>{{ row.actualQty }}</span>
            </template>
          </el-table-column>
          <el-table-column label="盈亏" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.diffQty != null"
                :style="{ color: row.diffQty > 0 ? '#67c23a' : row.diffQty < 0 ? '#f56c6c' : '#909399', fontWeight: 'bold' }">
                {{ row.diffQty > 0 ? '+' : '' }}{{ row.diffQty }}
              </span>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
          <el-table-column label="盈亏原因" min-width="140">
            <template #default="{ row }">
              <el-input v-if="detail.status === 0 && row.diffQty" v-model="row.reason" size="small"
                placeholder="必填：差异原因说明" />
              <span v-else>{{ row.reason || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 操作区 -->
        <div style="margin-top: 14px" v-if="detail.status === 0">
          <el-button type="primary" :loading="acting" @click="saveEntry(false)">保存录入</el-button>
          <el-button type="success" :loading="acting" @click="saveEntry(true)">保存并提交审核</el-button>
        </div>
        <template v-if="detail.status === 1">
          <h4 style="margin: 16px 0 8px">差异审核</h4>
          <el-input v-model="auditRemark" type="textarea" :rows="2" placeholder="审核意见（选填）" />
          <div style="margin-top: 10px">
            <el-button type="success" :loading="acting" @click="audit(true)">审核通过（自动修正库存）</el-button>
            <el-button type="danger" :loading="acting" @click="audit(false)">驳回重盘</el-button>
          </div>
        </template>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageCheck, getCheckDetail, createCheck, entryCheck,
  submitCheck, auditCheck, exportCheck
} from '../../api/check'
import { listAllWarehouse, getCategoryTree, getLocationTree, downloadBlob } from '../../api/base'
import { formatTime } from '../../utils/format.js'

const query = reactive({ pageNum: 1, pageSize: 10, orderNo: '', warehouseId: null, status: null, checkPeriod: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const warehouses = ref([])
const categoryTree = ref([])
const areas = ref([])

const statusText = (s) => (s === 0 ? '待盘点' : s === 1 ? '待审核' : '已完成')
const statusType = (s) => (s === 0 ? 'warning' : s === 1 ? 'primary' : 'success')
const warehouseName = (id) => warehouses.value.find((w) => w.id === id)?.warehouseName || '—'

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageCheck(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(query, { pageNum: 1, orderNo: '', warehouseId: null, status: null, checkPeriod: null })
  load(1)
}

async function doExport() {
  const res = await exportCheck({ checkPeriod: query.checkPeriod })
  downloadBlob(res, '盘点单.xlsx')
}

// ==================== 新建 ====================

const createVisible = ref(false)
const acting = ref(false)
const form = reactive({ warehouseId: null, scopeType: 1, areaId: null, categoryId: null, checkPeriod: '月度', remark: '' })

async function openCreate() {
  Object.assign(form, { warehouseId: null, scopeType: 1, areaId: null, categoryId: null, checkPeriod: '月度', remark: '' })
  createVisible.value = true
  if (!areas.value.length) {
    const tree = await getLocationTree()
    const list = []
    for (const w of tree.data) {
      for (const a of w.children || []) list.push({ id: a.id, areaName: `${w.label} / ${a.label}`, warehouseId: w.id })
    }
    areas.value = list
  }
}

async function saveCreate() {
  if (!form.warehouseId) {
    ElMessage.warning('请选择盘点仓库')
    return
  }
  acting.value = true
  try {
    const res = await createCheck(form)
    ElMessage.success(`创建成功，单号：${res.data}`)
    createVisible.value = false
    load(1)
  } finally {
    acting.value = false
  }
}

// ==================== 明细/录入/审核 ====================

const detailVisible = ref(false)
const detail = ref(null)
const auditRemark = ref('')

const diffCount = computed(() => detail.value?.items.filter((i) => i.diffQty).length ?? 0)
const profitCount = computed(() => detail.value?.items.filter((i) => i.diffQty > 0).length ?? 0)
const lossCount = computed(() => detail.value?.items.filter((i) => i.diffQty < 0).length ?? 0)

const diffRowClass = ({ row }) => (row.diffQty ? 'diff-row' : '')

async function openDetail(id) {
  const res = await getCheckDetail(id)
  detail.value = res.data
  auditRemark.value = ''
  detailVisible.value = true
}

function onActualChange(row) {
  row.diffQty = row.actualQty == null ? null : row.actualQty - row.bookQty
}

async function saveEntry(submit) {
  // 先本地算一遍 diff（未触发 change 的保底）
  for (const item of detail.value.items) {
    if (item.actualQty != null) item.diffQty = item.actualQty - item.bookQty
  }
  const items = detail.value.items
    .filter((i) => i.actualQty != null)
    .map((i) => ({ itemId: i.id, actualQty: i.actualQty, reason: i.reason }))
  if (!items.length) {
    ElMessage.warning('请先录入至少一条实际数量')
    return
  }
  acting.value = true
  try {
    await entryCheck({ items })
    if (submit) {
      await submitCheck(detail.value.id)
      ElMessage.success('已提交审核')
      detailVisible.value = false
      load()
    } else {
      ElMessage.success('保存成功')
      openDetail(detail.value.id)
    }
  } finally {
    acting.value = false
  }
}

async function audit(approve) {
  acting.value = true
  try {
    await auditCheck({ id: detail.value.id, approve, auditRemark: auditRemark.value })
    ElMessage.success(approve ? '审核通过，库存已修正' : '已驳回，可重新盘点')
    detailVisible.value = false
    load()
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  load()
  const [w, c] = await Promise.all([listAllWarehouse(), getCategoryTree()])
  warehouses.value = w.data
  categoryTree.value = c.data
})
</script>

<style>
.diff-row {
  background: #fdf6ec;
}
</style>
