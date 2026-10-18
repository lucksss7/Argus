<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { fetchTickets } from '@/api/ticket'
import type { CreateTicketResult, TicketDetail, TicketStatus } from '@/types/ticket'
import CreateTicketModal from './components/CreateTicketModal.vue'
import { extractApiError } from '@/api/http'
import PageHeaderHero from '@/components/layout/PageHeaderHero.vue'

const router = useRouter()

// ── 数据状态 ──
const tickets = ref<TicketDetail[]>([])
const isLoading = ref(false)
const errorMsg = ref('')

// ── 创建工单 ──
const showCreateModal = ref(false)
const feedbackMsg = ref('')
const feedbackKind = ref<'success' | 'info'>('success')

// ── 本地筛选 / 搜索 ──
const searchQuery = ref('')
const statusFilter = ref('')

// ── 状态元信息 ──
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

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待处理' },
  { value: 'PENDING_APPROVAL', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'IN_PROGRESS', label: '执行中' },
  { value: 'RESOLVED', label: '已解决' },
  { value: 'CLOSED', label: '已关闭' },
  { value: 'REJECTED', label: '已拒绝' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'ABORTED', label: '已终止' },
]

/** 状态元信息兜底：后端若返回未知状态，降级为灰阶并回显原始状态字符串，避免运行时崩溃 */
function statusMetaOf(status: TicketStatus): { label: string; tone: string } {
  return STATUS_META[status] ?? { label: status, tone: 'slate' }
}

// ── 计算属性 ──
const filteredTickets = computed(() => {
  const query = searchQuery.value.trim().toLowerCase()
  return tickets.value.filter((t) => {
    if (statusFilter.value && t.status !== statusFilter.value) return false
    if (!query) return true
    return t.title.toLowerCase().includes(query) || t.ticketNo.toLowerCase().includes(query)
  })
})

// ── 格式化工具 ──
/**
 * 格式化创建时间。
 *
 * 后端将 LocalDateTime 序列化为无时区 ISO 字符串（如 2026-10-15T14:30:00），
 * 此处按字符串切片直接展示，不做 Date 解析，避免任何时区偏移假设；
 * 对无法识别的格式降级为仅展示日期部分。
 */
function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  const match = iso.match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (match) {
    return `${match[1]}-${match[2]}-${match[3]} ${match[4]}:${match[5]}`
  }
  const datePart = iso.split(/[T ]/)[0] ?? ''
  return datePart ? datePart.replace(/-/g, '.') : iso
}

function priorityText(priority: string | null): string {
  const trimmed = priority?.trim()
  return trimmed ? trimmed : '—'
}

// ── 详情入口 ──
function viewDetail(ticket: TicketDetail) {
  router.push(`/app/tickets/${ticket.id}`)
}

// ── 数据加载 ──
onMounted(() => {
  void loadTickets()
})

async function loadTickets() {
  isLoading.value = true
  errorMsg.value = ''
  try {
    tickets.value = await fetchTickets()
  } catch (err) {
    errorMsg.value = extractApiError(err, '加载工单列表失败')
    tickets.value = []
  } finally {
    isLoading.value = false
  }
}

// ── 创建工单 ──
function openCreateModal() {
  feedbackMsg.value = ''
  showCreateModal.value = true
}

function handleCreated(result: CreateTicketResult) {
  showCreateModal.value = false
  const ticketNo = result.ticket.ticketNo
  if (result.alreadyExists) {
    feedbackKind.value = 'info'
    feedbackMsg.value = `该工单已存在（工单号 ${ticketNo}），本次未重复创建。`
  } else {
    feedbackKind.value = 'success'
    feedbackMsg.value = `工单创建成功（工单号 ${ticketNo}）。`
  }
  void loadTickets()
}
</script>

<template>
  <div class="ticket-list-page">
    <!-- ── 页面标题 ── -->
    <PageHeaderHero
      eyebrow="Ticket Center"
      title="工单管理"
      description="查看账号开通等工单的处理进度与状态，支持按状态与关键字进行本地筛选。"
    >
      <template #actions>
        <button class="action-btn action-btn--primary" @click="openCreateModal">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 4v16m8-8H4" />
          </svg>
          创建工单
        </button>
        <button class="action-btn" :disabled="isLoading" @click="loadTickets">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
          </svg>
          刷新
        </button>
      </template>
    </PageHeaderHero>

    <!-- ── 反馈消息 ── -->
    <div v-if="feedbackMsg" class="feedback-area">
      <p class="feedback" :class="feedbackKind === 'info' ? 'feedback--info' : 'feedback--success'">
        {{ feedbackMsg }}
      </p>
    </div>

    <!-- ── 加载状态 ── -->
    <div v-if="isLoading" class="state-block">
      <div class="spinner"></div>
      <p>正在加载工单列表...</p>
    </div>

    <!-- ── 加载失败 ── -->
    <div v-else-if="errorMsg" class="state-block state-block--error">
      <p>{{ errorMsg }}</p>
      <button class="retry-btn" @click="loadTickets">重试</button>
    </div>

    <template v-else>
      <!-- ── 筛选 / 搜索工具栏 ── -->
      <div class="toolbar">
        <div class="search-box">
          <svg class="search-box__icon" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="7" />
            <path stroke-linecap="round" d="M21 21l-4.35-4.35" />
          </svg>
          <input
            v-model="searchQuery"
            type="text"
            class="search-box__input"
            placeholder="搜索工单编号或标题"
          />
        </div>

        <select v-model="statusFilter" class="status-select" aria-label="按状态筛选">
          <option v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
            {{ opt.label }}
          </option>
        </select>

        <span class="result-count">共 {{ filteredTickets.length }} 条</span>
      </div>

      <!-- ── 空状态（无任何工单） ── -->
      <div v-if="tickets.length === 0" class="state-block state-block--empty">
        <div class="empty-icon">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="5" y="4" width="14" height="17" rx="2" />
            <path d="M9 4.5V3.5C9 2.94772 9.44772 2.5 10 2.5H14C14.5523 2.5 15 2.94772 15 3.5V4.5" />
            <path d="M8.5 12L10.5 14L15.5 9" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <h3>暂无工单</h3>
        <p>目前没有可查看的工单。</p>
      </div>

      <!-- ── 筛选后无结果 ── -->
      <div v-else-if="filteredTickets.length === 0" class="state-block state-block--empty">
        <div class="empty-icon">
          <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="11" cy="11" r="7" />
            <path d="M21 21l-4.35-4.35" stroke-linecap="round" />
          </svg>
        </div>
        <h3>没有符合条件的工单</h3>
        <p>请调整搜索关键字或状态筛选条件。</p>
      </div>

      <!-- ── 工单表格 ── -->
      <div v-else class="table-wrapper">
        <div class="table-header">
          <div class="th th--no">工单编号</div>
          <div class="th th--title">标题</div>
          <div class="th th--status">状态</div>
          <div class="th th--priority">优先级</div>
          <div class="th th--time">创建时间</div>
          <div class="th th--actions">操作</div>
        </div>

        <div
          v-for="ticket in filteredTickets"
          :key="ticket.id"
          class="table-row"
        >
          <div class="td td--no">
            <code class="ticket-no">{{ ticket.ticketNo }}</code>
          </div>
          <div class="td td--title">
            <span class="ticket-title" :title="ticket.title">{{ ticket.title }}</span>
          </div>
          <div class="td td--status">
            <span class="status-pill" :class="`status-pill--${statusMetaOf(ticket.status).tone}`">
              <span class="status-pill__dot"></span>
              {{ statusMetaOf(ticket.status).label }}
            </span>
          </div>
          <div class="td td--priority">
            <span class="priority-text">{{ priorityText(ticket.priority) }}</span>
          </div>
          <div class="td td--time">
            <span class="time-text">{{ formatDateTime(ticket.createdAt) }}</span>
          </div>
          <div class="td td--actions">
            <button class="row-btn row-btn--detail" @click="viewDetail(ticket)">
              详情
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- ── 创建工单弹窗 ── -->
    <CreateTicketModal v-model:visible="showCreateModal" @created="handleCreated" />
  </div>
</template>

<style scoped>
/* ════════════ Page Layout ════════════ */
.ticket-list-page {
  width: 100%;
  min-height: 100%;
}

/* ════════════ Header Action Button ════════════ */
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

.action-btn:hover:not(:disabled) {
  color: var(--text-primary);
  border-color: var(--text-muted);
  background: var(--surface-subtle);
}

.action-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.action-btn--primary {
  background: var(--brand-primary);
  border-color: var(--brand-primary);
  color: #fff;
}

.action-btn--primary:hover:not(:disabled) {
  background: var(--brand-primary-dark);
  border-color: var(--brand-primary-dark);
  color: #fff;
}

/* ════════════ Feedback ════════════ */
.feedback-area {
  margin-bottom: 16px;
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

.state-block--empty h3 {
  margin: 0 0 6px;
  font-size: 1rem;
  font-weight: 700;
  color: var(--text-primary);
}

.state-block--empty p {
  margin-top: 0;
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

.empty-icon {
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: rgba(74, 144, 217, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--brand-primary);
  margin: 0 auto 16px;
}

/* ════════════ Toolbar ════════════ */
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  flex-wrap: wrap;
}

.search-box {
  position: relative;
  flex: 1;
  min-width: 220px;
  max-width: 360px;
}

.search-box__icon {
  position: absolute;
  left: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted);
  pointer-events: none;
}

.search-box__input {
  width: 100%;
  padding: 9px 12px 9px 36px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 0.88rem;
  font-family: inherit;
  color: var(--text-primary);
  background: var(--surface-white);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.search-box__input::placeholder {
  color: var(--text-muted);
}

.search-box__input:focus {
  outline: none;
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(74, 144, 217, 0.1);
}

.status-select {
  padding: 9px 12px;
  border: 1px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 0.88rem;
  font-family: inherit;
  color: var(--text-primary);
  background: var(--surface-white);
  cursor: pointer;
  transition: border-color 0.15s ease;
}

.status-select:focus {
  outline: none;
  border-color: var(--brand-primary);
}

.result-count {
  font-size: 0.82rem;
  color: var(--text-muted);
  margin-left: auto;
}

/* ════════════ Table ════════════ */
.table-wrapper {
  background: var(--surface-white);
  border: 1px solid var(--border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
}

.table-header,
.table-row {
  display: grid;
  grid-template-columns: minmax(150px, 1.2fr) minmax(220px, 2.4fr) minmax(96px, 0.9fr) minmax(80px, 0.7fr) minmax(140px, 1fr) minmax(80px, 0.7fr);
  gap: 16px;
  align-items: center;
  padding: 14px 20px;
}

.table-header {
  background: var(--surface-subtle);
  border-bottom: 1px solid var(--border-default);
}

.table-row {
  border-bottom: 1px solid var(--border-subtle);
  transition: background 0.15s ease;
}

.table-row:last-child {
  border-bottom: none;
}

.table-row:hover {
  background: rgba(74, 144, 217, 0.02);
}

.th {
  font-size: 0.74rem;
  font-weight: 700;
  color: var(--text-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

/* 工单编号 */
.ticket-no {
  font-family: 'JetBrains Mono', 'Consolas', monospace;
  font-size: 0.8rem;
  color: var(--text-secondary);
  background: var(--surface-subtle);
  padding: 3px 8px;
  border-radius: 4px;
  white-space: nowrap;
}

/* 标题 */
.ticket-title {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--text-primary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 状态 */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 600;
  white-space: nowrap;
}

.status-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex-shrink: 0;
}

.status-pill--blue { color: #2563eb; background: rgba(37, 99, 235, 0.08); }
.status-pill--amber { color: #d97706; background: rgba(245, 158, 11, 0.1); }
.status-pill--green { color: #059669; background: rgba(16, 185, 129, 0.1); }
.status-pill--indigo { color: #4f46e5; background: rgba(99, 102, 241, 0.1); }
.status-pill--slate { color: #475569; background: rgba(100, 116, 139, 0.1); }
.status-pill--red { color: #dc2626; background: rgba(239, 68, 68, 0.1); }
.status-pill--gray { color: #6b7280; background: rgba(107, 114, 128, 0.1); }

/* 优先级 */
.priority-text {
  font-size: 0.84rem;
  color: var(--text-secondary);
}

/* 创建时间 */
.time-text {
  font-size: 0.82rem;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}

/* 操作按钮 */
.td--actions {
  display: flex;
  justify-content: flex-end;
}

.row-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border-default);
  background: var(--surface-white);
  font-size: 0.78rem;
  font-weight: 600;
  font-family: inherit;
  cursor: pointer;
  transition: all 0.18s ease;
  white-space: nowrap;
}

.row-btn--detail {
  color: var(--text-secondary);
}

.row-btn--detail:hover {
  background: var(--surface-subtle);
  color: var(--text-primary);
  border-color: var(--text-muted);
}

/* ════════════ Responsive ════════════ */
@media (max-width: 900px) {
  .table-header,
  .table-row {
    grid-template-columns: 1fr 1fr;
    gap: 8px;
  }

  .th--priority,
  .td--priority,
  .th--time,
  .td--time {
    display: none;
  }
}

@media (max-width: 640px) {
  .table-header,
  .table-row {
    grid-template-columns: 1fr auto;
  }

  .th--title,
  .td--title {
    display: none;
  }

  .result-count {
    margin-left: 0;
    width: 100%;
  }
}
</style>
