<template>
  <div>
    <el-card shadow="never">
      <!-- 分类筛选 chip 行（替代原左侧分类树卡片） -->
      <div class="category-chips">
        <div class="chips-left">
          <span
            class="chip"
            :class="{ active: query.categoryId == null }"
            @click="onChipClick(null)"
          >
            全部 <em>{{ totalCount }}</em>
          </span>
          <span
            v-for="cat in categoryChips"
            :key="cat.id"
            class="chip"
            :class="{ active: query.categoryId === cat.id }"
            @click="onChipClick(cat)"
          >
            {{ cat.name }} <em>{{ cat.count }}</em>
          </span>
          <el-button link type="primary" size="small" class="chip-add" @click="openCategoryDialog()">+ 新增分类</el-button>
        </div>
        <div class="chips-right">
          <el-form :inline="true" @submit.prevent>
            <el-form-item>
              <el-input v-model="query.keyword" placeholder="关键字 / 物料名" clearable style="width: 200px"
                @keyup.enter="load(1)" />
            </el-form-item>
            <el-form-item>
              <el-select v-model="query.status" placeholder="状态" clearable style="width: 100px">
                <el-option label="启用" :value="1" />
                <el-option label="停用" :value="0" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="load(1)">查询</el-button>
              <el-button @click="reset">重置</el-button>
            </el-form-item>
          </el-form>
        </div>
      </div>

      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openDialog()">新增物料</el-button>
        <el-upload :show-file-list="false" accept=".xlsx,.xls" :http-request="doImport"
          style="display: inline-block; margin: 0 8px">
          <el-button type="success">导入 Excel</el-button>
        </el-upload>
        <el-button @click="doExport">导出 Excel</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" border>
        <el-table-column prop="materialCode" label="编号" width="80" />
        <el-table-column prop="materialName" label="物料名称" min-width="110" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格型号" width="95" show-overflow-tooltip />
        <el-table-column prop="unit" label="单位" width="58" />
        <el-table-column prop="purchasePrice" label="采购价" width="75" align="right" />
        <el-table-column prop="salePrice" label="销售价" width="75" align="right" />
        <el-table-column prop="totalQty" label="库存总量" width="95" align="right">
          <template #default="{ row }">
            <div>
              <span :class="{ 'zero-stock': !row.totalQty }">{{ row.totalQty ?? 0 }}</span>
              <el-link
                v-if="(row.frozenQty || 0) > 0"
                type="warning"
                :underline="false"
                class="frozen-mini"
                title="该物料有冻结库存，点击跳转到库存流水"
                @click="goFrozenLogs(row)"
              >❄ {{ row.frozenQty }}</el-link>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="locations" label="存放库位" min-width="150">
          <template #default="{ row }">
            <div v-if="row.locations && row.locations !== '-'" class="location-text">
              <div v-for="loc in row.locations.split(' / ')" :key="loc" class="loc-item">
                <el-icon class="loc-icon"><MapLocation /></el-icon>{{ loc }}
              </div>
            </div>
            <span v-else class="no-location">暂无库存</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-popconfirm title="确定删除该物料？" @confirm="remove(row)">
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

    <!-- 物料弹窗 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑物料' : '新增物料'" width="520px" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="95px">
        <el-row :gutter="10">
          <el-col :span="12">
            <el-form-item label="物料编号" prop="materialCode">
              <el-input v-model="form.materialCode" maxlength="32" placeholder="如 WL001" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="物料名称" prop="materialName">
              <el-input v-model="form.materialName" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="所属分类">
              <el-tree-select v-model="form.categoryId" :data="categoryTree" node-key="id"
                :props="{ label: 'categoryName' }" check-strictly style="width: 100%" clearable />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="规格型号">
              <el-input v-model="form.spec" maxlength="64" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="计量单位">
              <el-input v-model="form.unit" maxlength="16" placeholder="个/箱/千克…" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="保质期(天)">
              <el-input-number v-model="form.shelfLife" :min="0" style="width: 100%" placeholder="留空表示无" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="采购单价">
              <el-input-number v-model="form.purchasePrice" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="销售单价">
              <el-input-number v-model="form.salePrice" :min="0" :precision="2" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="库存上限">
              <el-input-number v-model="form.stockUpper" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="库存下限">
              <el-input-number v-model="form.stockLower" :min="0" style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="备注">
              <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="200" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">确定</el-button>
      </template>
    </el-dialog>

    <!-- 分类弹窗 -->
    <el-dialog v-model="catDialogVisible" :title="catForm.id ? '编辑分类' : '新增分类'" width="400px" destroy-on-close>
      <el-form ref="catFormRef" :model="catForm" :rules="catRules" label-width="90px">
        <el-form-item label="上级分类">
          <el-input :model-value="catForm.parentName || '（顶级分类）'" disabled />
        </el-form-item>
        <el-form-item label="分类名称" prop="categoryName">
          <el-input v-model="catForm.categoryName" maxlength="32" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="catForm.sort" :min="0" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="catDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveCategory">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  pageMaterial, addMaterial, updateMaterial, deleteMaterial, updateMaterialStatus,
  importMaterial, exportMaterial, downloadBlob,
  getCategoryTree, getCategoryCounts, addCategory, updateCategory, deleteCategory
} from '../../../api/base'

const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: null, categoryId: null })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const categoryTree = ref([])        // 新增/编辑分类弹窗里用（保留）
const categoryChips = ref([])      // 顶部 chip 栏数据：{id, name, count}
const totalCount = ref(0)          // "全部" chip 的数量 = 所有物料数
const router = useRouter()

// 点击冻结标识 → 跳到库存管理 → 变动流水（预选物料 + 类型=冻结）
function goFrozenLogs(row) {
  router.push({ path: '/inventory', query: { materialId: row.id, type: 'frozen' } })
}

const dialogVisible = ref(false)
const catDialogVisible = ref(false)
const saving = ref(false)
const formRef = ref()
const catFormRef = ref()

const emptyForm = {
  id: null, materialCode: '', materialName: '', categoryId: null, spec: '', unit: '',
  purchasePrice: null, salePrice: null, shelfLife: null, stockUpper: null, stockLower: null, remark: ''
}
const form = reactive({ ...emptyForm })
const catForm = reactive({ id: null, parentId: 0, parentName: '', categoryName: '', sort: 0 })

const rules = {
  materialCode: [{ required: true, message: '请输入物料编号', trigger: 'blur' }],
  materialName: [{ required: true, message: '请输入物料名称', trigger: 'blur' }]
}
const catRules = {
  categoryName: [{ required: true, message: '请输入分类名称', trigger: 'blur' }]
}

async function load(page) {
  if (page) query.pageNum = page
  loading.value = true
  try {
    const res = await pageMaterial(query)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

async function loadTree() {
  const [treeRes, countsRes] = await Promise.all([getCategoryTree(), getCategoryCounts()])
  categoryTree.value = treeRes.data
  categoryChips.value = countsRes.data
  totalCount.value = countsRes.data.reduce((sum, c) => sum + Number(c.count || 0), 0)
}

function reset() {
  query.keyword = ''
  query.status = null
  query.categoryId = null
  load(1)
}

function onChipClick(cat) {
  query.categoryId = cat ? cat.id : null
  load(1)
}

function clearCategory() {
  // 保留兼容（不再有"当前分类"提示），但保留供其他处使用
  query.categoryId = null
  load(1)
}

function openDialog(row) {
  // 新增时若当前选中了某个分类，自动预填该分类
  Object.assign(form, row ? { ...row } : { ...emptyForm, categoryId: query.categoryId ?? null })
  dialogVisible.value = true
}

async function save() {
  await formRef.value.validate()
  saving.value = true
  try {
    if (form.id) {
      await updateMaterial(form)
      ElMessage.success('修改成功')
    } else {
      await addMaterial(form)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    load()
  } finally {
    saving.value = false
  }
}

async function toggle(row) {
  await updateMaterialStatus(row.id, row.status === 1 ? 0 : 1)
  ElMessage.success('操作成功')
  load()
}

async function remove(row) {
  await deleteMaterial(row.id)
  ElMessage.success('删除成功')
  load()
}

async function doImport({ file }) {
  const res = await importMaterial(file)
  ElMessage.success(res.data || res.msg || '导入完成')
  load()
}

async function doExport() {
  const res = await exportMaterial()
  downloadBlob(res, '物料数据.xlsx')
}

// ==================== 分类维护 ====================

function openCategoryDialog(cat, parent) {
  if (cat) {
    Object.assign(catForm, { id: cat.id, parentId: cat.parentId, parentName: '', categoryName: cat.categoryName, sort: cat.sort ?? 0 })
  } else {
    Object.assign(catForm, { id: null, parentId: parent?.id ?? 0, parentName: parent?.categoryName ?? '', categoryName: '', sort: 0 })
  }
  catDialogVisible.value = true
}

async function saveCategory() {
  await catFormRef.value.validate()
  saving.value = true
  try {
    if (catForm.id) {
      await updateCategory({ id: catForm.id, categoryName: catForm.categoryName, sort: catForm.sort })
      ElMessage.success('修改成功')
    } else {
      await addCategory({ parentId: catForm.parentId, categoryName: catForm.categoryName, sort: catForm.sort })
      ElMessage.success('新增成功')
    }
    catDialogVisible.value = false
    loadTree()
  } finally {
    saving.value = false
  }
}

async function removeCategory(data) {
  await deleteCategory(data.id)
  ElMessage.success('删除成功')
  if (query.categoryId === data.id) {
    query.categoryId = null
    load(1)
  }
  loadTree()
}

onMounted(() => {
  load()
  loadTree()
})
</script>

<style scoped>
.cat-node {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
}
.cat-ops {
  display: none;
}
.cat-node:hover .cat-ops {
  display: inline-flex;
}
.zero-stock {
  color: #c0c4cc;
}
.frozen-mini {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 500;
  vertical-align: middle;
}
:deep(.frozen-mini .el-link__inner) {
  color: #e6a23c;
}
:deep(.frozen-mini:hover .el-link__inner) {
  color: #f56c6c;
}

/* 分类筛选 chip 栏（替代原左侧分类树） */
.category-chips {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}
.chips-left {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1;
  min-width: 0;
}
.chips-right {
  flex-shrink: 0;
}
.chip {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 12px;
  border-radius: 14px;
  background: #f4f4f5;
  color: #606266;
  font-size: 13px;
  cursor: pointer;
  user-select: none;
  transition: all 0.18s;
  border: 1px solid transparent;
}
.chip:hover {
  background: #ecf5ff;
  color: #409eff;
}
.chip.active {
  background: #409eff;
  color: #fff;
  border-color: #409eff;
  box-shadow: 0 2px 6px rgba(64, 158, 255, 0.25);
}
.chip em {
  font-style: normal;
  margin-left: 5px;
  padding: 0 6px;
  background: rgba(0, 0, 0, 0.08);
  border-radius: 9px;
  font-size: 11px;
  min-width: 18px;
  text-align: center;
  line-height: 16px;
}
.chip.active em {
  background: rgba(255, 255, 255, 0.28);
  color: #fff;
}
.chip-add {
  margin-left: 4px;
}
.location-text {
  color: #2e6bff;
  font-size: 12.5px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.loc-item {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  line-height: 1.5;
}
.loc-icon {
  flex-shrink: 0;
}
.no-location {
  color: #c0c4cc;
  font-size: 12.5px;
}
</style>
