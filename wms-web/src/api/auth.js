import request from '../utils/request'

// 获取图形验证码
export const getCaptcha = () => request.get('/auth/captcha')
// 账号密码登录
export const login = (data) => request.post('/auth/login', data)
// 用户注册
export const register = (data) => request.post('/auth/register', data)
// 当前登录用户信息（含角色与菜单树）
export const getUserInfo = () => request.get('/auth/userinfo')
// 退出登录
export const logout = () => request.post('/auth/logout')
