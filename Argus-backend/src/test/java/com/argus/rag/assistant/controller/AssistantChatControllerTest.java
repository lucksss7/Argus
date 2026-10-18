package com.argus.rag.assistant.controller;

import com.argus.rag.assistant.model.dto.chat.AssistantChatRequest;
import com.argus.rag.assistant.model.enums.AssistantToolMode;
import com.argus.rag.assistant.model.vo.chat.AssistantChatResponse;
import com.argus.rag.assistant.model.vo.chat.AssistantChatStreamEvent;
import com.argus.rag.assistant.service.AssistantService;
import com.argus.rag.assistant.service.AssistantStreamEventEmitter;
import com.argus.rag.auth.security.JwtAccessTokenService;
import com.argus.rag.common.exception.BusinessException;
import com.argus.rag.common.exception.GlobalExceptionHandler;
import com.argus.rag.qa.model.vo.AskQuestionResponse;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AssistantChatController} 接口层测试。
 * <p>
 * 使用 {@link WebMvcTest} 仅加载 Controller 层，模拟 {@link AssistantService} 依赖，
 * 覆盖同步聊天 {@code /api/assistant/chat} 与流式聊天 {@code /api/assistant/chat/stream}（SSE）的
 * HTTP 可观测行为：路由、参数校验、响应包装、SSE 事件契约与异常映射。
 * </p>
 *
 * <h3>认证链路覆盖边界（重要）</h3>
 * <p>
 * 本切片会<em>实例化</em> {@code JwtAuthenticationFilter} 这个 {@code @Component} 过滤器 bean
 * （因此必须 {@code @MockitoBean JwtAccessTokenService} 才能启动上下文），
 * 但 {@code @WebMvcTest} 的 MockMvc <b>不会把普通 {@code Filter} bean 加入其请求过滤链</b>——
 * 应用运行时该过滤器由 Servlet 容器的 {@code ServletContextInitializerBeans} 自动注册，
 * 而切片测试没有真实 Servlet 容器。因此本类中的 MockMvc 请求<b>不经过该过滤器</b>，
 * 任何认证行为（缺 token、非法 token、有效 token → 401/403）都<b>不在本类覆盖范围</b>，
 * 需由过滤器集成测试或服务层（{@link AssistantService#resolveCurrentUser} 等）测试验证。
 * </p>
 */
@WebMvcTest(controllers = AssistantChatController.class)
@Import({AssistantChatController.class, GlobalExceptionHandler.class})
@DisplayName("助手聊天控制器")
class AssistantChatControllerTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AssistantService assistantService;

    /** JwtAuthenticationFilter 作为 Filter 会被 WebMvcTest 切片纳入，需 mock 其依赖 */
    @MockitoBean
    private JwtAccessTokenService jwtAccessTokenService;

    private static final String CHAT_URL = "/api/assistant/chat";
    private static final String STREAM_URL = "/api/assistant/chat/stream";

    private static final String VALID_CHAT_JSON =
            "{\"sessionId\":1,\"message\":\"你好\",\"toolMode\":\"CHAT\"}";

    // ─────────────────────────── 同步聊天 /chat ───────────────────────────

    @Test
    @DisplayName("合法请求：应包装成功响应并正确传递请求参数")
    void shouldReturnChatResponseOnValidRequest() throws Exception {
        when(assistantService.chat(any(HttpServletRequest.class), any(AssistantChatRequest.class)))
                .thenReturn(new AssistantChatResponse(1L, 10L, "你好", AssistantToolMode.CHAT, null, List.of()));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHAT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(1))
                .andExpect(jsonPath("$.data.messageId").value(10))
                .andExpect(jsonPath("$.data.reply").value("你好"))
                .andExpect(jsonPath("$.data.toolMode").value("CHAT"));

        ArgumentCaptor<AssistantChatRequest> captor = ArgumentCaptor.forClass(AssistantChatRequest.class);
        verify(assistantService).chat(any(HttpServletRequest.class), captor.capture());
        AssistantChatRequest req = captor.getValue();
        assertThat(req.sessionId()).isEqualTo(1L);
        assertThat(req.message()).isEqualTo("你好");
        assertThat(req.toolMode()).isEqualTo(AssistantToolMode.CHAT);
        assertThat(req.groupId()).isNull();
    }

    @Test
    @DisplayName("缺失 sessionId 应返回 400 及字段错误信息")
    void shouldReturn400WhenSessionIdMissing() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"toolMode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("sessionId 不能为空"));
    }

    @Test
    @DisplayName("sessionId 非正整数应返回 400")
    void shouldReturn400WhenSessionIdNotPositive() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":0,\"message\":\"你好\",\"toolMode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("sessionId 非法"));
    }

    @Test
    @DisplayName("message 为空应返回 400")
    void shouldReturn400WhenMessageBlank() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"message\":\"\",\"toolMode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("message 不能为空"));
    }

    @Test
    @DisplayName("缺失 toolMode 应返回 400")
    void shouldReturn400WhenToolModeMissing() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("toolMode 不能为空"));
    }

    @Test
    @DisplayName("groupId 非正整数应返回 400")
    void shouldReturn400WhenGroupIdNotPositive() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"message\":\"你好\",\"toolMode\":\"CHAT\",\"groupId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("groupId 非法"));
    }

    @Test
    @DisplayName("message 超过 4000 字符应返回 400")
    void shouldReturn400WhenMessageTooLong() throws Exception {
        String tooLong = "a".repeat(4001);
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":1,\"message\":\"" + tooLong + "\",\"toolMode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("message 长度不能超过 4000"));
    }

    @Test
    @DisplayName("业务异常应映射为 400 并透传错误信息")
    void shouldReturn400OnBusinessException() throws Exception {
        when(assistantService.chat(any(HttpServletRequest.class), any(AssistantChatRequest.class)))
                .thenThrow(new BusinessException("当前模式不允许传 groupId"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHAT_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("当前模式不允许传 groupId"));
    }

    @Test
    @DisplayName("未预期异常应映射为 500 且不暴露堆栈细节")
    void shouldReturn500OnUnexpectedException() throws Exception {
        when(assistantService.chat(any(HttpServletRequest.class), any(AssistantChatRequest.class)))
                .thenThrow(new RuntimeException("boom jdbc secret"));

        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHAT_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("服务器内部错误"));
    }

    @Test
    @DisplayName("请求体非法 JSON 应返回 400")
    void shouldReturn400OnMalformedBody() throws Exception {
        mockMvc.perform(post(CHAT_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("请求体格式非法"));
    }

    // ─────────────────────────── 流式聊天 /chat/stream ───────────────────────────

    @Test
    @DisplayName("合法流式请求：应按序发送 start/delta/done 事件")
    void shouldStreamStartDeltaDoneEvents() throws Exception {
        doAnswer(invocation -> {
            AssistantStreamEventEmitter emitter = invocation.getArgument(2);
            emitter.emit(AssistantChatStreamEvent.start(1L, AssistantToolMode.CHAT, null));
            emitter.emit(AssistantChatStreamEvent.delta(1L, AssistantToolMode.CHAT, null, "你好，"));
            emitter.emit(AssistantChatStreamEvent.delta(1L, AssistantToolMode.CHAT, null, "世界"));
            emitter.emit(AssistantChatStreamEvent.done(1L, AssistantToolMode.CHAT, null, 10L, "你好，世界", List.of()));
            return null;
        }).when(assistantService).streamChat(
                any(HttpServletRequest.class),
                any(AssistantChatRequest.class),
                any(AssistantStreamEventEmitter.class));

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHAT_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        String responseBody = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(responseBody)
                .contains("event:start")
                .contains("\"event\":\"start\"")
                .contains("event:delta")
                .contains("\"delta\":\"你好，\"")
                .contains("\"delta\":\"世界\"")
                .contains("event:done")
                .contains("\"messageId\":10")
                .contains("\"reply\":\"你好，世界\"")
                .doesNotContain("event:error");
    }

    @Test
    @DisplayName("流式执行异常：应发送 error 事件而非 delta")
    void shouldSendErrorEventOnStreamFailure() throws Exception {
        doThrow(new RuntimeException("模型不可用"))
                .when(assistantService).streamChat(
                        any(HttpServletRequest.class),
                        any(AssistantChatRequest.class),
                        any(AssistantStreamEventEmitter.class));

        MvcResult mvcResult = mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CHAT_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();

        String responseBody = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(responseBody)
                .contains("event:error")
                .contains("\"error\":\"模型不可用\"")
                .doesNotContain("event:delta");
    }

    @Test
    @DisplayName("流式请求参数校验失败应同步返回 400")
    void shouldReturn400OnStreamValidationFailure() throws Exception {
        mockMvc.perform(post(STREAM_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"toolMode\":\"CHAT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("sessionId 不能为空"));
    }
}
