<template>
  <div>
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="单号">
          <el-input v-model="query.orderNo" placeholder="出库单号" clearable style="width: 170px" @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="客户">
          <el-select v-model="query.customerId" placeholder="全部" clearable filterable style="width: 170px">
            <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="待审核" :value="0" />
            <el-option label="已审核" :value="1" />
            <el-option label="已驳回" :value="2" />
            <el-option label="已完成" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" style="width: 220px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openCreate()">新建出库单</el-button>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <!-- 单据列表 -->
      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="orderNo" label="出库单号" width="150" />
        <el-table-column label="客户" min-width="140">
          <template #default="{ row }">{{ customerName(row.customerId) }}</template>
        </el-table-column>
        <el-table-column label="出库仓库" width="110">
          <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column prop="orderDate" label="出库日期" width="105" />
        <el-table-column prop="totalAmount" label="总金额" width="110" align="right">
          <template #default="{ row }">¥ {{ row.totalAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="拣货" width="80" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.status === 1 || row.status === 3" size="small"
              :type="row.pickStatus === 2 ? 'success' : row.pickStatus === 1 ? 'warning' : 'info'">
              {{ row.pickStatus === 2 ? '已拣货' : row.pickStatus === 1 ? '拣货中' : '未拣货' }}
            </el-tag>
            <span v-else style="color: #c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="applicant" label="制单人" width="95" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
            <el-button v-if="row.status === 0 || row.status === 2" link type="warning" @click="openCreate(row.id)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <!-- 新建/编辑出库单 -->
    <el-dialog v-model="createVisible" :title="form.id ? '编辑出库单' : '新建出库单'" width="860px" destroy-on-close top="4vh">
      <el-form :model="form" label-width="85px">
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="客户" required>
              <el-select v-model="form.customerId" filterable style="width: 100%">
                <el-option v-for="c in customers" :key="c.id" :label="c.customerName" :value="c.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="出库仓库" required>
              <el-select v-model="form.warehouseId" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="出库日期" required>
              <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <el-table :data="form.items" border size="small">
        <el-table-column label="物料" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.materialId" filterable placeholder="选择物料" style="width: 100%"
              @change="onMaterialChange(row)">
              <el-option v-for="m in materials" :key="m.id" :label="`${m.materialCode} ${m.materialName}`" :value="m.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="1" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="单价" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.price" :min="0" :precision="2" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">¥ {{ ((row.quantity || 0) * (row.price || 0)).toFixed(2) }}</template>
        </el-table-column>
        <el-table-column label="" width="60" align="center">
          <template #default="{ $index }">
            <el-button link type="danger" @click="form.items.splice($index, 1)">删</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 8px; display: flex; justify-content: space-between; align-items: center">
        <el-button size="small" @click="form.items.push({ materialId: null, quantity: 1, price: 0 })">+ 添加明细</el-button>
        <span style="font-size: 15px">合计：<b style="color: #f56c6c">¥ {{ totalAmount }}</b></span>
      </div>

      <el-alert style="margin-top: 10px" type="info" :closable="false"
        title="提交时系统会校验所选仓库的可用库存，库存不足将无法保存（禁止超卖）" />
      <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注（选填）" style="margin-top: 10px" />

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="出库单详情" size="800px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="客户">{{ detail.customerName }}</el-descriptions-item>
          <el-descriptions-item label="出库仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="出库日期">{{ detail.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="总金额">¥ {{ detail.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="制单人">{{ detail.applicant }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.auditor || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核意见" :span="2">{{ detail.auditRemark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">
          拣货任务（{{ detail.items.length }} 条）
          <el-tag v-if="detail.status === 1" size="small" :type="detail.pickStatus === 2 ? 'success' : 'warning'" style="margin-left: 8px">
            {{ detail.pickStatus === 2 ? '已全部拣货' : detail.pickStatus === 1 ? '拣货中' : '待拣货' }}
          </el-tag>
        </h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="materialCode" label="编号" width="90" />
          <el-table-column prop="materialName" label="物料" min-width="100" />
          <el-table-column label="拣货库位" width="110" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.locationCode" size="small" type="warning">{{ row.locationCode }}</el-tag>
              <span v-else style="color: #c0c4cc">审核后分配</span>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="应拣" width="70" align="right" />
          <el-table-column label="已拣" width="70" align="right">
            <template #default="{ row }">{{ row.pickedQty }}</template>
          </el-table-column>
          <el-table-column prop="price" label="单价" width="80" align="right" />
          <el-table-column prop="amount" label="金额" width="90" align="right" />
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <template v-if="detail.status === 1 && row.pickedQty < row.quantity">
                <el-button link type="primary" size="small" @click="openPick(row, true)">扫码核验</el-button>
                <el-button link type="success" size="small" @click="pickManual(row)">确认拣货</el-button>
              </template>
              <el-tag v-else-if="row.pickedQty >= row.quantity" type="success" size="small">✓ 已拣</el-tag>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
        </el-table>

        <!-- 审核操作 -->
        <template v-if="detail.status === 0">
          <h4 style="margin: 16px 0 8px">审核</h4>
          <el-input v-model="auditRemark" type="textarea" :rows="2" placeholder="审核意见（选填）" />
          <div style="margin-top: 10px">
            <el-button type="success" :loading="acting" @click="audit(true)">审核通过（生成拣货任务）</el-button>
            <el-button type="danger" :loading="acting" @click="audit(false)">驳回</el-button>
          </div>
        </template>

        <!-- 复核操作 -->
        <template v-if="detail.status === 1 && detail.pickStatus === 2">
          <el-alert style="margin-top: 16px" type="warning" :closable="false"
            title="拣货已全部完成，复核通过后将扣减库存并写库存流水，此操作不可撤销" />
          <div style="margin-top: 10px">
            <el-button type="danger" :loading="acting" @click="review">复核确认出库</el-button>
          </div>
        </template>
      </template>
    </el-drawer>

    <!-- 扫码拣货弹窗 -->
    <el-dialog v-model="pickVisible" title="扫码核验出库" width="400px" destroy-on-close>
      <p style="margin-top: 0">
        请扫描物料二维码，应扫：<el-tag size="small">{{ currentItem?.materialCode }}</el-tag>
        {{ currentItem?.materialName }} × {{ currentItem?.quantity }}
      </p>
      <el-input v-model="pickCode" placeholder="扫描或输入物料编号后回车" @keyup.enter="doPick" ref="pickInputRef">
        <template #append>
          <el-button @click="scanVisible = true">📷 摄像头</el-button>
        </template>
      </el-input>
      <ScanDialog v-model="scanVisible" @scan="onCameraScan" />
      <template #footer>
        <el-button @click="pickVisible = false">取消</el-button>
        <el-button type="primary" @click="doPick">核验并拣货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageOutbound, getOutboundDetail, createOutbound, updateOutbound,
  auditOutbound, pickOutbound, reviewOutbound, exportOutbound
} from '../../api/outbound'
import { listAllCustomer, listAllWarehouse, downloadBlob } from '../../api/base'
import request from '../../utils/request'
import ScanDialog from '../../components/ScanDialog.vue'

const query = reactive({ pageNum: 1, pageSize: 10, orderNo: '', customerId: null, status: null })
const dateRange = ref(null)
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const customers = ref([])
const warehouses = ref([])
const materials = ref([])

const statusText = (s) => (['待审核', '已审核', '已驳回', '已完成'][s] ?? '未知')
const statusType = (s) => (['warning', 'primary', 'danger', 'success'][s] ?? 'info')
const customerName = (id) => customers.value.find((c) => c.id === id)?.customerName || '—'
const warehouseName = (id) => warehouses.value.find((w) => w.id === id)?.warehouseName || '—'

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const params = { ...query }
    if (dateRange.value?.length === 2) {
      params.dateStart = dateRange.value[0]
      params.dateEnd = dateRange.value[1]
    }
    const res = await pageOutbound(params)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.orderNo = ''
  query.customerId = null
  query.status = null
  dateRange.value = null
  load(1)
}

async function doExport() {
  const params = {}
  if (dateRange.value?.length === 2) {
    params.dateStart = dateRange.value[0]
    params.dateEnd = dateRange.value[1]
  }
  const res = await exportOutbound(params)
  downloadBlob(res, '出库单.xlsx')
}

// ==================== 新建/编辑 ====================

const createVisible = ref(false)
const saving = ref(false)
const form = reactive({ id: null, customerId: null, warehouseId: null, orderDate: '', remark: '', items: [] })

const totalAmount = computed(() =>
  form.items.reduce((sum, i) => sum + (i.quantity || 0) * (i.price || 0), 0).toFixed(2))

async function openCreate(id) {
  Object.assign(form, { id: null, customerId: null, warehouseId: null, remark: '', items: [] })
  form.orderDate = new Date().toISOString().slice(0, 10)
  if (id) {
    const res = await getOutboundDetail(id)
    Object.assign(form, {
      id: res.data.id,
      customerId: res.data.customerId,
      warehouseId: res.data.warehouseId,
      orderDate: res.data.orderDate,
      remark: res.data.remark,
      items: res.data.items.map((i) => ({ materialId: i.materialId, quantity: i.quantity, price: i.price }))
    })
  } else {
    form.items.push({ materialId: null, quantity: 1, price: 0 })
  }
  createVisible.value = true
}

function onMaterialChange(row) {
  const m = materials.value.find((x) => x.id === row.materialId)
  if (m && (!row.price || row.price === 0)) row.price = m.salePrice || 0
}

async function save() {
  if (!form.customerId || !form.warehouseId || !form.orderDate) {
    ElMessage.warning('请完整选择客户、仓库和日期')
    return
  }
  const items = form.items.filter((i) => i.materialId)
  if (!items.length) {
    ElMessage.warning('请至少添加一条有效明细')
    return
  }
  saving.value = true
  try {
    if (form.id) {
      await updateOutbound({ ...form, items })
      ElMessage.success('修改成功')
    } else {
      const res = await createOutbound({ ...form, items })
      ElMessage.success(`创建成功，单号：${res.data}`)
    }
    createVisible.value = false
    load(1)
  } finally {
    saving.value = false
  }
}

// ==================== 详情/审核/拣货/复核 ====================

const detailVisible = ref(false)
const detail = ref(null)
const auditRemark = ref('')
const acting = ref(false)

async function openDetail(id) {
  const res = await getOutboundDetail(id)
  detail.value = res.data
  auditRemark.value = ''
  detailVisible.value = true
}

async function refreshDetail() {
  const res = await getOutboundDetail(detail.value.id)
  detail.value = res.data
}

async function audit(approve) {
  acting.value = true
  try {
    await auditOutbound({ id: detail.value.id, approve, auditRemark: auditRemark.value })
    ElMessage.success(approve ? '审核通过，拣货任务已生成' : '已驳回')
    detailVisible.value = false
    load()
  } finally {
    acting.value = false
  }
}

const pickVisible = ref(false)
const currentItem = ref(null)
const pickCode = ref('')
const pickInputRef = ref()
const scanVisible = ref(false)

function onCameraScan(code) {
  pickCode.value = code
  doPick()
}

function openPick(item) {
  currentItem.value = item
  pickCode.value = ''
  pickVisible.value = true
  nextTick(() => pickInputRef.value?.focus())
}

async function doPick() {
  if (!pickCode.value.trim()) {
    ElMessage.warning('请扫描或输入物料编号')
    return
  }
  await pickOutbound({ itemId: currentItem.value.id, materialCode: pickCode.value.trim() })
  ElMessage.success('核验通过，拣货完成')
  pickVisible.value = false
  refreshDetail()
}

async function pickManual(item) {
  await ElMessageBox.confirm(`确认 ${item.materialName} × ${item.quantity} 已拣货？`, '确认拣货', { type: 'info' })
  await pickOutbound({ itemId: item.id, pickedQty: item.quantity })
  ElMessage.success('拣货完成')
  refreshDetail()
}

async function review() {
  await ElMessageBox.confirm('复核通过将扣减库存并写流水，确认出库？', '出库复核', { type: 'warning' })
  acting.value = true
  try {
    await reviewOutbound(detail.value.id)
    ElMessage.success('复核通过，出库完成')
    detailVisible.value = false
    load()
  } finally {
    acting.value = false
  }
}

onMounted(async () => {
  load()
  const [c, w, m] = await Promise.all([
    listAllCustomer(),
    listAllWarehouse(),
    request.get('/base/material/all')
  ])
  customers.value = c.data
  warehouses.value = w.data
  materials.value = m.data
})
</script>
