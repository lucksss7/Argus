package com.argus.rag.ticket.model.dto;

import com.argus.rag.ticket.model.enums.DispositionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 人工处置请求：统一按 {@code type} 分派到具体处置动作。
 * <p>操作人身份一律由服务端登录态决定，本请求不含操作人字段，也不信任调用方传入的用户 ID。</p>
 */
public record DispositionRequest(
        /** 处置类型 */
        @NotNull(message = "处置类型不能为空")
        DispositionType type,

        /** 被核验的执行记录 ID（仅 CONFIRM_SUCCESS / CONFIRM_NOT_EXECUTED 需要，其余为 null） */
        Long executionId,

        /** 处置原因（必填，不超过 500 字符） */
        @NotBlank(message = "处置原因不能为空")
        @Size(max = 500, message = "处置原因长度不能超过 500")
        String reason
) {
}
