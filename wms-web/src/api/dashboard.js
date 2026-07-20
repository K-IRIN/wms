import request from '../utils/request'

// 数据看板
export const getSummary = () => request.get('/dashboard/summary')
export const getInoutTrend = (period) => request.get('/dashboard/inout-trend', { params: { period } })
export const getCategoryRatio = () => request.get('/dashboard/category-ratio')
export const getCheckAnalysis = () => request.get('/dashboard/check-analysis')
export const getTurnover = (slowDays = 30) => request.get('/dashboard/turnover', { params: { slowDays } })
export const getStaffPerformance = () => request.get('/dashboard/staff-performance')
export const getWarningReport = () => request.get('/dashboard/warning-report')
export const exportDashboard = () => request.get('/dashboard/export', { responseType: 'blob' })
