<template>
  <div>
    <el-card shadow="never">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="名称/编号/联系人" clearable style="width: 180px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="合作等级">
          <el-select v-model="query.level" placeholder="全部" clearable style="width: 110px">
            <el-option v-for="l in ['普通', '银牌', '金牌']" :key="l" :label="l" :value="l" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 100px">
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
        <el-button type="primary" @click="openDialog()">新增客户</el-button>
        <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="doImport"
          style="display: inline-block; margin: 0 8px">
          <el-button type="success">导入 Excel</el-button>
        </el-upload>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="customerCode" label="编号" width="110" />
        <el-table-column prop="customerName" label="客户名称" min-width="150" />
        <el-table-column prop="contact" label="联系人" width="100" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="address" label="地址" min-width="150" show-overflow-tooltip />
        <el-table-column label="合作等级" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.level === '金牌' ? 'warning' : row.level === '银牌' ? 'info' : ''" size="small">
              {{ row.level || '普通' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-popconfirm :title="row.status === 1 ? '确定停用该客户？' : '确定启用该客户？'" @confirm="toggle(row)">
              <template #reference>
                <el-button link :type="row.status === 1 ? 'danger' : 'success'">
                  {{ row.status === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50]" @change="load()" />
    </el-card>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑客户' : '新增客户'" width="460px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="编号" prop="customerCode">
          <el-input v-model="form.customerCode" maxlength="32" placeholder="如 KH001" />
        </el-form-item>
        <el-form-item label="名称" prop="customerName">
          <el-input v-model="form.customerName" maxlength="64" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="form.contact" maxlength="32" />
        </el-form-item>
        <el-form-item label="联系电话">
          <el-input v-model="form.phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" maxlength="128" />
        </el-form-item>
        <el-form-item label="合作等级">
          <el-select v-model="form.level" style="width: 100%">
            <el-option v-for="l in ['普通', '银牌', '金牌']" :key="l" :label="l" :value="l" />
          </el-select>
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
  pageCustomer, addCustomer, updateCustomer, updateCustomerStatus,
  importCustomer, exportCustomer, downloadBlob
} from '../../../api/base'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', level: null, status: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const emptyForm = { id: null, customerCode: '', customerName: '', contact: '', phone: '', address: '', level: '普通', remark: '' }
const form = reactive({ ...emptyForm })

const rules = {
  customerCode: [{ required: true, message: '请输入客户编号', trigger: 'blur' }],
  customerName: [{ required: true, message: '请输入客户名称', trigger: 'blur' }]
}

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageCustomer(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.level = null
  query.status = null
  load(1)
}

function openDialog(row) {
  Object.assign(form, row ? { ...row } : { ...emptyForm })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateCustomer(form)
      ElMessage.success('修改成功')
    } else {
      await addCustomer(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  await updateCustomerStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success('操作成功')
  load()
}

async function doImport({ file }) {
  const res = await importCustomer(file)
  ElMessage.success(res.data || '导入完成')
  load()
}

async function doExport() {
  const res = await exportCustomer()
  downloadBlob(res, '客户数据.xlsx')
}

onMounted(load)
</script>
