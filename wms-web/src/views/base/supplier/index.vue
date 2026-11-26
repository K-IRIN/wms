<template>
  <div>
    <el-card shadow="never">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="名称/编号/联系人" clearable style="width: 180px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="合作状态">
          <el-select v-model="query.cooperateStatus" placeholder="全部" clearable style="width: 120px">
            <el-option label="合作中" :value="1" />
            <el-option label="已终止" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openDialog()">新增供应商</el-button>
        <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="doImport"
          style="display: inline-block; margin: 0 8px">
          <el-button type="success">导入 Excel</el-button>
        </el-upload>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="supplierCode" label="编号" width="110" />
        <el-table-column prop="supplierName" label="供应商名称" min-width="150" />
        <el-table-column prop="contact" label="联系人" width="100" />
        <el-table-column prop="phone" label="联系电话" width="130" />
        <el-table-column prop="address" label="地址" min-width="160" show-overflow-tooltip />
        <el-table-column label="合作状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.cooperateStatus === 1 ? 'success' : 'danger'" size="small">
              {{ row.cooperateStatus === 1 ? '合作中' : '已终止' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-popconfirm :title="row.cooperateStatus === 1 ? '确定终止与该供应商合作？' : '确定恢复合作？'"
              @confirm="toggle(row)">
              <template #reference>
                <el-button link :type="row.cooperateStatus === 1 ? 'danger' : 'success'">
                  {{ row.cooperateStatus === 1 ? '终止合作' : '恢复合作' }}
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑供应商' : '新增供应商'" width="460px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="编号" prop="supplierCode">
          <el-input v-model="form.supplierCode" maxlength="32" placeholder="如 GYS001" />
        </el-form-item>
        <el-form-item label="名称" prop="supplierName">
          <el-input v-model="form.supplierName" maxlength="64" />
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
  pageSupplier, addSupplier, updateSupplier, updateSupplierStatus,
  importSupplier, exportSupplier, downloadBlob
} from '../../../api/base'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', cooperateStatus: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const emptyForm = { id: null, supplierCode: '', supplierName: '', contact: '', phone: '', address: '', remark: '' }
const form = reactive({ ...emptyForm })

const rules = {
  supplierCode: [{ required: true, message: '请输入供应商编号', trigger: 'blur' }],
  supplierName: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }]
}

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageSupplier(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

function reset() {
  query.keyword = ''
  query.cooperateStatus = null
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
      await updateSupplier(form)
      ElMessage.success('修改成功')
    } else {
      await addSupplier(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  await updateSupplierStatus(row.id, row.cooperateStatus === 1 ? 0 : 1)
  ElMessage.success('操作成功')
  load()
}

async function doImport({ file }) {
  const res = await importSupplier(file)
  ElMessage.success(res.data || '导入完成')
  load()
}

async function doExport() {
  const res = await exportSupplier()
  downloadBlob(res, '供应商数据.xlsx')
}

onMounted(load)
</script>
