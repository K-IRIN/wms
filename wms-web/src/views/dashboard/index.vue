<template>
  <div>
    <!-- 统计卡片（静态演示数据，阶段 12 接入真实统计接口） -->
    <el-row :gutter="16">
      <el-col :span="6" v-for="card in cards" :key="card.title">
        <el-card shadow="hover">
          <div class="card-title">{{ card.title }}</div>
          <div class="card-num">{{ card.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 16px">
      <h2>🎉 前后端骨架搭建成功</h2>
      <p>这是阶段 2 的占位首页。左侧菜单对应需求文档的 9 大模块，页面将在阶段 4~12 逐个实现。</p>
      <p>统计图表（ECharts）将在阶段 12「数据统计与可视化分析」实现。</p>
      <el-button type="primary" :loading="loading" @click="ping">测试后端连通性</el-button>
    </el-card>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import request from '../../utils/request'

const loading = ref(false)
const cards = [
  { title: '今日入库（单）', value: 0 },
  { title: '今日出库（单）', value: 0 },
  { title: '库存预警（项）', value: 0 },
  { title: '临期物料（项）', value: 0 }
]

const ping = async () => {
  loading.value = true
  try {
    const res = await request.get('/test/ping')
    ElMessage.success(`后端连通成功：${res.data}`)
  } catch (e) {
    // request.js 已统一提示
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.card-title {
  color: #909399;
  font-size: 14px;
}
.card-num {
  font-size: 28px;
  font-weight: bold;
  margin-top: 8px;
}
</style>
