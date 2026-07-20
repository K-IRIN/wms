<template>
  <div class="page">
    <el-card shadow="never">
      <el-form inline>
        <el-form-item label="操作人">
          <el-input v-model="query.username" placeholder="模糊搜索" clearable style="width: 150px"
            @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="操作内容">
          <el-input v-model="query.operation" placeholder="如：审核、登录" clearable style="width: 160px"
            @keyup.enter="load(1)" @clear="load(1)" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker v-model="timeRange" type="datetimerange" range-separator="至"
            start-placeholder="开始时间" end-placeholder="结束时间" value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 340px" @change="load(1)" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load(1)">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="rows" v-loading="loading" border stripe>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="username" label="操作人" width="160" />
        <el-table-column prop="operation" label="操作内容" width="200" />
        <el-table-column prop="method" label="调用方法" min-width="200" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" />
        <el-table-column label="参数" min-width="220">
          <template #default="{ row }">
            <el-popover v-if="row.params" trigger="hover" width="420" placement="left">
              <pre class="params-detail">{{ row.params }}</pre>
              <template #reference>
                <span class="params-preview">{{ row.params }}</span>
              </template>
            </el-popover>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="操作时间" width="165">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
      </el-table>

      <el-pagination style="margin-top: 12px; justify-content: flex-end" background
        layout="total, sizes, prev, pager, next" :total="total" v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize" :page-sizes="[10, 20, 50, 100]" @change="load()" />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { pageLog } from '../../api/log.js'

const loading = ref(false)
const rows = ref([])
const total = ref(0)
const timeRange = ref(null)
const query = ref({ pageNum: 1, pageSize: 20, username: '', operation: '' })

function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

async function load(pageNum) {
  if (pageNum) query.value.pageNum = pageNum
  const params = { ...query.value }
  if (timeRange.value && timeRange.value.length === 2) {
    params.startTime = timeRange.value[0]
    params.endTime = timeRange.value[1]
  } else {
    delete params.startTime
    delete params.endTime
  }
  loading.value = true
  try {
    const res = await pageLog(params)
    rows.value = res.data.records
    total.value = res.data.total
  } finally {
    loading.value = false
  }
}

onMounted(() => load())
</script>

<style scoped>
.page {
  padding: 12px;
}

.params-preview {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  color: #909399;
  font-size: 12px;
  cursor: pointer;
}

.params-detail {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
}
</style>
