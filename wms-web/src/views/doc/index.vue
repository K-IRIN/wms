<template>
  <div class="page">
    <el-card shadow="never">
      <el-tabs v-model="query.type" @tab-change="onTabChange">
        <el-tab-pane label="采购入库单" name="INBOUND" />
        <el-tab-pane label="销售出库单" name="OUTBOUND" />
        <el-tab-pane label="移库单" name="MOVE" />
        <el-tab-pane label="盘点单" name="CHECK" />
      </el-tabs>

      <el-form inline>
        <el-form-item label="单号">
          <el-input v-model="query.orderNo" placeholder="模糊搜索" clearable style="width: 180px"
            @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 130px" @change="load(1)">
            <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" border stripe :row-class-name="invalidRowClass">
        <el-table-column prop="orderNo" label="单号" width="160" />
        <el-table-column prop="orderTypeName" label="单据类型" width="110" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.invalidFlag === 1" type="info">已作废</el-tag>
            <el-tag v-else :type="statusTagType(row.status)">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bizInfo" label="业务摘要" min-width="220" show-overflow-tooltip />
        <el-table-column prop="operator" label="制单人" width="100" />
        <el-table-column prop="createTime" label="创建时间" width="165">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="作废原因" min-width="140">
          <template #default="{ row }">
            <span v-if="row.invalidFlag === 1" class="invalid-reason">{{ row.invalidReason }}</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canCancel(row)" type="danger" link @click="openCancel(row)">作废</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <!-- 作废弹窗 -->
    <el-dialog v-model="cancelVisible" title="作废单据" width="420px" destroy-on-close>
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 12px"
        :title="`确定作废单据 ${cancelRow?.orderNo} 吗？作废后单据只读，不可再操作。`" />
      <el-form label-width="90px">
        <el-form-item label="作废原因" required>
          <el-input v-model="cancelReason" type="textarea" :rows="3" maxlength="200" show-word-limit
            placeholder="必填，如：单据录入错误" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="cancelVisible = false">取消</el-button>
        <el-button type="danger" :loading="canceling" @click="doCancel">确认作废</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { pageDoc, cancelDoc } from '../../api/doc.js'
import { formatTime } from '../../utils/format.js'

const STATUS_MAP = {
  INBOUND: [
    { value: 0, label: '待审核' }, { value: 1, label: '已审核' },
    { value: 2, label: '已驳回' }, { value: 3, label: '已完成' }
  ],
  OUTBOUND: [
    { value: 0, label: '待审核' }, { value: 1, label: '已审核' },
    { value: 2, label: '已驳回' }, { value: 3, label: '已完成' }
  ],
  MOVE: [{ value: 0, label: '待确认' }, { value: 1, label: '已完成' }],
  CHECK: [{ value: 0, label: '待盘点' }, { value: 1, label: '待审核' }, { value: 3, label: '已完成' }]
}

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const query = ref({ type: 'INBOUND', pageNum: 1, pageSize: 10, orderNo: '', status: null })

const statusOptions = computed(() => STATUS_MAP[query.value.type] || [])

const cancelVisible = ref(false)
const canceling = ref(false)
const cancelRow = ref(null)
const cancelReason = ref('')

function statusTagType(status) {
  if (status === 0) return 'warning'
  if (status === 1) return 'primary'
  if (status === 2) return 'danger'
  return 'success'
}

function invalidRowClass({ row }) {
  return row.invalidFlag === 1 ? 'invalid-row' : ''
}

function canCancel(row) {
  if (row.invalidFlag === 1) return false
  if (row.orderType === 'INBOUND' || row.orderType === 'OUTBOUND') return row.status === 0 || row.status === 2
  if (row.orderType === 'CHECK') return row.status === 0 || row.status === 1
  return false // 移库创建即生效，不可作废
}

function onTabChange() {
  query.value.status = null
  load(1)
}

async function load(pageNum) {
  if (pageNum) query.value.pageNum = pageNum
  loading.value = true
  try {
    const res = await pageDoc(query.value)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function openCancel(row) {
  cancelRow.value = row
  cancelReason.value = ''
  cancelVisible.value = true
}

async function doCancel() {
  if (!cancelReason.value.trim()) {
    ElMessage.warning('请填写作废原因')
    return
  }
  canceling.value = true
  try {
    await cancelDoc({ type: cancelRow.value.orderType, id: cancelRow.value.id, reason: cancelReason.value.trim() })
    ElMessage.success('单据已作废')
    cancelVisible.value = false
    load()
  } finally {
    canceling.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.page {
  padding: 12px;
}

.invalid-reason {
  color: #f56c6c;
  font-size: 12px;
}
</style>

<style>
.invalid-row {
  --el-table-tr-bg-color: #fafafa;
  color: #909399;
}
</style>
