<template>
  <div>
    <el-card shadow="never">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="仓库名称/编号" clearable style="width: 180px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 110px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openDialog()">新增仓库</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="warehouseCode" label="编号" width="110" />
        <el-table-column prop="warehouseName" label="仓库名称" min-width="140" />
        <el-table-column prop="warehouseType" label="类型" width="110">
          <template #default="{ row }">
            <el-tag size="small">{{ row.warehouseType || '综合仓' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="地址" min-width="160" show-overflow-tooltip />
        <el-table-column prop="manager" label="负责人" width="100" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-popconfirm title="确定删除该仓库？" @confirm="remove(row)">
              <template #reference>
                <el-button link type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑仓库' : '新增仓库'" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="仓库编号" prop="warehouseCode">
          <el-input v-model="form.warehouseCode" maxlength="32" placeholder="如 CK001" />
        </el-form-item>
        <el-form-item label="仓库名称" prop="warehouseName">
          <el-input v-model="form.warehouseName" maxlength="64" />
        </el-form-item>
        <el-form-item label="仓库类型">
          <el-select v-model="form.warehouseType" style="width: 100%">
            <el-option v-for="t in ['原料仓', '半成品仓', '成品仓', '综合仓']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" maxlength="128" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.manager" maxlength="32" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  pageWarehouse, addWarehouse, updateWarehouse,
  deleteWarehouse, updateWarehouseStatus
} from '../../../api/base'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, warehouseCode: '', warehouseName: '', warehouseType: '综合仓', address: '', manager: '', phone: '', remark: '' })

const rules = {
  warehouseCode: [{ required: true, message: '请输入仓库编号', trigger: 'blur' }],
  warehouseName: [{ required: true, message: '请输入仓库名称', trigger: 'blur' }]
}

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageWarehouse(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.status = null
  load(1)
}

function openDialog(row) {
  Object.assign(form, row
    ? { ...row }
    : { id: null, warehouseCode: '', warehouseName: '', warehouseType: '综合仓', address: '', manager: '', phone: '', remark: '' })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateWarehouse(form)
      ElMessage.success('修改成功')
    } else {
      await addWarehouse(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  await updateWarehouseStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success('操作成功')
  load()
}

async function remove(row) {
  await deleteWarehouse(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
