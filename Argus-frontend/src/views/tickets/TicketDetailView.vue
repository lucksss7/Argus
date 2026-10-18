<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  fetchTicketDetail,
  fetchTicketExecutions,
  submitTicketForApproval,
  approveTicket,
  rejectTicket,
  executeTicket,
} from '@/api/ticket'
import type {
  TicketDetail,
  TicketExecutionRecord,
  TicketStatus,
  ExecutionResult,
  ExecutionVerification,
  PermissionLevel,
} from '@/types/ticket'
import { extractApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import PageHeaderHero from '@/components/layout/PageHeaderHero.vue'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()

const ticketId = computed(() => Number(route.params.ticketId))

// ── 数据状态 ──
const ticket = ref<TicketDetail | null>(null)
const executions = ref<TicketExecutionRecord[]>([])
const isLoading = ref(false)
const loadError = ref('')
const executionsError = ref('')

// ── 操作反馈 ──
const actionLoading = ref('')
const feedbackMsg = ref('')
const feedbackKind = ref<'success' | 'info' | 'warning' | 'error'>('success')

// ── 状态 / 执行结果 / 权限级别元信息 ──
const STATUS_META: Record<TicketStatus, { label: string; tone: string }> = {
  OPEN: { label: '待处理', tone: 'blue' },
  PENDING_APPROVAL: { label: '待审批', tone: 'amber' },
  APPROVED: { label: '已通过', tone: 'green' },
  IN_PROGRESS: { label: '执行中', tone: 'indigo' },
  RESOLVED: { label: '已解决', tone: 'green' },
  CLOSED: { label: '已关闭', tone: 'slate' },
  REJECTED: { label: '已拒绝', tone: 'red' },
  CANCELLED: { label: '已取消', tone: 'gray' },
  ABORTED: { label: '已终止', tone: 'red' },
}

const EXECUTION_RESULT_META: Record<ExecutionResult, { label: string; tone: string }> = {
  PENDING: { label: '待执行', tone: 'slate' },
  SUCCESS: { label: '成功', tone: 'green' },
  FAILED: { label: '失败', tone: 'red' },
  UNCERTAIN: { label: '结果不确定', tone: 'amber' },
}

const VERIFICATION_META: Record<ExecutionVerification, { label: string; tone: string }> = {
  CONFIRMED_SUCCESS: { label: '已核验：确认成功', tone: 'green' },
  CONFIRMED_NOT_EXECUTED: { label: '已核验：确认未执行', tone: 'red' },
}

const PERMISSION_LABELS: Record<PermissionLevel, string> = {
  READ_ONLY: '只读',
  STANDARD: '标准',
  ADMIN: '管理员',
}

// ── 角色与按钮显隐（仅控制前端显隐，最终权限由后端校验） ──
const isAdmin = computed(() => authStore.isAdmin)

const isCreator = computed(() => {
  const userId = authStore.currentUser?.userId
  const creatorId = ticket.value?.creatorUserId
  return userId != null && creatorId != null && userId === creatorId
})

const canSubmit = computed(() => isCreator.value && ticket.value?.status === 'OPEN')
const canApprove = computed(() => isAdmin.value && ticket.value?.status === 'PENDING_APPROVAL')
const canReject = computed(() => isAdmin.value && ticket.value?.status === 'PENDING_APPROVAL')

// 历史中是否存在结果不确定（UNCERTAIN）的执行记录。
// 后端 begin 事务会调用 requireNoUnresolvedUncertain：只要存在任何 UNCERTAIN 记录即禁止再次执行。
const hasUncertainHistory = computed(
  () => executions.value.some((e) => e.result === 'UNCERTAIN'),
)

// 仅当 APPROVED 且历史中无 UNCERTAIN 记录时才允许发起执行。
// 注意：FAILED 记录不在此列（后端仅统计 UNCERTAIN），失败后工单回到 APPROVED 可正常重试。
const canExecute = computed(
  () => isAdmin.value && ticket.value?.status === 'APPROVED' && !hasUncertainHistory.value,
)

const hasActions = computed(() => canSubmit.value || canApprove.value || canReject.value || canExecute.value)

// 工单回到 APPROVED 但历史存在 UNCERTAIN 记录：后端会拒绝执行，前端同步禁用执行入口并突出提示
const executionBlockedByUncertain = computed(
  () => ticket.value?.status === 'APPROVED' && hasUncertainHistory.value,
)

// ── 工具函数 ──
function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (match) {
    return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}`
  }
  const datePart = iso.split(/[T ]/)[0] ?? ''
  return datePart ? datePart.replace(/-/g, '.') : iso
}

/** 状态元信息兜底：后端若返回未知状态，降级为灰阶并回显原始状态字符串，避免运行时崩溃 */
function statusMetaOf(status: TicketStatus): { label: string; tone: string } {
  return STATUS_META[status] ?? { label: status, tone: 'slate' }
}

/** 人工核验结论徽标文案（null 表示尚未核验，返回空串由模板 v-if 拦截） */
function verificationLabel(status: ExecutionVerification | null): string {
  return status ? VERIFICATION_META[status].label : ''
}

/** 人工核验结论徽标色调（null 时返回 slate 兜底，模板仅在非空时调用） */
function verificationTone(status: ExecutionVerification | null): string {
  return status ? VERIFICATION_META[status].tone : 'slate'
}

function goBack() {
  router.push('/app/tickets')
}

function clearFeedback() {
  feedbackMsg.value = ''
}

async function confirmAction(
  message: string,
  title: string,
  confirmText: string,
  type: 'warning' | 'info' = 'warning',
): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, title, {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type,
    })
    return true
  } catch {
    return false
  }
}

// ── 数据加载 ──
onMounted(() => {
  void loadAll()
})

async function loadAll() {
  isLoading.value = true
  loadError.value = ''
  executionsError.value = ''
  try {
    const [detail, history] = await Promise.all([
      fetchTicketDetail(ticketId.value),
      fetchTicketExecutions(ticketId.value).catch((err) => {
        executionsError.value = extractApiError(err, '加载执行历史失败')
        return [] as TicketExecutionRecord[]
      }),
    ])
    ticket.value = detail
    executions.value = history
  } catch (err) {
    loadError.value = extractApiError(err, '加载工单详情失败')
    ticket.value = null
    executions.value = []
  } finally {
    isLoading.value = false
  }
}

// ── 状态操作 ──
async function handleSubmit() {
  if (actionLoading.value || !ticket.value) return
  const ok = await confirmAction(
    '确定提交审批吗？提交后工单将进入「待审批」状态。',
    '确认提交审批',
    '提交审批',
    'info',
  )
  if (!ok) return
  actionLoading.value = 'submit'
  clearFeedback()
  try {
    await submitTicketForApproval(ticketId.value)
    feedbackKind.value = 'success'
    feedbackMsg.value = '已提交审批。'
    await loadAll()
  } catch (err) {
    feedbackKind.value = 'error'
    feedbackMsg.value = extractApiError(err, '提交审批失败')
  } finally {
    actionLoading.value = ''
  }
}

async function handleApprove() {
  if (actionLoading.value || !ticket.value) return
  const ok = await confirmAction('确定审批通过该工单吗？', '确认审批通过', '审批通过', 'info')
  if (!ok) return
  actionLoading.value = 'approve'
  clearFeedback()
  try {
    await approveTicket(ticketId.value)
    feedbackKind.value = 'success'
    feedbackMsg.value = '已审批通过。'
    await loadAll()
  } catch (err) {
    feedbackKind.value = 'error'
    feedbackMsg.value = extractApiError(err, '审批通过失败')
  } finally {
    actionLoading.value = ''
  }
}

async function handleReject() {
  if (actionLoading.value || !ticket.value) return
  const ok = await confirmAction(
    '确定拒绝该工单吗？拒绝后工单将进入「已拒绝」状态。',
    '确认拒绝',
    '审批拒绝',
    'warning',
  )
  if (!ok) return
  actionLoading.value = 'reject'
  clearFeedback()
  try {
    await rejectTicket(ticketId.value)
    feedbackKind.value = 'success'
    feedbackMsg.value = '已审批拒绝。'
    await loadAll()
  } catch (err) {
    feedbackKind.value = 'error'
    feedbackMsg.value = extractApiError(err, '审批拒绝失败')
  } finally {
    actionLoading.value = ''
  }
}

async function handleExecute() {
  if (actionLoading.value || !ticket.value) return
  const ok = await confirmAction('确定执行账号开通吗？', '确认执行', '执行', 'warning')
  if (!ok) return
  actionLoading.value = 'execute'
  clearFeedback()
  try {
    const result = await executeTicket(ticketId.value)
    if (result.result === 'SUCCESS') {
      feedbackKind.value = 'success'
      feedbackMsg.value = result.message || '执行成功，账号已开通。'
    } else if (result.result === 'UNCERTAIN') {
      feedbackKind.value = 'warning'
      feedbackMsg.value = result.message
        ? `执行结果不确定，需要人工核实：${result.message}`
        : '执行结果不确定，需要人工核实。'
    } else {
      feedbackKind.value = 'error'
      feedbackMsg.value = result.message || '执行失败，工单已回到「已通过」状态，可再次执行。'
    }
    await loadAll()
  } catch (err) {
    feedbackKind.value = 'error'
    feedbackMsg.value = extractApiError(err, '执行失败')
  } finally {
    actionLoading.value = ''
  }
}
</script>

<template>
  <div class="ticket-detail-page">
    <!-- ── 页头 ── -->
    <PageHeaderHero
      eyebrow="Ticket Center"
      :title="ticket ? `工单 ${ticket.ticketNo}` : '工单详情'"
      :description="ticket ? ticket.title : '查看工单详情、状态流转与执行历史'"
    >
      <template #actions>
        <button class="action-btn" @click="goBack">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M19 12H5m7 7l-7-7 7-7" />
          </svg>
          返回列表
        </button>
      </template>
    </PageHeaderHero>

    <!-- ── 反馈消息 ── -->
    <div v-if="feedbackMsg" class="feedback-area">
      <p class="feedback" :class="`feedback--${feedbackKind}`">{{ feedbackMsg }}</p>
    </div>

    <!-- ── 加载状态 ── -->
    <div v-if="isLoading" class="state-block">
      <div class="spinner"></div>
      <p>正在加载工单详情...</p>
    </div>

    <!-- ── 加载失败 ── -->
    <div v-else-if="loadError" class="state-block state-block--error">
      <p>{{ loadError }}</p>
      <button class="retry-btn" @click="loadAll">重试</button>
    </div>

    <template v-else-if="ticket">
      <!-- ── 风险提示：APPROVED 但存在未核实 UNCERTAIN（后端禁止再次执行） ── -->
      <div v-if="executionBlockedByUncertain" class="risk-banner">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
          <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z" />
        </svg>
        <div class="risk-banner__text">
          <strong>存在结果不确定的执行记录，需人工核实</strong>
          <p>该工单历史中存在结果不确定（UNCERTAIN）的执行尝试，外部操作可能已成功也可能未成功。系统已禁止再次执行，请先人工核实后处理；在核实完成前「执行开通」入口不可用。</p>
        </div>
      </div>

      <!-- ── 工单信息卡 ── -->
      <section class="detail-card">
        <div class="detail-card__header">
          <div class="detail-card__title">
            <code class="ticket-no">{{ ticket.ticketNo }}</code>
            <h2>{{ ticket.title }}</h2>
          </div>
          <span class="status-pill" :class="`status-pill--${statusMetaOf(ticket.status).tone}`">
            <span class="status-pill__dot"></span>
            {{ statusMetaOf(ticket.status).label }}
          </span>
        </div>

        <dl class="detail-grid">
          <div class="detail-field">
            <dt>工单类型</dt>
            <dd>账号开通（ACCOUNT_PROVISIONING）</dd>
          </div>
          <div class="detail-field">
            <dt>优先级</dt>
            <dd>{{ ticket.priority ?? '—' }}</dd>
          </div>
          <div class="detail-field">
            <dt>目标系统</dt>
            <dd>{{ ticket.systemName }}</dd>
          </div>
          <div class="detail-field">
            <dt>目标用户名</dt>
            <dd>{{ ticket.targetUsername }}</dd>
          </div>
          <div class="detail-field">
            <dt>权限级别</dt>
            <dd>{{ PERMISSION_LABELS[ticket.permissionLevel] }}</dd>
          </div>
          <div class="detail-field">
            <dt>申请人</dt>
            <dd>#{{ ticket.creatorUserId }}</dd>
          </div>
          <div class="detail-field">
            <dt>审批人</dt>
            <dd>{{ ticket.approverUserId != null ? `#${ticket.approverUserId}` : '—' }}</dd>
          </div>
          <div class="detail-field">
            <dt>处理结果</dt>
            <dd>{{ ticket.resolution ?? '—' }}</dd>
          </div>
          <div class="detail-field">
            <dt>创建时间</dt>
            <dd>{{ formatDateTime(ticket.createdAt) }}</dd>
          </div>
          <div class="detail-field">
            <dt>更新时间</dt>
            <dd>{{ formatDateTime(ticket.updatedAt) }}</dd>
          </div>
          <div class="detail-field">
            <dt>版本</dt>
            <dd>{{ ticket.version }}</dd>
          </div>
        </dl>

        <div class="detail-card__description">
          <dt>申请原因</dt>
          <dd>{{ ticket.description }}</dd>
        </div>

        <!-- ── 状态操作 ── -->
        <div v-if="hasActions" class="detail-card__actions">
          <button
            v-if="canSubmit"
            class="act-btn act-btn--primary"
            :disabled="actionLoading !== ''"
            @click="handleSubmit"
          >
            {{ actionLoading === 'submit' ? '提交中...' : '提交审批' }}
          </button>
          <button
            v-if="canApprove"
            class="act-btn act-btn--success"
            :disabled="actionLoading !== ''"
            @click="handleApprove"
          >
            {{ actionLoading === 'approve' ? '审批中...' : '审批通过' }}
          </button>
          <button
            v-if="canReject"
            class="act-btn act-btn--danger"
            :disabled="actionLoading !== ''"
            @click="handleReject"
          >
            {{ actionLoading === 'reject' ? '拒绝中...' : '审批拒绝' }}
          </button>
          <button
            v-if="canExecute"
            class="act-btn act-btn--primary"
            :disabled="actionLoading !== ''"
            @click="handleExecute"
          >
            {{ actionLoading === 'execute' ? '执行中...' : '执行开通' }}
          </button>
        </div>
      </section>

      <!-- ── 执行历史 ── -->
      <section class="history-card">
        <h3 class="history-card__title">执行历史</h3>

        <p v-if="executionsError" class="feedback feedback--error">{{ executionsError }}</p>

        <div v-else-if="executions.length === 0" class="history-empty">
          暂无执行记录
        </div>

        <div v-else class="history-list">
          <div v-for="rec in executions" :key="rec.id" class="history-row">
            <div class="history-row__meta">
              <span class="history-attempt">第 {{ rec.attemptNo }} 次</span>
              <span class="history-type">账号开通</span>
              <span class="result-pill" :class="`result-pill--${EXECUTION_RESULT_META[rec.result].tone}`">
                <span class="result-pill__dot"></span>
                {{ EXECUTION_RESULT_META[rec.result].label }}
              </span>
            </div>
            <div class="history-row__body">
              <p v-if="rec.result === 'UNCERTAIN'" class="history-uncertain">
                执行结果不确定，需要人工核实，不能当作普通失败自动重试。
              </p>
              <p v-if="rec.errorMessage" class="history-error">{{ rec.errorMessage }}</p>
              <div v-if="rec.verificationStatus" class="history-verification">
                <span class="verify-badge" :class="`verify-badge--${verificationTone(rec.verificationStatus)}`">
                  <span class="verify-badge__dot"></span>
                  {{ verificationLabel(rec.verificationStatus) }}
                </span>
                <span v-if="rec.verifiedAt" class="history-verify-time">
                  核验于 {{ formatDateTime(rec.verifiedAt) }}
                </span>
              </div>
              <p class="history-time">
                开始 {{ formatDateTime(rec.startedAt) }} · 结束 {{ rec.finishedAt ? formatDateTime(rec.finishedAt) : '—' }}
              </p>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* ════════════ Page Layout ════════════ */
.ticket-detail-page {
  width: 100%;
  min-height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* ════════════ Header Button ════════════ */
.action-btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 16px;
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  font-weight: 600;
  border: 1px solid var(--border-default);
  background: var(--surface-white);
  color: var(--text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
}

.action-btn:hover {
  color: var(--text-primary);
  border-color: var(--text-muted);
  background: var(--surface-subtle);
}

/* ════════════ Feedback ════════════ */
.feedback-area {
  margin: 0;
}

.feedback {
  margin: 0;
  padding: 10px 14px;
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  line-height: 1.5;
}

.feedback--success {
  background: rgba(16, 185, 129, 0.08);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.15);
}

.feedback--info {
  background: rgba(74, 144, 217, 0.08);
  color: var(--brand-primary);
  border: 1px solid rgba(74, 144, 217, 0.18);
}

.feedback--warning {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
  border: 1px solid rgba(245, 158, 11, 0.2);
}

.feedback--error {
  background: rgba(239, 68, 68, 0.08);
  color: var(--el-color-danger);
  border: 1px solid rgba(239, 68, 68, 0.15);
}

/* ════════════ State Blocks ════════════ */
.state-block {
  text-align: center;
  padding: 64px 24px;
  background: var(--surface-white);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
}

.state-block p {
  margin: 14px 0 0;
  font-size: 0.9rem;
  color: var(--text-muted);
}

.state-block--error p {
  color: var(--el-color-danger);
  margin-bottom: 14px;
}

.spinner {
  width: 32px;
  height: 32px;
  margin: 0 auto;
  border: 3px solid var(--surface-muted);
  border-top-color: var(--brand-primary);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.retry-btn {
  padding: 8px 20px;
  font-family: inherit;
  font-size: 0.85rem;
  font-weight: 600;
  color: var(--brand-primary);
  background: var(--surface-accent);
  border: 1px solid rgba(74, 144, 217, 0.15);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: all 0.15s ease;
}

.retry-btn:hover {
  background: rgba(74, 144, 217, 0.12);
}

/* ════════════ Risk Banner ════════════ */
.risk-banner {
  display: flex;
  gap: 12px;
  padding: 14px 18px;
  border-radius: var(--radius-md);
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.28);
  color: #b45309;
}

.risk-banner svg {
  flex-shrink: 0;
  margin-top: 1px;
  color: #d97706;
}

.risk-banner__text strong {
  display: block;
  font-size: 0.9rem;
  font-weight: 700;
  color: #92400e;
}

.risk-banner__text p {
  margin: 4px 0 0;
  font-size: 0.84rem;
  line-height: 1.6;
  color: #b45309;
}

/* ════════════ Detail Card ════════════ */
.detail-card,
.history-card {
  background: var(--surface-white);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  padding: 24px 28px;
}

.detail-card__header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--border-subtle);
  margin-bottom: 18px;
}

.detail-card__title {
  min-width: 0;
}

.ticket-no {
  display: inline-block;
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 0.78rem;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  padding: 3px 8px;
  border-radius: 4px;
  margin-bottom: 6px;
}

.detail-card__title h2 {
  margin: 0;
  font-size: 1.15rem;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.35;
  overflow-wrap: anywhere;
}

/* 状态 pill */
.status-pill,
.result-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
  white-space: nowrap;
  flex-shrink: 0;
}

.status-pill__dot,
.result-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.status-pill--blue, .result-pill--blue { color: #2563eb; background: rgba(37, 99, 235, 0.08); }
.status-pill--amber, .result-pill--amber { color: #d97706; background: rgba(245, 158, 11, 0.1); }
.status-pill--green, .result-pill--green { color: #059669; background: rgba(16, 185, 129, 0.1); }
.status-pill--indigo, .result-pill--indigo { color: #4f46e5; background: rgba(99, 102, 241, 0.1); }
.status-pill--slate, .result-pill--slate { color: #475569; background: rgba(100, 116, 139, 0.1); }
.status-pill--red, .result-pill--red { color: #dc2626; background: rgba(239, 68, 68, 0.1); }
.status-pill--gray, .result-pill--gray { color: #6b7280; background: rgba(107, 114, 128, 0.1); }

/* 详情字段网格 */
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px 24px;
  margin: 0;
}

.detail-field {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.detail-field dt {
  font-size: 0.7rem;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.detail-field dd {
  margin: 0;
  font-size: 0.9rem;
  color: var(--text-primary);
  overflow-wrap: anywhere;
}

.detail-card__description {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--border-subtle);
}

.detail-card__description dt {
  font-size: 0.7rem;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.detail-card__description dd {
  margin: 6px 0 0;
  font-size: 0.9rem;
  color: var(--text-primary);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 操作按钮 */
.detail-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 20px;
  padding-top: 20px;
  border-top: 1px solid var(--border-subtle);
}

.act-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 20px;
  border-radius: var(--radius-sm);
  font-size: 0.86rem;
  font-weight: 600;
  font-family: inherit;
  border: 1px solid;
  cursor: pointer;
  transition: all 0.2s ease;
}

.act-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.act-btn--primary {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: #fff;
}

.act-btn--primary:hover:not(:disabled) {
  background: var(--brand-primary-dark);
  border-color: var(--brand-primary-dark);
}

.act-btn--success {
  background: #10b981;
  border-color: #10b981;
  color: #fff;
}

.act-btn--success:hover:not(:disabled) {
  background: #059669;
  border-color: #059669;
}

.act-btn--danger {
  background: transparent;
  border-color: rgba(239, 68, 68, 0.3);
  color: #ef4444;
}

.act-btn--danger:hover:not(:disabled) {
  background: rgba(239, 68, 68, 0.08);
  border-color: rgba(239, 68, 68, 0.45);
}

/* ════════════ History Card ════════════ */
.history-card__title {
  margin: 0 0 16px;
  font-size: 1rem;
  font-weight: 700;
  color: var(--text-primary);
}

.history-empty {
  padding: 24px 0;
  text-align: center;
  font-size: 0.88rem;
  color: var(--text-muted);
}

.history-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.history-row {
  padding: 14px 16px;
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-sm);
}

.history-row__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.history-attempt {
  font-size: 0.85rem;
  font-weight: 700;
  color: var(--text-primary);
}

.history-type {
  font-size: 0.78rem;
  color: var(--text-muted);
}

.history-row__body {
  margin-top: 8px;
}

.history-uncertain {
  margin: 0;
  padding: 8px 12px;
  border-radius: var(--radius-sm);
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
  font-size: 0.84rem;
  font-weight: 600;
  line-height: 1.5;
}

.history-error {
  margin: 8px 0 0;
  font-size: 0.84rem;
  color: var(--el-color-danger);
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.history-verification {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 8px 0 0;
  flex-wrap: wrap;
}

.verify-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
  white-space: nowrap;
}

.verify-badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.verify-badge--green { color: #059669; background: rgba(16, 185, 129, 0.1); }
.verify-badge--red { color: #dc2626; background: rgba(239, 68, 68, 0.1); }

.history-verify-time {
  font-size: 0.8rem;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.history-time {
  margin: 8px 0 0;
  font-size: 0.8rem;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

/* ════════════ Responsive ════════════ */
@media (max-width: 720px) {
  .detail-card,
  .history-card {
    padding: 18px 16px;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
