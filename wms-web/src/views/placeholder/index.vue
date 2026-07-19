<template>
  <el-empty :description="`「${$route.meta.title || '该模块'}」将在后续阶段开发，敬请期待`">
    <el-button type="primary" @click="$router.push('/dashboard')">返回数据看板</el-button>
  </el-empty>
</template>
