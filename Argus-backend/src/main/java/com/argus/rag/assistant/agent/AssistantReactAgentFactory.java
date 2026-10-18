package com.argus.rag.assistant.agent;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.argus.rag.assistant.memory.AssistantShortTermMemoryHook;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.common.enums.SystemRole;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 助手 React Agent 工厂。
 * <p>负责创建配置好的 {@link ReactAgent} 实例，根据不同的工具模式（CHAT/KB_SEARCH）
 * 动态添加知识库检索工具和方法工具上下文。</p>
 */
@Component
public class AssistantReactAgentFactory {

    // KB_SEARCH 至少需要经历“模型决定调工具 -> 工具执行 -> 模型基于工具结果生成最终回答”，
    // 递归上限过小会导致图在最终回答前被截断，返回空 AssistantMessage。
    private static final int AGENT_RECURSION_LIMIT = 10;

    private final ChatModel chatModel;
    private final AssistantShortTermMemoryHook assistantShortTermMemoryHook;
    private final AssistantKnowledgeBaseTool assistantKnowledgeBaseTool;
    private final AssistantTicketCreateTool assistantTicketCreateTool;
    private final AssistantTicketQueryTool assistantTicketQueryTool;
    private final AssistantTicketDetailTool assistantTicketDetailTool;
    private final AssistantTicketExecutionsTool assistantTicketExecutionsTool;

    public AssistantReactAgentFactory(
            ChatModel chatModel,
            AssistantShortTermMemoryHook assistantShortTermMemoryHook,
            AssistantKnowledgeBaseTool assistantKnowledgeBaseTool,
            AssistantTicketCreateTool assistantTicketCreateTool,
            AssistantTicketQueryTool assistantTicketQueryTool,
            AssistantTicketDetailTool assistantTicketDetailTool,
            AssistantTicketExecutionsTool assistantTicketExecutionsTool
    ) {
        this.chatModel = chatModel;
        this.assistantShortTermMemoryHook = assistantShortTermMemoryHook;
        this.assistantKnowledgeBaseTool = assistantKnowledgeBaseTool;
        this.assistantTicketCreateTool = assistantTicketCreateTool;
        this.assistantTicketQueryTool = assistantTicketQueryTool;
        this.assistantTicketDetailTool = assistantTicketDetailTool;
        this.assistantTicketExecutionsTool = assistantTicketExecutionsTool;
    }

    /**
     * 创建配置好的 ReactAgent 实例。
     * <p>根据指定的 instruction、工具模式和知识库组 ID 构建 Agent，
     * CHAT 模式下仅配置对话能力，KB_SEARCH 模式下额外注入知识库检索工具及其上下文。</p>
     * <p>Agent 配置了短期记忆 Hook、检查点保存器和递归上限，
     * 确保单次调用能完成"模型推理 → 工具执行 → 最终回复"的完整链路。</p>
     *
     * @param instruction  系统提示词，设定 Agent 的角色和行为规范
     * @param toolMode     工具模式（CHAT 仅对话 / KB_SEARCH 知识库检索 / TICKET_QUERY 工单查询）
     * @param groupId      知识库组 ID，仅 KB_SEARCH 模式生效，CHAT 模式可传 {@code null}
     * @param resultHolder 知识库检索结果持有器，用于跨步骤传递检索引用
     * @param userId       当前用户 ID，仅 TICKET_QUERY / TICKET_ASSIST 模式生效，作为工单工具的服务端可信身份
     * @param systemRole   当前用户角色，仅 TICKET_QUERY / TICKET_ASSIST 模式生效，作为工单工具的权限依据
     * @param messageId    当前用户消息 ID，仅 TICKET_ASSIST 模式生效，作为建单的消息级幂等边界
     * @return 构建完成的 ReactAgent 实例
     */
    public ReactAgent createAgent(
            String instruction,
            AssistantToolMode toolMode,
            Long groupId,
            AssistantKnowledgeBaseToolResultHolder resultHolder,
            Long userId,
            SystemRole systemRole,
            Long messageId
    ) {
        // 当前“仅对话模式”仍复用 ReactAgent 运行时：
        // system prompt 与短期上下文保留，长期记忆工具已整体移除。
        com.alibaba.cloud.ai.graph.agent.Builder builder = ReactAgent.builder()
                .name("assistant_chat_agent")
                .model(chatModel)
                .instruction(instruction)
                .hooks(assistantShortTermMemoryHook)
                .compileConfig(CompileConfig.builder()
                        .recursionLimit(AGENT_RECURSION_LIMIT)
                        .build())
                // MemorySaver 只负责图运行态的 checkpoint，不是正式业务记忆的事实源。
                .saver(new MemorySaver());
        if (toolMode == AssistantToolMode.KB_SEARCH) {
            builder.methodTools(assistantKnowledgeBaseTool)
                    .toolContext(Map.of(
                            AssistantKnowledgeBaseTool.GROUP_ID_CONTEXT_KEY, groupId,
                            AssistantKnowledgeBaseTool.RESULT_HOLDER_CONTEXT_KEY, resultHolder
                    ));
        }
        if (toolMode == AssistantToolMode.TICKET_QUERY) {
            // 身份由服务端显式注入工具上下文，三个工单只读工具共用同一份身份，
            // 模型只能提供业务参数（如 ticketId），无法覆盖 userId/systemRole。
            builder.methodTools(assistantTicketQueryTool, assistantTicketDetailTool, assistantTicketExecutionsTool)
                    .toolContext(Map.of(
                            AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId,
                            AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, systemRole
                    ));
        }
        if (toolMode == AssistantToolMode.TICKET_ASSIST) {
            // 工单助手模式只注册「创建 + 只读查询」四类工具；提交审批、审批、执行、
            // 关闭、取消等写操作没有对应工具类，Agent 从能力上就无法执行。
            // 身份与消息 ID 同样由服务端注入，建单工具复用它确定创建人，并用消息 ID 作为幂等边界。
            // 使用 HashMap 而非 Map.of，以容忍 messageId 为 null 时由工具侧统一拒绝，而非在构建阶段抛 NPE。
            Map<String, Object> assistContext = new HashMap<>();
            assistContext.put(AbstractAssistantTicketTool.USER_ID_CONTEXT_KEY, userId);
            assistContext.put(AbstractAssistantTicketTool.SYSTEM_ROLE_CONTEXT_KEY, systemRole);
            assistContext.put(AbstractAssistantTicketTool.MESSAGE_ID_CONTEXT_KEY, messageId);
            builder.methodTools(
                            assistantTicketCreateTool,
                            assistantTicketQueryTool,
                            assistantTicketDetailTool,
                            assistantTicketExecutionsTool)
                    .toolContext(assistContext);
        }
        return builder.build();
    }
}
