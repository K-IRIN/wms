import request from '../utils/request'

// 查询个人信息
export const getProfile = () => request.get('/profile/info')
// 修改个人信息（昵称/电话）
export const updateProfile = (data) => request.put('/profile/info', data)
// 修改密码（需原密码）
export const updatePassword = (data) => request.put('/profile/password', data)
// 上传头像
export const uploadAvatar = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request.post('/profile/avatar', fd, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
