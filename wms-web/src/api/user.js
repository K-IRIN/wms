import request from '../utils/request'

// 用户分页查询
export const pageUser = (params) => request.get('/system/user/page', { params })
// 新增用户
export const addUser = (data) => request.post('/system/user', data)
// 编辑用户
export const updateUser = (data) => request.put('/system/user', data)
// 删除用户
export const deleteUser = (id) => request.delete(`/system/user/${id}`)
// 批量启用/禁用
export const updateUserStatus = (data) => request.put('/system/user/status', data)
// 重置密码
export const resetUserPwd = (id) => request.put(`/system/user/resetPwd/${id}`)
// 角色下拉列表
export const listRole = () => request.get('/system/role/list')
