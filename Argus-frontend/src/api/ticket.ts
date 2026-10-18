import http from './http'
import type { ApiResponse } from './http'
import type {
  ApprovalDecisionPayload,
  CreateTicketPayload,
  CreateTicketResult,
  ExecuteTicketResult,
  TicketDetail,
  TicketExecutionRecord,
} from '../types/ticket'

// ─────────────────────────────────────────────
// API 函数
// ─────────────────────────────────────────────

/**
 * 创建工单
 *
 * POST /api/tickets
 *
 * 创建后工单初始状态为 OPEN，创建人由后端从登录态获取（请求体不含身份字段）。
 * 需提供全局唯一的 idempotencyKey；重复提交同一键且内容一致时，后端复用已有工单并返回 alreadyExists=true。
 *
 * @param payload 创建工单参数
 * @returns 创建结果（含工单详情与是否幂等复用标记）
 */
export async function createTicket(payload: CreateTicketPayload): Promise<CreateTicketResult> {
  const { data } = await http.post<ApiResponse<CreateTicketResult>>('/tickets', payload)
  return unwrapApiResponse(data, '创建工单失败')
}

/**
 * 查询当前用户可见的工单列表
 *
 * GET /api/tickets
 *
 * 普通用户返回自己创建的工单，系统管理员返回全部；按创建时间倒序。
 * ⚠️ 返回完整工单详情数组（非摘要结构），且当前后端未提供分页，前端需自行本地筛选/分页。
 *
 * @returns 工单详情数组
 */
export async function fetchTickets(): Promise<TicketDetail[]> {
  const { data } = await http.get<ApiResponse<TicketDetail[]>>('/tickets')
  return unwrapApiResponse(data, '加载工单列表失败')
}

/**
 * 查询工单详情
 *
 * GET /api/tickets/{ticketId}
 *
 * 仅创建人本人或系统管理员可见。
 *
 * @param ticketId 工单 ID
 * @returns 工单详情
 */
export async function fetchTicketDetail(ticketId: number): Promise<TicketDetail> {
  const { data } = await http.get<ApiResponse<TicketDetail>>(`/tickets/${ticketId}`)
  return unwrapApiResponse(data, '加载工单详情失败')
}

/**
 * 查询工单执行尝试历史
 *
 * GET /api/tickets/{ticketId}/executions
 *
 * 仅创建人本人或系统管理员可见；按 attemptNo 升序。
 *
 * @param ticketId 工单 ID
 * @returns 执行记录数组
 */
export async function fetchTicketExecutions(ticketId: number): Promise<TicketExecutionRecord[]> {
  const { data } = await http.get<ApiResponse<TicketExecutionRecord[]>>(`/tickets/${ticketId}/executions`)
  return unwrapApiResponse(data, '加载执行历史失败')
}

/**
 * 提交审批
 *
 * POST /api/tickets/{ticketId}/submit
 *
 * 仅工单创建人可操作；状态流转 OPEN -> PENDING_APPROVAL。
 *
 * @param ticketId 工单 ID
 */
export async function submitTicketForApproval(ticketId: number): Promise<void> {
  const { data } = await http.post<ApiResponse<null>>(`/tickets/${ticketId}/submit`)
  unwrapApiResponse(data, '提交审批失败')
}

/**
 * 审批通过
 *
 * POST /api/tickets/{ticketId}/approve
 *
 * 仅系统管理员可操作；状态流转 PENDING_APPROVAL -> APPROVED。
 * 审批意见可选，请求体整体可省略。
 *
 * @param ticketId 工单 ID
 * @param payload 审批意见（可选）
 */
export async function approveTicket(ticketId: number, payload?: ApprovalDecisionPayload): Promise<void> {
  const { data } = await http.post<ApiResponse<null>>(`/tickets/${ticketId}/approve`, payload ?? {})
  unwrapApiResponse(data, '审批通过失败')
}

/**
 * 审批拒绝
 *
 * POST /api/tickets/{ticketId}/reject
 *
 * 仅系统管理员可操作；状态流转 PENDING_APPROVAL -> REJECTED。
 * 审批意见可选，请求体整体可省略。
 *
 * @param ticketId 工单 ID
 * @param payload 审批意见（可选）
 */
export async function rejectTicket(ticketId: number, payload?: ApprovalDecisionPayload): Promise<void> {
  const { data } = await http.post<ApiResponse<null>>(`/tickets/${ticketId}/reject`, payload ?? {})
  unwrapApiResponse(data, '审批拒绝失败')
}

/**
 * 执行工单（账号开通）
 *
 * POST /api/tickets/{ticketId}/execute
 *
 * 仅系统管理员可操作；状态流转 APPROVED -> IN_PROGRESS -> RESOLVED（成功）/ APPROVED（失败或结果不确定）。
 * 结果 UNCERTAIN 表示外部操作可能已成功也可能未成功，需人工核实。
 *
 * @param ticketId 工单 ID
 * @returns 执行结果（不含执行后的工单状态，需回拉详情确认）
 */
export async function executeTicket(ticketId: number): Promise<ExecuteTicketResult> {
  const { data } = await http.post<ApiResponse<ExecuteTicketResult>>(`/tickets/${ticketId}/execute`)
  return unwrapApiResponse(data, '执行工单失败')
}

// ─────────────────────────────────────────────
// 工具函数
// ─────────────────────────────────────────────

/**
 * 解包 ApiResponse，若 success 为 false 则抛出错误
 * @param payload API 响应体
 * @param fallbackMessage 响应消息为空时的兜底错误信息
 */
function unwrapApiResponse<T>(payload: ApiResponse<T>, fallbackMessage: string): T {
  if (!payload.success) {
    throw new Error(payload.message ?? fallbackMessage)
  }

  return payload.data
}
