<script setup lang="ts">
import { ref, watch } from 'vue'
import { createTicket } from '@/api/ticket'
import type { CreateTicketPayload, CreateTicketResult, PermissionLevel } from '@/types/ticket'
import { extractApiError } from '@/api/http'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  created: [result: CreateTicketResult]
}>()

// ── 表单字段 ──
const title = ref('')
const systemName = ref('')
const targetUsername = ref('')
const permissionLevel = ref<PermissionLevel | ''>('')
const description = ref('')
const priority = ref('')

// ── 幂等键：每次「打开弹窗」视为一次新的创建操作，生成一次；本次操作内（含重试）复用同一键 ──
const idempotencyKey = ref('')

// ── 提交状态 ──
const submitting = ref(false)
const validationError = ref('')
const submitError = ref('')

const PERMISSION_OPTIONS: { value: PermissionLevel; label: string }[] = [
  { value: 'READ_ONLY', label: '只读' },
  { value: 'STANDARD', label: '标准' },
  { value: 'ADMIN', label: '管理员' },
]

// 打开弹窗时重置表单并生成新的幂等键；关闭后下次打开将生成全新键
watch(
  () => props.visible,
  (v) => {
    if (v) {
      title.value = ''
      systemName.value = ''
      targetUsername.value = ''
      permissionLevel.value = ''
      description.value = ''
      priority.value = ''
      idempotencyKey.value = generateIdempotencyKey()
      validationError.value = ''
      submitError.value = ''
    }
  },
)

/**
 * 生成本次创建操作的幂等键（全局唯一，长度 ≤ 128）。
 *
 * 优先使用 crypto.randomUUID；在不满足安全上下文的环境下降级为
 * 「时间戳 + 随机串」组合。该键不展示在表单中，也不写入任何本地存储。
 */
function generateIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `ticket-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`
}

function handleSubmit() {
  // 防止连续点击重复提交：提交中直接忽略后续触发
  if (submitting.value) return
  validationError.value = ''
  submitError.value = ''

  const trimmedTitle = title.value.trim()
  const trimmedSystem = systemName.value.trim()
  const trimmedTarget = targetUsername.value.trim()
  const level = permissionLevel.value
  const trimmedDescription = description.value.trim()
  const trimmedPriority = priority.value.trim()

  if (!trimmedTitle) {
    validationError.value = '请输入标题'
    return
  }
  if (!trimmedSystem) {
    validationError.value = '请输入目标系统'
    return
  }
  if (!trimmedTarget) {
    validationError.value = '请输入目标用户名'
    return
  }
  if (!level) {
    validationError.value = '请选择权限级别'
    return
  }
  if (!trimmedDescription) {
    validationError.value = '请输入申请原因'
    return
  }

  const payload: CreateTicketPayload = {
    idempotencyKey: idempotencyKey.value,
    type: 'ACCOUNT_PROVISIONING',
    title: trimmedTitle,
    systemName: trimmedSystem,
    targetUsername: trimmedTarget,
    permissionLevel: level,
    description: trimmedDescription,
    // 后端将空 / 空白优先级归一化为 null，这里保持一致
    priority: trimmedPriority || null,
  }

  submitting.value = true
  void submitTicket(payload)
}

async function submitTicket(payload: CreateTicketPayload) {
  try {
    const result = await createTicket(payload)
    // 成功后交由父组件关闭弹窗并刷新列表；提交失败时弹窗保持打开，幂等键不变，可安全重试
    emit('created', result)
  } catch (err) {
    submitError.value = extractApiError(err, '创建工单失败')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    width="640px"
    top="10vh"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => { if (!v) emit('update:visible', false) }"
  >
    <template #header>
      <div class="modal-header">
        <h2 class="modal-header__title">创建工单</h2>
        <span class="modal-header__badge">账号开通</span>
      </div>
    </template>

    <div class="modal-body">
      <p v-if="validationError" class="modal-error">{{ validationError }}</p>
      <p v-if="submitError" class="modal-error">{{ submitError }}</p>

      <div class="form-field">
        <span class="form-field__label">工单类型</span>
        <div class="form-field__readonly">账号开通（ACCOUNT_PROVISIONING）</div>
      </div>

      <label class="form-field">
        <span class="form-field__label">标题 <small>（必填，最多 200 字）</small></span>
        <input
          v-model="title"
          type="text"
          maxlength="200"
          placeholder="例如：为张三开通生产环境只读账号"
          class="form-field__input"
          @keyup.enter="handleSubmit"
        />
      </label>

      <label class="form-field">
        <span class="form-field__label">目标系统 <small>（必填，最多 100 字）</small></span>
        <input
          v-model="systemName"
          type="text"
          maxlength="100"
          placeholder="例如：prod-erp"
          class="form-field__input"
          @keyup.enter="handleSubmit"
        />
      </label>

      <label class="form-field">
        <span class="form-field__label">目标用户名 <small>（必填，最多 100 字）</small></span>
        <input
          v-model="targetUsername"
          type="text"
          maxlength="100"
          placeholder="需要开通账号的用户名"
          class="form-field__input"
          @keyup.enter="handleSubmit"
        />
      </label>

      <label class="form-field">
        <span class="form-field__label">权限级别 <small>（必填）</small></span>
        <select v-model="permissionLevel" class="form-field__input form-field__select">
          <option value="" disabled>请选择权限级别</option>
          <option v-for="opt in PERMISSION_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>
      </label>

      <label class="form-field">
        <span class="form-field__label">申请原因 <small>（必填）</small></span>
        <textarea
          v-model="description"
          rows="5"
          placeholder="说明开通该账号的业务原因与用途"
          class="form-field__textarea"
        />
      </label>

      <label class="form-field">
        <span class="form-field__label">优先级 <small>（可选，最多 16 字）</small></span>
        <input
          v-model="priority"
          type="text"
          maxlength="16"
          placeholder="例如：高 / 普通（留空表示未指定）"
          class="form-field__input"
          @keyup.enter="handleSubmit"
        />
      </label>
    </div>

    <template #footer>
      <el-button :disabled="submitting" @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">
        {{ submitting ? '创建中...' : '创建工单' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.modal-header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-header__title {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 1.1rem;
  font-weight: 700;
  color: var(--text-primary);
  margin: 0;
}

.modal-header__badge {
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 0.7rem;
  font-weight: 700;
  background: var(--surface-accent);
  color: var(--brand-primary);
}

.modal-body {
  display: grid;
  gap: 16px;
  padding: 4px 0;
}

.modal-error {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  background: rgba(239, 68, 68, 0.08);
  color: var(--el-color-danger);
  font-size: 0.86rem;
  line-height: 1.5;
}

.form-field {
  display: grid;
  gap: 5px;
}

.form-field__label {
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--text-secondary);
}

.form-field__label small {
  font-weight: 400;
  color: var(--text-muted);
}

.form-field__input,
.form-field__textarea {
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
  font-family: inherit;
  color: var(--text-primary);
  background: var(--surface-white);
  transition: border-color 0.15s ease;
}

.form-field__input:focus,
.form-field__textarea:focus {
  outline: none;
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(74, 144, 217, 0.1);
}

.form-field__textarea {
  resize: vertical;
  min-height: 96px;
  line-height: 1.6;
}

.form-field__select {
  cursor: pointer;
}

.form-field__readonly {
  padding: 9px 12px;
  border: 1px dashed var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 0.9rem;
  color: var(--text-secondary);
  background: var(--surface-subtle);
}
</style>
