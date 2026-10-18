package com.argus.rag.ticket.model.dto;

import jakarta.validation.constraints.Size;

/**
 * 审批决定请求（审批通过 / 审批拒绝共用）。
 * <p>
 * 审批意见可选，approve / reject 均不强制填写。请求体整体缺省时（配合控制器
 * {@code @RequestBody(required = false)}）参数为 null，此时按 null 意见处理。
 */
public record ApprovalDecisionRequest(
        /** 审批意见（可选，最长 500 字符） */
        @Size(max = 500, message = "审批意见长度不能超过 500")
        String comment
) {
}
