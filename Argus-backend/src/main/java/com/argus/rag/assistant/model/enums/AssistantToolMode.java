package com.argus.rag.assistant.model.enums;

/**
 * 助手工具模式枚举。
 * <p>定义助手对话的工作模式，影响 Agent 的 system prompt 和可用工具。</p>
 */
public enum AssistantToolMode {
    /**
     * 纯对话模式，不使用知识库工具，仅基于模型自身知识进行对话
     */
    CHAT,
    /**
     * 知识库检索模式，允许 Agent 调用知识库检索工具获取相关证据后生成回答
     */
    KB_SEARCH,
    /**
     * 工单查询模式，允许 Agent 调用 queryTickets 工具查询当前登录用户可见的工单列表
     */
    TICKET_QUERY,
    /**
     * 工单助手模式，允许 Agent 以自然语言创建工单（createTicket），并查询当前登录用户
     * 可见的工单列表、详情与执行记录。Agent 只能创建 + 只读查询，不能提交审批、审批、
     * 执行、关闭或取消工单。
     */
    TICKET_ASSIST
}
