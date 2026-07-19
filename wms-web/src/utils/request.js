import axios from 'axios'
import { ElMessage } from 'element-plus'

// axios 二次封装：统一 baseURL、自动携带 Token、统一错误处理
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 请求拦截器：自动携带 Token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('wms-token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一处理业务码与错误提示
request.interceptors.response.use(
  (response) => {
    // 文件流（导出 Excel 等）直接返回
    if (response.config.responseType === 'blob') return response
    const res = response.data
    if (res.code === 200) return res
    ElMessage.error(res.msg || '请求失败')
    if (res.code === 401) {
      localStorage.removeItem('wms-token')
      window.location.href = '/login'
    }
    return Promise.reject(new Error(res.msg || '请求失败'))
  },
  (error) => {
    ElMessage.error(error.response?.data?.msg || error.message || '网络异常')
    return Promise.reject(error)
  }
)

export default request
