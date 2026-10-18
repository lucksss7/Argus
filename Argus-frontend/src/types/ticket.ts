// ─────────────────────────────────────────────
// 工单模块 — TypeScript 类型定义
// ─────────────────────────────────────────────
//
// 领域概述
// ─────────
// 工单（Ticket）用于发起「账号开通」等受控执行请求，走完整审批流转：
//
//   OPEN（创建） → PENDING_APPROVAL（提交审批） → APPROVED（审批通过） → IN_PROGRESS（执行中）
//     → RESOLVED（执行成功）/ APPROVED（执行失败或结果不确定，可再次执行）
//
//   另有 REJECTED（审批拒绝）、CLOSED（关闭）、CANCELLED（取消）等终态。
//
// 关键约定：
//   - 创建人 / 审批人 / 执行人一律由后端从服务端登录态获取，请求体不含任何身份字段。
//   - 建单幂等由调用方提供的 idempotencyKey 保证，重复提交同一键且内容一致会复用已有工单。
//   - 列表接口返回完整工单详情（TicketDetail）数组，而非 Agent 工具使用的摘要结构。
//
// 本文档中的枚举均以字符串字面量联合类型表示，与后端 Java 枚举的 name() 序列化结果一一对应。

// ─────────────────────────────────────────────
// 枚举 / 字面量联合类型
// ─────────────────────────────────────────────

/**
 * 工单状态
 *
 * 后端 TicketStatus 枚举，按 name() 序列化：
 * - `OPEN`：待处理（含待分配，用 assigneeUserId 是否为空区分）
 * - `PENDING_APPROVAL`：待审批
 * - `APPROVED`：已审批通过，业务尚未执行
 * - `IN_PROGRESS`：执行中
 * - `RESOLVED`：已解决
 * - `CLOSED`：已关闭
 * - `REJECTED`：审批被拒
 * - `CANCELLED`：已取消
 * - `ABORTED`：已终止（经管理员人工处置终结，不再执行，与普通取消 CANCELLED 不同）
 */
export type TicketStatus =
  | 'OPEN'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'IN_PROGRESS'
  | 'RESOLVED'
  | 'CLOSED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'ABORTED'

/**
 * 工单类型
 *
 * 后端 TicketType 枚举。当前仅一种：
 * - `ACCOUNT_PROVISIONING`：账号开通
 */
export type TicketType = 'ACCOUNT_PROVISIONING'

/**
 * 账号权限级别
 *
 * 后端 PermissionLevel 枚举（白名单，后端按规则校验）：
 * - `READ_ONLY`：只读
 * - `STANDARD`：标准
 * - `ADMIN`：管理员
 */
export type PermissionLevel = 'READ_ONLY' | 'STANDARD' | 'ADMIN'

/**
 * 工单执行类型
 *
 * 后端 ExecutionType 枚举。当前仅一种：
 * - `PROVISION_ACCOUNT`：开通账号
 */
export type ExecutionType = 'PROVISION_ACCOUNT'

/**
 * 工单执行结果
 *
 * 后端 ExecutionResult 枚举：
 * - `PENDING`：待执行（仅出现在执行尝试历史中，execute 接口不会返回此值）
 * - `SUCCESS`：成功
 * - `FAILED`：失败
 * - `UNCERTAIN`：结果不确定，外部操作可能已成功也可能未成功，需人工核实，不能当作普通失败自动重试
 */
export type ExecutionResult = 'PENDING' | 'SUCCESS' | 'FAILED' | 'UNCERTAIN'

/**
 * 执行记录的人工核验结论
 *
 * 后端 ExecutionVerification 枚举：
 * - `CONFIRMED_SUCCESS`：管理员已确认该次操作执行成功
 * - `CONFIRMED_NOT_EXECUTED`：管理员已确认该次操作未执行
 *
 * 仅当 result = UNCERTAIN 且经管理员人工处置后写入；尚未核验时为 null。
 */
export type ExecutionVerification = 'CONFIRMED_SUCCESS' | 'CONFIRMED_NOT_EXECUTED'

// ─────────────────────────────────────────────
// 请求类型
// ─────────────────────────────────────────────

/**
 * 创建工单请求体
 * 对应后端 CreateTicketRequest
 */
export interface CreateTicketPayload {
  /**
   * 建单幂等键（全局唯一，由调用方提供）
   *
   * 后端以该键去重：同一幂等键再次提交且内容一致时，返回已有工单（alreadyExists=true）。
   * 长度不超过 128 字符。
   */
  idempotencyKey: string
  /** 工单类型 */
  type: TicketType
  /** 目标系统（长度不超过 100） */
  systemName: string
  /** 要开通账号的目标用户名（长度不超过 100） */
  targetUsername: string
  /** 权限级别 */
  permissionLevel: PermissionLevel
  /** 申请原因（必填，后端未限制长度） */
  description: string
  /** 标题（长度不超过 200） */
  title: string
  /**
   * 优先级（可选）
   *
   * 自由文本，非枚举，长度不超过 16；不传或传空字符串时后端归一化为 null。
   */
  priority?: string | null
}

/**
 * 审批决定请求体（审批通过 / 审批拒绝共用）
 * 对应后端 ApprovalDecisionRequest
 *
 * 整个请求体可省略；如提供 comment，长度不超过 500 字符。
 */
export interface ApprovalDecisionPayload {
  /** 审批意见（可选） */
  comment?: string
}

// ─────────────────────────────────────────────
// 响应类型
// ─────────────────────────────────────────────

/**
 * 工单详情
 * 对应后端 TicketDetailResponse
 *
 * 列表接口（GET /api/tickets）返回该对象的数组，详情接口返回单个该对象。
 */
export interface TicketDetail {
  /** 工单 ID */
  id: number
  /** 工单号（后端生成，格式 TK + yyyyMMddHHmmss + 4 位随机数） */
  ticketNo: string
  /** 标题 */
  title: string
  /** 申请原因 / 描述 */
  description: string
  /** 工单类型 */
  type: TicketType
  /** 工单状态 */
  status: TicketStatus
  /**
   * 优先级（自由文本）
   *
   * 创建时未提供则为 null。
   */
  priority: string | null
  /** 创建人用户 ID */
  creatorUserId: number
  /**
   * 处理人用户 ID
   *
   * v1 尚未实现分配功能，当前恒为 null。
   */
  assigneeUserId: number | null
  /**
   * 审批人用户 ID
   *
   * 审批通过 / 拒绝后才有值，此前为 null。
   */
  approverUserId: number | null
  /** 目标系统 */
  systemName: string
  /** 要开通账号的目标用户名 */
  targetUsername: string
  /** 权限级别 */
  permissionLevel: PermissionLevel
  /**
   * 解决说明
   *
   * v1 当前未使用，恒为 null。
   */
  resolution: string | null
  /** 乐观锁版本号 */
  version: number
  /** 创建时间（ISO 8601 字符串，LocalDateTime 序列化，无时区） */
  createdAt: string
  /** 更新时间（ISO 8601 字符串，LocalDateTime 序列化，无时区） */
  updatedAt: string
}

/**
 * 创建工单结果
 * 对应后端 CreateTicketResult
 */
export interface CreateTicketResult {
  /** 工单详情 */
  ticket: TicketDetail
  /** 是否命中幂等复用：true 表示返回的是已有工单，而非本次新建 */
  alreadyExists: boolean
}

/**
 * 执行工单结果
 * 对应后端 ExecuteTicketResult
 *
 * ⚠️ 本结果不包含执行后的工单状态；执行成功后工单为 RESOLVED，
 * 失败或结果不确定（UNCERTAIN）时工单回到 APPROVED。前端应回拉详情确认最终状态。
 */
export interface ExecuteTicketResult {
  /** 工单 ID */
  ticketId: number
  /** 本次执行是第几次尝试（从 1 开始） */
  attemptNo: number
  /** 执行结果 */
  result: ExecutionResult
  /**
   * 结果说明
   *
   * SUCCESS 时为成功描述（如「账号开通成功」）；FAILED / UNCERTAIN 时为失败或需人工核实的说明。
   * 后端未填充时可能为 null。
   */
  message: string | null
}

/**
 * 一次执行尝试的历史记录
 * 对应后端 ExecutionRecordVO
 *
 * 仅暴露可见字段，不含 requestPayload / responsePayload 等敏感载荷。
 */
export interface TicketExecutionRecord {
  /** 执行记录 ID */
  id: number
  /** 第几次尝试 */
  attemptNo: number
  /** 执行类型 */
  executionType: ExecutionType
  /** 本次执行结果（PENDING 表示已开始但尚未写回最终结果） */
  result: ExecutionResult
  /**
   * 错误信息
   *
   * 仅 FAILED / UNCERTAIN 时有值，SUCCESS 时为 null。
   */
  errorMessage: string | null
  /** 开始时间（ISO 8601 字符串，无时区） */
  startedAt: string
  /** 完成时间（ISO 8601 字符串，无时区）；记录尚处 PENDING 时为 null */
  finishedAt: string | null
  /**
   * 人工核验结论
   *
   * 仅当 result 为 UNCERTAIN 且经管理员人工处置后才有值，否则为 null。
   */
  verificationStatus: ExecutionVerification | null
  /** 核验时间（ISO 8601 字符串，无时区）；尚未核验时为 null */
  verifiedAt: string | null
}
