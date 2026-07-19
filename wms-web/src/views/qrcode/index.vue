<template>
  <div>
    <el-card shadow="never">
      <el-tabs v-model="tab">
        <!-- ========== 物料二维码 ========== -->
        <el-tab-pane label="物料二维码" name="material">
          <div style="margin-bottom: 12px">
            <el-button type="primary" :disabled="!matSelected.length" @click="downloadZip('material')">
              批量下载 ZIP（{{ matSelected.length }}）
            </el-button>
            <el-button type="success" :disabled="!matSelected.length" @click="printLabels('material')">
              批量打印标签
            </el-button>
            <span style="margin-left: 10px; color: #909399; font-size: 13px">二维码内容 = 物料编号，资料修改后重新生成即最新</span>
          </div>
          <el-table :data="materials" v-loading="loading" border @selection-change="(v) => (matSelected = v)">
            <el-table-column type="selection" width="45" />
            <el-table-column prop="materialCode" label="编号" width="110" />
            <el-table-column prop="materialName" label="物料名称" min-width="130" />
            <el-table-column prop="spec" label="规格" width="130" show-overflow-tooltip />
            <el-table-column prop="unit" label="单位" width="70" />
            <el-table-column prop="salePrice" label="销售价" width="90" align="right" />
            <el-table-column label="二维码" width="110" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="preview('material', row)">预览/打印</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== 库位二维码 ========== -->
        <el-tab-pane label="库位二维码" name="location">
          <div style="margin-bottom: 12px">
            <el-button type="primary" :disabled="!locSelected.length" @click="downloadZip('location')">
              批量下载 ZIP（{{ locSelected.length }}）
            </el-button>
            <el-button type="success" :disabled="!locSelected.length" @click="printLabels('location')">
              批量打印标签
            </el-button>
          </div>
          <el-table :data="locations" v-loading="loading" border @selection-change="(v) => (locSelected = v)">
            <el-table-column type="selection" width="45" />
            <el-table-column prop="locationCode" label="库位编号" width="130" />
            <el-table-column prop="warehouseName" label="所属仓库" width="120" />
            <el-table-column prop="areaName" label="所属库区" width="120" />
            <el-table-column prop="goodsType" label="存放类型" width="110">
              <template #default="{ row }">{{ row.goodsType || '不限' }}</template>
            </el-table-column>
            <el-table-column label="二维码" width="110" align="center">
              <template #default="{ row }">
                <el-button link type="primary" @click="preview('location', row)">预览/打印</el-button>
              </template>
            </el-table-column>
            <el-table-column label="库存" width="110" align="center">
              <template #default="{ row }">
                <el-button link type="warning" @click="viewStock(row)">查看库存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- ========== 扫码演示 ========== -->
        <el-tab-pane label="扫码演示" name="scan">
          <div class="scan-box">
            <el-button type="primary" size="large" @click="scanVisible = true">📷 打开摄像头扫码</el-button>
            <el-divider>或手动输入编号</el-divider>
            <el-input v-model="manualCode" placeholder="输入物料编号（如 M001）或库位编号（如 A02-01-01）后回车"
              style="max-width: 420px" clearable @keyup.enter="resolveCode(manualCode)" />
          </div>
          <template v-if="scanResult">
            <el-card v-if="scanResult.type === 'material'" shadow="hover" class="result-card">
              <h4>📦 物料信息</h4>
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="编号">{{ scanResult.data.materialCode }}</el-descriptions-item>
                <el-descriptions-item label="名称">{{ scanResult.data.materialName }}</el-descriptions-item>
                <el-descriptions-item label="规格">{{ scanResult.data.spec || '—' }}</el-descriptions-item>
                <el-descriptions-item label="单位">{{ scanResult.data.unit || '—' }}</el-descriptions-item>
                <el-descriptions-item label="采购价">{{ scanResult.data.purchasePrice }}</el-descriptions-item>
                <el-descriptions-item label="销售价">{{ scanResult.data.salePrice }}</el-descriptions-item>
              </el-descriptions>
            </el-card>
            <el-card v-else shadow="hover" class="result-card">
              <h4>🗄️ 库位 {{ scanResult.code }} 的库存（{{ scanResult.data.length }} 条）</h4>
              <el-table :data="scanResult.data" border size="small">
                <el-table-column prop="materialCode" label="编号" width="110" />
                <el-table-column prop="materialName" label="物料" min-width="120" />
                <el-table-column prop="quantity" label="数量" width="90" align="right" />
                <el-table-column prop="frozenQty" label="冻结" width="90" align="right" />
              </el-table>
              <el-empty v-if="!scanResult.data.length" description="该库位暂无库存" :image-size="60" />
            </el-card>
          </template>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 单个二维码预览 -->
    <el-dialog v-model="previewVisible" :title="previewTitle" width="320px" destroy-on-close align-center>
      <div class="qr-preview">
        <img v-if="previewUrl" :src="previewUrl" alt="二维码" />
        <p class="qr-text">{{ previewText }}</p>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="success" @click="printSingle">打印标签</el-button>
      </template>
    </el-dialog>

    <ScanDialog v-model="scanVisible" @scan="resolveCode" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  materialQrBlob, locationQrBlob, batchMaterialQr, batchLocationQr,
  getLocationStock, getMaterialByCode
} from '../../api/qrcode'
import { downloadBlob, getLocationTree } from '../../api/base'
import ScanDialog from '../../components/ScanDialog.vue'
import request from '../../utils/request'

const tab = ref('material')
const loading = ref(false)
const materials = ref([])
const locations = ref([])
const matSelected = ref([])
const locSelected = ref([])

// ==================== 数据加载 ====================

async function load() {
  loading.value = true
  try {
    const m = await request.get('/base/material/all')
    materials.value = m.data
    const tree = await getLocationTree()
    const locs = []
    for (const w of tree.data) {
      for (const a of w.children || []) {
        for (const l of a.children || []) {
          locs.push({ ...l, warehouseName: w.label, areaName: a.label })
        }
      }
    }
    locations.value = locs
  } finally {
    loading.value = false
  }
}

// ==================== 预览/打印 ====================

const previewVisible = ref(false)
const previewUrl = ref('')
const previewTitle = ref('')
const previewText = ref('')
let previewRaw = null

async function fetchQrUrl(type, id) {
  const res = type === 'material' ? await materialQrBlob(id) : await locationQrBlob(id)
  return URL.createObjectURL(res.data)
}

async function preview(type, row) {
  previewRaw = { type, row }
  previewTitle.value = type === 'material' ? `物料二维码：${row.materialName}` : `库位二维码：${row.locationCode}`
  previewText.value = type === 'material'
    ? `${row.materialCode}｜${row.materialName}｜${row.spec || ''}｜¥${row.salePrice ?? ''}`
    : `${row.locationCode}｜${row.warehouseName} / ${row.areaName}`
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = await fetchQrUrl(type, row.id)
  previewVisible.value = true
}

function labelHtml(type, row, url) {
  const line1 = type === 'material' ? row.materialCode : row.locationCode
  const line2 = type === 'material'
    ? `${row.materialName} ${row.spec || ''} ¥${row.salePrice ?? ''}`
    : `${row.warehouseName} / ${row.areaName}`
  return `<div class="label"><img src="${url}"/><div class="t1">${line1}</div><div class="t2">${line2}</div></div>`
}

function openPrintWindow(html) {
  const win = window.open('', '_blank', 'width=800,height=600')
  win.document.write(`<html><head><title>打印标签</title><style>
    body{font-family:sans-serif;display:flex;flex-wrap:wrap;gap:12px;padding:12px}
    .label{border:1px dashed #999;padding:8px;text-align:center;width:180px;page-break-inside:avoid}
    .label img{width:160px;height:160px}
    .t1{font-weight:bold;margin-top:4px}
    .t2{font-size:12px;color:#555}
  </style></head><body>${html}<script>window.onload=()=>window.print()<\/script></body></html>`)
  win.document.close()
}

async function printSingle() {
  const { type, row } = previewRaw
  openPrintWindow(labelHtml(type, row, previewUrl.value))
}

async function printLabels(type) {
  const list = type === 'material' ? matSelected.value : locSelected.value
  if (!list.length) return
  const parts = []
  for (const row of list) {
    const url = await fetchQrUrl(type, row.id)
    parts.push(labelHtml(type, row, url))
  }
  openPrintWindow(parts.join(''))
  ElMessage.success(`已生成 ${list.length} 张标签，正在打开打印窗口`)
}

async function downloadZip(type) {
  const ids = (type === 'material' ? matSelected.value : locSelected.value).map((r) => r.id)
  const res = type === 'material' ? await batchMaterialQr(ids) : await batchLocationQr(ids)
  downloadBlob(res, type === 'material' ? '物料二维码.zip' : '库位二维码.zip')
}

// ==================== 扫码 ====================

const scanVisible = ref(false)
const manualCode = ref('')
const scanResult = ref(null)

async function resolveCode(code) {
  if (!code || !code.trim()) return
  code = code.trim()
  scanResult.value = null
  // 先按物料编号查，查不到再按库位编号查
  try {
    const m = await getMaterialByCode(code)
    scanResult.value = { type: 'material', data: m.data }
    manualCode.value = ''
    return
  } catch (e) { /* 继续尝试库位 */ }
  try {
    const res = await getLocationStock(code)
    scanResult.value = { type: 'location', code, data: res.data }
    manualCode.value = ''
  } catch (e) {
    ElMessage.warning(`未识别：${code}（既不是物料编号也不是库位编号）`)
  }
}

async function viewStock(row) {
  tab.value = 'scan'
  resolveCode(row.locationCode)
}

onMounted(load)
</script>

<style scoped>
.scan-box {
  text-align: center;
  padding: 20px 0;
}
.result-card {
  max-width: 720px;
  margin: 16px auto 0;
}
.qr-preview {
  text-align: center;
}
.qr-preview img {
  width: 220px;
  height: 220px;
}
.qr-text {
  color: #606266;
  font-size: 13px;
  margin: 8px 0 0;
}
</style>
