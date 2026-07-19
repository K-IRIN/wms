<template>
  <div>
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="单号">
          <el-input v-model="query.orderNo" placeholder="入库单号" clearable style="width: 170px" @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="query.supplierId" placeholder="全部" clearable filterable style="width: 170px">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="待审核" :value="0" />
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
        <el-button type="primary" @click="openCreate()">新建入库单</el-button>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <!-- 单据列表 -->
      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="orderNo" label="入库单号" width="150" />
        <el-table-column label="供应商" min-width="150">
          <template #default="{ row }">{{ supplierName(row.supplierId) }}</template>
        </el-table-column>
        <el-table-column label="入库仓库" width="120">
          <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column prop="orderDate" label="入库日期" width="105" />
        <el-table-column prop="totalAmount" label="总金额" width="110" align="right">
          <template #default="{ row }">¥ {{ row.totalAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="applicant" label="制单人" width="100" />
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">详情</el-button>
            <el-button v-if="row.status !== 3" link type="warning" @click="openCreate(row.id)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <!-- 新建/编辑入库单 -->
    <el-dialog v-model="createVisible" :title="form.id ? '编辑入库单' : '新建入库单'" width="860px" destroy-on-close top="4vh">
      <el-form :model="form" label-width="85px">
        <el-row :gutter="10">
          <el-col :span="8">
            <el-form-item label="供应商" required>
              <el-select v-model="form.supplierId" filterable style="width: 100%">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.supplierName" :value="s.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库仓库" required>
              <el-select v-model="form.warehouseId" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="入库日期" required>
              <el-date-picker v-model="form.orderDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <!-- 扫码快速添加 -->
      <div class="scan-row">
        <el-input v-model="scanCode" placeholder="扫描或输入物料编号后回车，自动添加明细" clearable @keyup.enter="onScan">
          <template #prefix>🔍</template>
        </el-input>
      </div>

      <!-- 明细编辑 -->
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

      <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="备注（选填）" style="margin-top: 10px" />

      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="入库单详情" size="780px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)" size="small">{{ statusText(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="供应商">{{ detail.supplierName }}</el-descriptions-item>
          <el-descriptions-item label="入库仓库">{{ detail.warehouseName }}</el-descriptions-item>
          <el-descriptions-item label="入库日期">{{ detail.orderDate }}</el-descriptions-item>
          <el-descriptions-item label="总金额">¥ {{ detail.totalAmount }}</el-descriptions-item>
          <el-descriptions-item label="制单人">{{ detail.applicant }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.auditor || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核意见" :span="2">{{ detail.auditRemark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="备注" :span="2">{{ detail.remark || '—' }}</el-descriptions-item>
        </el-descriptions>

        <h4 style="margin: 16px 0 8px">明细（{{ detail.items.length }} 条）</h4>
        <el-table :data="detail.items" border size="small">
          <el-table-column prop="materialCode" label="编号" width="90" />
          <el-table-column prop="materialName" label="物料" min-width="110" />
          <el-table-column prop="quantity" label="数量" width="70" align="right" />
          <el-table-column prop="price" label="单价" width="80" align="right" />
          <el-table-column prop="amount" label="金额" width="90" align="right" />
          <el-table-column label="质检" width="130" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.qualityStatus === 1" type="success" size="small">合格</el-tag>
              <el-tag v-else-if="row.qualityStatus === 2" type="danger" size="small">不合格</el-tag>
              <template v-else>
                <el-button v-if="detail.status === 0" link type="primary" size="small" @click="openQuality(row)">质检</el-button>
                <el-tag v-else size="small" type="info">待质检</el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="上架" min-width="130" align="center">
            <template #default="{ row }">
              <template v-if="row.shelfStatus === 1">
                <el-tag size="small" type="warning">{{ row.locationCode }}</el-tag>
                <el-button v-if="detail.status === 0" link type="danger" size="small" @click="cancelShelf(row)">退</el-button>
              </template>
              <el-button v-else-if="detail.status === 0 && row.qualityStatus === 1" link type="primary" size="small"
                @click="openShelf(row)">上架</el-button>
              <span v-else style="color: #c0c4cc">—</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="detail.items.some(i => i.qualityStatus === 2)" style="margin-top: 8px; color: #f56c6c; font-size: 13px">
          不合格明细处理：{{ detail.items.filter(i => i.qualityStatus === 2).map(i => `${i.materialName}（${i.remark || '未填写'}）`).join('、') }}
        </div>

        <!-- 审核操作 -->
        <template v-if="detail.status === 0">
          <h4 style="margin: 16px 0 8px">审核</h4>
          <el-input v-model="auditRemark" type="textarea" :rows="2" placeholder="审核意见（选填）" />
          <div style="margin-top: 10px">
            <el-button type="success" :loading="auditing" @click="audit(true)">审核通过（库存生效）</el-button>
            <el-button type="danger" :loading="auditing" @click="audit(false)">驳回（退回修改）</el-button>
          </div>
        </template>
      </template>
    </el-drawer>

    <!-- 质检弹窗 -->
    <el-dialog v-model="qualityVisible" title="质检登记" width="380px" destroy-on-close>
      <p style="margin-top: 0">{{ currentItem?.materialName }} × {{ currentItem?.quantity }}</p>
      <el-radio-group v-model="qualityForm.qualityStatus">
        <el-radio :value="1">合格（可上架）</el-radio>
        <el-radio :value="2">不合格</el-radio>
      </el-radio-group>
      <el-input v-if="qualityForm.qualityStatus === 2" v-model="qualityForm.remark" placeholder="处理方式：退货 / 补货"
        style="margin-top: 12px" />
      <template #footer>
        <el-button @click="qualityVisible = false">取消</el-button>
        <el-button type="primary" @click="saveQuality">确定</el-button>
      </template>
    </el-dialog>

    <!-- 上架弹窗 -->
    <el-dialog v-model="shelfVisible" title="选择上架库位" width="440px" destroy-on-close>
      <p style="margin-top: 0">{{ currentItem?.materialName }} × {{ currentItem?.quantity }}</p>
      <template v-if="recommended.length">
        <p style="color: #67c23a; font-size: 13px">✨ 智能推荐（空闲且类型匹配）：</p>
        <el-radio-group v-model="shelfForm.locationId" style="display: flex; flex-direction: column; gap: 6px">
          <el-radio v-for="l in recommended" :key="l.id" :value="l.id" border size="small">
            {{ l.locationCode }}（容量 {{ l.capacity ?? '—' }} {{ l.goodsType || '不限类型' }}）
          </el-radio>
        </el-radio-group>
      </template>
      <p style="color: #909399; font-size: 13px; margin-top: 12px">或手动选择空闲库位：</p>
      <el-select v-model="shelfForm.locationId" filterable style="width: 100%">
        <el-option v-for="l in freeLocs" :key="l.id" :label="`${l.locationCode}（容量 ${l.capacity ?? '—'}）`" :value="l.id" />
      </el-select>
      <template #footer>
        <el-button @click="shelfVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!shelfForm.locationId" @click="saveShelf">确认上架</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageInbound, getInboundDetail, createInbound, updateInbound, qualityInbound,
  scanMaterial, recommendLocation, freeLocations, shelfInbound, cancelShelfInbound,
  auditInbound, exportInbound
} from '../../api/inbound'
import { listAllSupplier, listAllWarehouse, downloadBlob } from '../../api/base'
import request from '../../utils/request'

const query = reactive({ pageNum: 1, pageSize: 10, orderNo: '', supplierId: null, status: null })
const dateRange = ref(null)
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const suppliers = ref([])
const warehouses = ref([])
const materials = ref([])

const statusText = (s) => (s === 0 ? '待审核' : s === 2 ? '已驳回' : '已完成')
const statusType = (s) => (s === 0 ? 'warning' : s === 2 ? 'danger' : 'success')
const supplierName = (id) => suppliers.value.find((s) => s.id === id)?.supplierName || '—'
const warehouseName = (id) => warehouses.value.find((w) => w.id === id)?.warehouseName || '—'

// ==================== 列表 ====================

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const params = { ...query }
    if (dateRange.value?.length === 2) {
      params.dateStart = dateRange.value[0]
      params.dateEnd = dateRange.value[1]
    }
    const res = await pageInbound(params)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.orderNo = ''
  query.supplierId = null
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
  const res = await exportInbound(params)
  downloadBlob(res, '入库单.xlsx')
}

// ==================== 新建/编辑 ====================

const createVisible = ref(false)
const saving = ref(false)
const scanCode = ref('')
const form = reactive({ id: null, supplierId: null, warehouseId: null, orderDate: '', remark: '', items: [] })

const totalAmount = computed(() =>
  form.items.reduce((sum, i) => sum + (i.quantity || 0) * (i.price || 0), 0).toFixed(2))

async function openCreate(id) {
  Object.assign(form, { id: null, supplierId: null, warehouseId: null, remark: '', items: [] })
  form.orderDate = new Date().toISOString().slice(0, 10)
  scanCode.value = ''
  if (id) {
    const res = await getInboundDetail(id)
    Object.assign(form, {
      id: res.data.id,
      supplierId: res.data.supplierId,
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
  if (m && (!row.price || row.price === 0)) row.price = m.purchasePrice || 0
}

async function onScan() {
  if (!scanCode.value.trim()) return
  try {
    const res = await scanMaterial(scanCode.value.trim())
    const m = res.data
    const exist = form.items.find((i) => i.materialId === m.id)
    if (exist) {
      exist.quantity += 1
      ElMessage.success(`已累加：${m.materialName} × ${exist.quantity}`)
    } else {
      form.items.push({ materialId: m.id, quantity: 1, price: m.purchasePrice || 0 })
      ElMessage.success(`已添加：${m.materialName}`)
    }
    scanCode.value = ''
  } catch (e) { /* request.js 已提示 */ }
}

async function save() {
  if (!form.supplierId || !form.warehouseId || !form.orderDate) {
    ElMessage.warning('请完整选择供应商、仓库和日期')
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
      await updateInbound({ ...form, items })
      ElMessage.success('修改成功')
    } else {
      const res = await createInbound({ ...form, items })
      ElMessage.success(`创建成功，单号：${res.data}`)
    }
    createVisible.value = false
    load(1)
  } finally {
    saving.value = false
  }
}

// ==================== 详情/质检/上架/审核 ====================

const detailVisible = ref(false)
const detail = ref(null)
const auditRemark = ref('')
const auditing = ref(false)

async function openDetail(id) {
  const res = await getInboundDetail(id)
  detail.value = res.data
  auditRemark.value = ''
  detailVisible.value = true
}

async function refreshDetail() {
  const res = await getInboundDetail(detail.value.id)
  detail.value = res.data
}

const qualityVisible = ref(false)
const currentItem = ref(null)
const qualityForm = reactive({ qualityStatus: 1, remark: '' })

function openQuality(item) {
  currentItem.value = item
  qualityForm.qualityStatus = 1
  qualityForm.remark = ''
  qualityVisible.value = true
}

async function saveQuality() {
  if (qualityForm.qualityStatus === 2 && !qualityForm.remark.trim()) {
    ElMessage.warning('不合格时请填写处理方式（退货/补货）')
    return
  }
  await qualityInbound({ itemId: currentItem.value.id, ...qualityForm })
  ElMessage.success('质检完成')
  qualityVisible.value = false
  refreshDetail()
}

const shelfVisible = ref(false)
const shelfForm = reactive({ locationId: null })
const recommended = ref([])
const freeLocs = ref([])

async function openShelf(item) {
  currentItem.value = item
  shelfForm.locationId = null
  shelfVisible.value = true
  recommended.value = []
  freeLocs.value = []
  const [rec, free] = await Promise.all([
    recommendLocation(item.materialId, detail.value.warehouseId),
    freeLocations(detail.value.warehouseId)
  ])
  recommended.value = rec.data
  freeLocs.value = free.data
  if (rec.data.length) shelfForm.locationId = rec.data[0].id
}

async function saveShelf() {
  await shelfInbound({ itemId: currentItem.value.id, locationId: shelfForm.locationId })
  ElMessage.success('上架成功')
  shelfVisible.value = false
  refreshDetail()
}

async function cancelShelf(item) {
  await ElMessageBox.confirm('确定取消上架？库位将释放为空闲。', '提示', { type: 'warning' })
  await cancelShelfInbound(item.id)
  ElMessage.success('已取消上架')
  refreshDetail()
}

async function audit(approve) {
  auditing.value = true
  try {
    await auditInbound({ id: detail.value.id, approve, auditRemark: auditRemark.value })
    ElMessage.success(approve ? '审核通过，库存已生效' : '已驳回')
    detailVisible.value = false
    load()
  } finally {
    auditing.value = false
  }
}

onMounted(async () => {
  load()
  const [s, w, m] = await Promise.all([
    listAllSupplier(),
    listAllWarehouse(),
    request.get('/base/material/all')
  ])
  suppliers.value = s.data
  warehouses.value = w.data
  materials.value = m.data
})
</script>

<style scoped>
.scan-row {
  margin-bottom: 10px;
}
</style>
