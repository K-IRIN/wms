<template>
  <div class="page">
    <el-card shadow="never">
      <el-form :inline="true" @submit.prevent>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" placeholder="AGV编号/名称" clearable style="width: 180px"
            @keyup.enter="load(1)" />
        </el-form-item>
        <el-form-item label="运行状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="空闲" :value="0" />
            <el-option label="运行中" :value="1" />
            <el-option label="充电中" :value="2" />
            <el-option label="故障" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div style="margin-bottom: 12px">
        <el-button type="primary" :icon="Plus" @click="openDialog()">新增 AGV</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="agvCode" label="编号" width="95" />
        <el-table-column prop="agvName" label="名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="maxLoad" label="载重(kg)" width="90" align="right" />
        <el-table-column prop="speed" label="速度(格/秒)" width="100" align="center" />
        <el-table-column label="电量" width="150" align="center">
          <template #default="{ row }">
            <el-progress :percentage="row.battery" :stroke-width="14" :color="batteryColor" text-inside />
          </template>
        </el-table-column>
        <el-table-column label="运行状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="statusMap[row.status]?.type" size="small">{{ statusMap[row.status]?.text }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="是否启用" width="85" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled === 1 ? 'success' : 'info'" size="small">
              {{ row.enabled === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="当前位置" width="90" align="center">
          <template #default="{ row }">({{ row.posX }}, {{ row.posY }})</template>
        </el-table-column>
        <el-table-column label="待命区" width="85" align="center">
          <template #default="{ row }">({{ row.homeX }}, {{ row.homeY }})</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="110" show-overflow-tooltip />
        <el-table-column label="操作" width="235" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button v-if="row.status === 2" link type="success" @click="finishCharge(row)">完成充电</el-button>
            <el-button v-else link type="warning" :disabled="row.status === 1" @click="charge(row)">充电</el-button>
            <el-popconfirm :title="row.enabled === 1 ? '确定停用该设备？' : '确定启用该设备？'" @confirm="toggle(row)">
              <template #reference>
                <el-button link :type="row.enabled === 1 ? 'warning' : 'success'">
                  {{ row.enabled === 1 ? '停用' : '启用' }}
                </el-button>
              </template>
            </el-popconfirm>
            <el-popconfirm title="确定删除该设备？" @confirm="remove(row)">
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑 AGV 设备' : '新增 AGV 设备'" width="480px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="AGV 编号" prop="agvCode">
          <el-input v-model="form.agvCode" maxlength="32" placeholder="如 AGV-04" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="AGV 名称" prop="agvName">
          <el-input v-model="form.agvName" maxlength="32" placeholder="如 搬运四号" />
        </el-form-item>
        <el-form-item label="最大载重(kg)" prop="maxLoad">
          <el-input-number v-model="form.maxLoad" :min="1" :max="100000" style="width: 180px" />
        </el-form-item>
        <el-form-item label="速度(格/秒)" prop="speed">
          <el-input-number v-model="form.speed" :min="0.1" :max="10" :step="0.1" style="width: 180px" />
        </el-form-item>
        <el-form-item label="待命区 X">
          <el-input-number v-model="form.homeX" :min="0" :max="999" style="width: 180px" />
        </el-form-item>
        <el-form-item label="待命区 Y">
          <el-input-number v-model="form.homeY" :min="0" :max="999" style="width: 180px" />
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
import { Plus } from '@element-plus/icons-vue'
import {
  pageAgvDevice, addAgvDevice, updateAgvDevice, updateAgvDeviceStatus,
  chargeAgvDevice, finishChargeAgvDevice, deleteAgvDevice
} from '../../../api/agv'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const emptyForm = { id: null, agvCode: '', agvName: '', maxLoad: 500, speed: 1.0, homeX: 0, homeY: 0, remark: '' }
const form = reactive({ ...emptyForm })

const rules = {
  agvCode: [{ required: true, message: '请输入 AGV 编号', trigger: 'blur' }],
  agvName: [{ required: true, message: '请输入 AGV 名称', trigger: 'blur' }],
  maxLoad: [{ required: true, message: '请输入最大载重', trigger: 'blur' }],
  speed: [{ required: true, message: '请输入速度', trigger: 'blur' }]
}

// 运行状态：0空闲 1运行中 2充电中 3故障
const statusMap = {
  0: { text: '空闲', type: 'success' },
  1: { text: '运行中', type: 'primary' },
  2: { text: '充电中', type: 'warning' },
  3: { text: '故障', type: 'danger' }
}

const batteryColor = (b) => (b > 50 ? '#67c23a' : b > 20 ? '#e6a23c' : '#f56c6c')

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageAgvDevice(query)
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
  Object.assign(form, emptyForm, row ? {
    id: row.id, agvCode: row.agvCode, agvName: row.agvName, maxLoad: row.maxLoad,
    speed: row.speed, homeX: row.homeX, homeY: row.homeY, remark: row.remark
  } : {})
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateAgvDevice(form)
      ElMessage.success('修改成功')
    } else {
      await addAgvDevice(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  await updateAgvDeviceStatus(row.id, row.enabled === 1 ? 0 : 1)
  ElMessage.success(row.enabled === 1 ? '已停用' : '已启用')
  load()
}

async function charge(row) {
  await chargeAgvDevice(row.id)
  ElMessage.success(`${row.agvCode} 开始充电`)
  load()
}

async function finishCharge(row) {
  await finishChargeAgvDevice(row.id)
  ElMessage.success(`${row.agvCode} 充电完成`)
  load()
}

async function remove(row) {
  await deleteAgvDevice(row.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>
