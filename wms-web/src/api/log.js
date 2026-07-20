import request from '../utils/request'

// 操作日志
export const pageLog = (params) => request.get('/log/page', { params })
