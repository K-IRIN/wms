<template>
  <div class="page">
    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>基本信息</template>
          <div class="avatar-box">
            <el-upload :show-file-list="false" accept="image/*" :http-request="doUpload">
              <el-avatar :size="88" :src="avatarFullUrl" style="cursor: pointer">
                {{ (form.nickname || form.username || '?').slice(0, 1) }}
              </el-avatar>
            </el-upload>
            <div class="tip">点击头像可更换（不超过 5MB）</div>
          </div>
          <el-form :model="form" label-width="80px" style="margin-top: 12px">
            <el-form-item label="用户名">
              <el-input v-model="form.username" disabled />
            </el-form-item>
            <el-form-item label="昵称">
              <el-input v-model="form.nickname" maxlength="32" />
            </el-form-item>
            <el-form-item label="手机号">
              <el-input v-model="form.phone" maxlength="20" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="save">保存修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>修改密码</template>
          <el-form ref="pwdFormRef" :model="pwd" :rules="pwdRules" label-width="80px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwd.oldPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwd.newPassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirmPassword">
              <el-input v-model="pwd.confirmPassword" type="password" show-password />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="changing" @click="changePwd">确认修改</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getProfile, updateProfile, updatePassword, uploadAvatar } from '../../api/profile'

const form = reactive({ id: null, username: '', nickname: '', phone: '', avatar: '' })
const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdFormRef = ref()
const saving = ref(false)
const changing = ref(false)

const avatarFullUrl = computed(() => (form.avatar ? '/api' + form.avatar : ''))

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, max: 32, message: '长度 6-32 位', trigger: 'blur' },
    {
      validator: (r, v, cb) => {
        if (v && !/^(?=.*[A-Za-z])(?=.*\d)/.test(v)) cb(new Error('需同时包含字母和数字'))
        else cb()
      },
      trigger: 'blur'
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    {
      validator: (r, v, cb) => (v !== pwd.newPassword ? cb(new Error('两次输入不一致')) : cb()),
      trigger: 'blur'
    }
  ]
}

async function load() {
  const res = await getProfile()
  Object.assign(form, res.data)
}

async function save() {
  saving.value = true
  try {
    await updateProfile({ nickname: form.nickname, phone: form.phone, avatar: form.avatar })
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

async function doUpload({ file }) {
  const res = await uploadAvatar(file)
  form.avatar = res.data
  await updateProfile({ avatar: form.avatar })
  ElMessage.success('头像已更新')
}

async function changePwd() {
  await pwdFormRef.value.validate()
  changing.value = true
  try {
    await updatePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码修改成功，下次登录请使用新密码')
    pwd.oldPassword = pwd.newPassword = pwd.confirmPassword = ''
  } finally {
    changing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.avatar-box {
  text-align: center;
}
.tip {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
