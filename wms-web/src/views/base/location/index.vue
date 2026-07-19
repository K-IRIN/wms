<template>
  <div>
    <el-card shadow="never">
      <div style="margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center">
        <span style="color: #909399; font-size: 13px">
          仓库 → 库区 → 库位 三级结构；仓库本身请在「仓库管理」页维护
        </span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <el-tree :data="tree" v-loading="loading" node-key="treeKey" :props="{ label: 'label' }" default-expand-all
        :expand-on-click-node="false">
        <template #default="{ data }">
          <div class="tree-node">
            <span class="node-label">
              <el-tag v-if="data.nodeType === 'warehouse'" size="small" type="primary">仓库</el-tag>
              <el-tag v-else-if="data.nodeType === 'area'" size="small" type="warning">库区</el-tag>
              <el-tag v-else size="small" type="info">库位</el-tag>
              <span>{{ data.label }}</span>
              <span class="node-code">{{ data.code }}</span>
            </span>
            <span class="node-extra">
              <template v-if="data.nodeType === 'location'">
                <span class="meta">容量 {{ data.capacity ?? '—' }}</span>
                <span class="meta">{{ data.goodsType || '不限类型' }}</span>
                <el-tag :type="locStatusType(data.status)" size="small">{{ locStatusText(data.status) }}</el-tag>
              </template>
              <template v-else>
                <el-tag :type="data.status === 1 ? 'success' : 'info'" size="small">
                  {{ data.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </span>
            <span class="node-ops">
              <el-button v-if="data.nodeType === 'warehouse'" link type="primary" size="small"
                @click.stop="openAreaDialog(null, data)">+ 库区</el-button>
              <template v-if="data.nodeType === 'area'">
                <el-button link type="primary" size="small" @click.stop="openLocDialog(null, data)">+ 库位</el-button>
                <el-button link type="primary" size="small" @click.stop="openAreaDialog(data)">编辑</el-button>
                <el-popconfirm title="确定删除该库区？" @confirm="removeArea(data)">
                  <template #reference>
                    <el-button link type="danger" size="small" @click.stop>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
              <template v-if="data.nodeType === 'location'">
                <el-button link type="primary" size="small" @click.stop="openLocDialog(data)">编辑</el-button>
                <el-popconfirm title="确定删除该库位？" @confirm="removeLocation(data)">
                  <template #reference>
                    <el-button link type="danger" size="small" @click.stop>删除</el-button>
                  </template>
                </el-popconfirm>
              </template>
            </span>
          </div>
        </template>
      </el-tree>
      <el-empty v-if="!loading && !tree.length" description="还没有仓库，请先到「仓库管理」新增仓库" />
    </el-card>

    <!-- 库区弹窗 -->
    <el-dialog v-model="areaDialogVisible" :title="areaForm.id ? '编辑库区' : '新增库区'" width="420px" destroy-on-close>
      <el-form ref="areaFormRef" :model="areaForm" :rules="areaRules" label-width="90px">
        <el-form-item label="所属仓库">
          <el-input :model-value="areaForm.warehouseName" disabled />
        </el-form-item>
        <el-form-item label="库区编号" prop="areaCode">
          <el-input v-model="areaForm.areaCode" maxlength="32" placeholder="如 A区" :disabled="!!areaForm.id" />
        </el-form-item>
        <el-form-item label="库区名称" prop="areaName">
          <el-input v-model="areaForm.areaName" maxlength="64" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="areaForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="areaForm.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="areaDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveArea">确定</el-button>
      </template>
    </el-dialog>

    <!-- 库位弹窗 -->
    <el-dialog v-model="locDialogVisible" :title="locForm.id ? '编辑库位' : '新增库位'" width="420px" destroy-on-close>
      <el-form ref="locFormRef" :model="locForm" :rules="locRules" label-width="90px">
        <el-form-item label="所属库区">
          <el-input :model-value="locForm.areaName" disabled />
        </el-form-item>
        <el-form-item label="库位编号" prop="locationCode">
          <el-input v-model="locForm.locationCode" maxlength="32" placeholder="如 A-01-01" :disabled="!!locForm.id" />
        </el-form-item>
        <el-form-item label="容量">
          <el-input-number v-model="locForm.capacity" :min="0" :precision="2" style="width: 100%" />
        </el-form-item>
        <el-form-item label="存放类型">
          <el-input v-model="locForm.goodsType" maxlength="32" placeholder="可存放的物料类型，留空不限" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="locForm.status">
            <el-radio :value="0">空闲</el-radio>
            <el-radio :value="1">占用</el-radio>
            <el-radio :value="2">禁用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="locForm.remark" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveLocation">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getLocationTree, addArea, updateArea, deleteArea,
  addLocation, updateLocation, deleteLocation
} from '../../../api/base'

const tree = ref([])
const loading = ref(false)
const saving = ref(false)

const areaDialogVisible = ref(false)
const locDialogVisible = ref(false)
const areaFormRef = ref()
const locFormRef = ref()

const areaForm = reactive({ id: null, warehouseId: null, warehouseName: '', areaCode: '', areaName: '', status: 1, remark: '' })
const locForm = reactive({ id: null, areaId: null, areaName: '', locationCode: '', capacity: null, goodsType: '', status: 0, remark: '' })

const areaRules = {
  areaCode: [{ required: true, message: '请输入库区编号', trigger: 'blur' }],
  areaName: [{ required: true, message: '请输入库区名称', trigger: 'blur' }]
}
const locRules = {
  locationCode: [{ required: true, message: '请输入库位编号', trigger: 'blur' }]
}

const locStatusText = (s) => (s === 1 ? '占用' : s === 2 ? '禁用' : '空闲')
const locStatusType = (s) => (s === 1 ? 'warning' : s === 2 ? 'danger' : 'success')

async function load() {
  loading.value = true
  try {
    const res = await getLocationTree()
    const assign = (nodes, prefix) =>
      nodes.forEach((n) => {
        n.treeKey = `${prefix}-${n.id}`
        assign(n.children || [], n.treeKey)
      })
    assign(res.data, 'n')
    tree.value = res.data
  } finally {
    loading.value = false
  }
}

function openAreaDialog(area, warehouseNode) {
  if (area) {
    Object.assign(areaForm, {
      id: area.id, warehouseId: area.warehouseId, warehouseName: '',
      areaCode: area.code, areaName: area.label, status: area.status, remark: area.remark || ''
    })
  } else {
    Object.assign(areaForm, {
      id: null, warehouseId: warehouseNode.id, warehouseName: warehouseNode.label,
      areaCode: '', areaName: '', status: 1, remark: ''
    })
  }
  areaDialogVisible.value = true
}

async function saveArea() {
  await areaFormRef.value.validate()
  saving.value = true
  try {
    if (areaForm.id) {
      await updateArea({ id: areaForm.id, areaName: areaForm.areaName, status: areaForm.status, remark: areaForm.remark })
      ElMessage.success('修改成功')
    } else {
      await addArea({ warehouseId: areaForm.warehouseId, areaCode: areaForm.areaCode, areaName: areaForm.areaName, status: areaForm.status, remark: areaForm.remark })
      ElMessage.success('新增成功')
    }
    areaDialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeArea(data) {
  await deleteArea(data.id)
  ElMessage.success('删除成功')
  load()
}

function openLocDialog(loc, areaNode) {
  if (loc) {
    Object.assign(locForm, {
      id: loc.id, areaId: loc.areaId, areaName: '',
      locationCode: loc.code, capacity: loc.capacity, goodsType: loc.goodsType || '',
      status: loc.status, remark: loc.remark || ''
    })
  } else {
    Object.assign(locForm, {
      id: null, areaId: areaNode.id, areaName: areaNode.label,
      locationCode: '', capacity: null, goodsType: '', status: 0, remark: ''
    })
  }
  locDialogVisible.value = true
}

async function saveLocation() {
  await locFormRef.value.validate()
  saving.value = true
  try {
    if (locForm.id) {
      await updateLocation({ id: locForm.id, capacity: locForm.capacity, goodsType: locForm.goodsType, status: locForm.status, remark: locForm.remark })
      ElMessage.success('修改成功')
    } else {
      await addLocation({ areaId: locForm.areaId, locationCode: locForm.locationCode, capacity: locForm.capacity, goodsType: locForm.goodsType, status: locForm.status, remark: locForm.remark })
      ElMessage.success('新增成功')
    }
    locDialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function removeLocation(data) {
  await deleteLocation(data.id)
  ElMessage.success('删除成功')
  load()
}

onMounted(load)
</script>

<style scoped>
.tree-node {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 12px;
}
.node-label {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 260px;
}
.node-code {
  color: #909399;
  font-size: 12px;
}
.node-extra {
  display: flex;
  align-items: center;
  gap: 10px;
}
.meta {
  color: #909399;
  font-size: 12px;
}
.node-ops {
  margin-left: auto;
}
</style>
