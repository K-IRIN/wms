<template>
  <div>
    <el-card shadow="never">
      <!-- 搜索区 -->
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="用户名/姓名/手机号" clearable style="width: 200px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="启用" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 操作按钮区 -->
      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openDialog()">新增用户</el-button>
        <el-button type="success" :disabled="!selected.length" @click="batchStatus(1)">批量启用</el-button>
        <el-button type="warning" :disabled="!selected.length" @click="batchStatus(0)">批量禁用</el-button>
      </div>

      <!-- 数据表格 -->
      <el-table :data="rows" v-loading="loading" border @selection-change="(v) => (selected = v)">
        <el-table-column type="selection" width="45" />
        <el-table-column prop="username" label="用户名" width="130" />
        <el-table-column prop="realName" label="姓名" width="110" />
        <el-table-column prop="nickname" label="昵称" width="110" />
        <el-table-column prop="dept" label="部门" width="120" />
        <el-table-column prop="phone" label="手机号" width="130" />
        <el-table-column label="角色" min-width="140">
          <template #default="{ row }">
            <el-tag v-for="r in row.roleNames || []" :key="r" size="small" style="margin-right: 4px">{{ r }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="160" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="warning" @click="resetPwd(row)">重置密码</el-button>
            <el-popconfirm title="确定删除该用户？" @confirm="remove(row)">
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

    <!-- 新增/编辑弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="!!form.id" maxlength="32" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" maxlength="32" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" maxlength="32" />
        </el-form-item>
        <el-form-item label="部门">
          <el-input v-model="form.dept" maxlength="64" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" maxlength="20" />
        </el-form-item>
        <el-form-item label="角色" prop="roleId">
          <el-select v-model="form.roleId" style="width: 100%">
            <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="!form.id" label="初始密码">
          <el-input v-model="form.password" type="password" show-password placeholder="留空默认 123456" />
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
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageUser, addUser, updateUser, deleteUser,
  updateUserStatus, resetUserPwd, listRole
} from '../../../api/user'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const selected = ref([])
const roles = ref([])

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const form = reactive({ id: null, username: '', realName: '', nickname: '', dept: '', phone: '', roleId: null, password: '' })

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageUser(query)
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
  Object.assign(form, {
    id: row?.id ?? null,
    username: row?.username ?? '',
    realName: row?.realName ?? '',
    nickname: row?.nickname ?? '',
    dept: row?.dept ?? '',
    phone: row?.phone ?? '',
    roleId: row?.roles?.[0] ?? null,
    password: ''
  })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateUser(form)
      ElMessage.success('修改成功')
    } else {
      await addUser(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  await deleteUser(row.id)
  ElMessage.success('删除成功')
  load()
}

async function batchStatus(status) {
  const ids = selected.value.map((u) => u.id)
  await updateUserStatus({ ids, status })
  ElMessage.success(status === 1 ? '已批量启用' : '已批量禁用')
  load()
}

async function resetPwd(row) {
  await ElMessageBox.confirm(`确定将「${row.username}」的密码重置为 123456？`, '重置密码', { type: 'warning' })
  await resetUserPwd(row.id)
  ElMessage.success('密码已重置为 123456')
}

onMounted(async () => {
  load()
  const res = await listRole()
  roles.value = res.data
})
</script>
