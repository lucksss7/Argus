package com.argus.rag.ticket.controller;

import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.ticket.model.dto.CreateTicketRequest;
import com.argus.rag.ticket.model.dto.DispositionRequest;
import com.argus.rag.ticket.model.enums.DispositionType;
import com.argus.rag.ticket.model.enums.ExecutionResult;
import com.argus.rag.ticket.model.enums.ExecutionType;
import com.argus.rag.ticket.model.enums.PermissionLevel;
import com.argus.rag.ticket.model.enums.TicketStatus;
import com.argus.rag.ticket.model.enums.TicketType;
import com.argus.rag.ticket.model.vo.CreateTicketResult;
import com.argus.rag.ticket.model.vo.ExecuteTicketResult;
import com.argus.rag.ticket.model.vo.ExecutionRecordVO;
import com.argus.rag.ticket.model.vo.TicketDetailResponse;
import com.argus.rag.ticket.service.TicketApprovalService;
import com.argus.rag.ticket.service.TicketDispositionService;
import com.argus.rag.ticket.service.TicketExecutionService;
import com.argus.rag.ticket.service.TicketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TicketController} 接口层测试。
 * <p>
 * 使用 {@link WebMvcTest} 仅加载 Controller 层，模拟三个 Service 依赖，验证路由、
 * 参数校验、响应包装与 Service 调用参数。本类不验证 HTTP 层的真实认证/授权——
 * 那属于 {@code JwtAuthenticationFilter} + Service 内身份校验的职责，见集成测试与服务层测试。
 */
@WebMvcTest(controllers = TicketController.class)
@Import({TicketController.class, GlobalExceptionHandler.class})
@DisplayName("工单控制器")
class TicketControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private TicketApprovalService ticketApprovalService;

    @MockitoBean
    private TicketExecutionService ticketExecutionService;

    @MockitoBean
    private TicketDispositionService ticketDispositionService;

    /** JwtAuthenticationFilter 作为 Filter 会被 WebMvcTest 切片纳入，需 mock 其依赖 */
    @MockitoBean
    private JwtAccessTokenService jwtAccessTokenService;

    private static final String BASE = "/api/tickets";

    private static final String VALID_CREATE_JSON =
            "{\"idempotencyKey\":\"key-1\",\"type\":\"ACCOUNT_PROVISIONING\",\"systemName\":\"erp\","
            + "\"targetUsername\":\"zhangsan\",\"permissionLevel\":\"READ_ONLY\","
            + "\"description\":\"需要只读账号\",\"title\":\"账号开通申请\"}";

    private static final String INVALID_CREATE_JSON =
            "{\"type\":\"ACCOUNT_PROVISIONING\",\"systemName\":\"erp\",\"targetUsername\":\"zhangsan\","
            + "\"permissionLevel\":\"READ_ONLY\",\"description\":\"需要只读账号\",\"title\":\"账号开通申请\"}";

    // ─────────────────────────── 创建 ───────────────────────────

    @Test
    @DisplayName("创建工单：应包装成功响应并正确传递请求参数")
    void shouldCreateTicket() throws Exception {
        when(ticketService.createTicket(any(CreateTicketRequest.class)))
                .thenReturn(new CreateTicketResult(detail(), false));

        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CREATE_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.alreadyExists").value(false));

        ArgumentCaptor<CreateTicketRequest> captor = ArgumentCaptor.forClass(CreateTicketRequest.class);
        verify(ticketService).createTicket(captor.capture());
        CreateTicketRequest req = captor.getValue();
        assertThat(req.idempotencyKey()).isEqualTo("key-1");
        assertThat(req.type()).isEqualTo(TicketType.ACCOUNT_PROVISIONING);
        assertThat(req.systemName()).isEqualTo("erp");
        assertThat(req.targetUsername()).isEqualTo("zhangsan");
        assertThat(req.permissionLevel()).isEqualTo(PermissionLevel.READ_ONLY);
    }

    @Test
    @DisplayName("创建工单参数校验失败：缺失幂等键应返回 400")
    void shouldRejectCreateOnValidationFailure() throws Exception {
        mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INVALID_CREATE_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ─────────────────────────── 查询 ───────────────────────────

    @Test
    @DisplayName("查询工单列表：应包装成功响应")
    void shouldListTickets() throws Exception {
        when(ticketService.listTickets()).thenReturn(List.of(detail()));

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value(42));

        verify(ticketService).listTickets();
    }

    @Test
    @DisplayName("查询工单详情：应以路径参数调用 Service")
    void shouldGetTicketDetail() throws Exception {
        when(ticketService.getTicketDetail(42L)).thenReturn(detail());

        mockMvc.perform(get(BASE + "/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));

        verify(ticketService).getTicketDetail(42L);
    }

    @Test
    @DisplayName("查询执行历史：应返回 attemptNo 且不暴露 requestPayload/responsePayload")
    void shouldListExecutionsWithoutPayload() throws Exception {
        when(ticketService.getTicketExecutions(42L)).thenReturn(List.of(
                new ExecutionRecordVO(100L, 1, ExecutionType.PROVISION_ACCOUNT, ExecutionResult.SUCCESS, null,
                        LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 1), null, null)));

        String body = mockMvc.perform(get(BASE + "/42/executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].attemptNo").value(1))
                .andExpect(jsonPath("$.data[0].result").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"attemptNo\"")
                .doesNotContain("requestPayload")
                .doesNotContain("responsePayload");

        verify(ticketService).getTicketExecutions(42L);
    }

    // ─────────────────────────── 审批流转 ───────────────────────────

    @Test
    @DisplayName("提交审批：应以路径参数调用 Service")
    void shouldSubmitForApproval() throws Exception {
        mockMvc.perform(post(BASE + "/42/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketApprovalService).submitForApproval(42L);
    }

    @Test
    @DisplayName("审批通过：应透传审批意见")
    void shouldApproveWithComment() throws Exception {
        mockMvc.perform(post(BASE + "/42/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"同意开通\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketApprovalService).approve(eq(42L), eq("同意开通"));
    }

    @Test
    @DisplayName("审批通过：请求体缺省时按 null 意见处理")
    void shouldApproveWithoutBody() throws Exception {
        mockMvc.perform(post(BASE + "/42/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketApprovalService).approve(eq(42L), isNull());
    }

    @Test
    @DisplayName("审批通过：空对象 body 时意见为 null")
    void shouldApproveWithEmptyBody() throws Exception {
        mockMvc.perform(post(BASE + "/42/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketApprovalService).approve(eq(42L), isNull());
    }

    @Test
    @DisplayName("审批意见超长应返回 400")
    void shouldRejectApproveWhenCommentTooLong() throws Exception {
        String tooLong = "a".repeat(501);
        mockMvc.perform(post(BASE + "/42/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("审批拒绝：应透传审批意见")
    void shouldRejectWithComment() throws Exception {
        mockMvc.perform(post(BASE + "/42/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"资料不全\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketApprovalService).reject(eq(42L), eq("资料不全"));
    }

    // ─────────────────────────── 执行 ───────────────────────────

    @Test
    @DisplayName("执行账号开通：应以路径参数调用 Service 并包装执行结果")
    void shouldExecuteTicket() throws Exception {
        when(ticketExecutionService.execute(42L))
                .thenReturn(new ExecuteTicketResult(42L, 1, ExecutionResult.SUCCESS, "账号开通成功"));

        mockMvc.perform(post(BASE + "/42/execute"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ticketId").value(42))
                .andExpect(jsonPath("$.data.attemptNo").value(1))
                .andExpect(jsonPath("$.data.result").value("SUCCESS"));

        verify(ticketExecutionService).execute(42L);
    }

    // ─────────────────────────── 人工处置 ───────────────────────────

    @Test
    @DisplayName("人工处置：应以路径参数与请求体调用 Service 并包装成功响应")
    void shouldDisposeTicket() throws Exception {
        mockMvc.perform(post(BASE + "/42/dispose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CONFIRM_SUCCESS\",\"executionId\":100,\"reason\":\"确认已开通\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<DispositionRequest> captor = ArgumentCaptor.forClass(DispositionRequest.class);
        verify(ticketDispositionService).dispose(eq(42L), captor.capture());
        DispositionRequest req = captor.getValue();
        assertThat(req.type()).isEqualTo(DispositionType.CONFIRM_SUCCESS);
        assertThat(req.executionId()).isEqualTo(100L);
        assertThat(req.reason()).isEqualTo("确认已开通");
    }

    @Test
    @DisplayName("人工处置参数校验失败：缺失处置类型应返回 400")
    void shouldRejectDisposeOnValidationFailure() throws Exception {
        mockMvc.perform(post(BASE + "/42/dispose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"确认已开通\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ─────────────────────────── 关闭与取消 ───────────────────────────

    @Test
    @DisplayName("关闭工单：应以路径参数调用 Service 并包装成功响应")
    void shouldCloseTicket() throws Exception {
        mockMvc.perform(post(BASE + "/42/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketService).close(42L);
    }

    @Test
    @DisplayName("取消工单：应以路径参数调用 Service 并包装成功响应")
    void shouldCancelTicket() throws Exception {
        mockMvc.perform(post(BASE + "/42/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(ticketService).cancel(42L);
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private TicketDetailResponse detail() {
        return new TicketDetailResponse(42L, "TK-1", "账号开通申请", "需要只读账号",
                TicketType.ACCOUNT_PROVISIONING, TicketStatus.OPEN, null, 5L, null, null,
                "erp", "zhangsan", PermissionLevel.READ_ONLY, null, 0,
                LocalDateTime.of(2026, 1, 1, 0, 0), LocalDateTime.of(2026, 1, 1, 0, 0));
    }
}
