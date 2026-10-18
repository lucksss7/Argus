package com.argus.rag.ticket.mapper;

import com.argus.rag.ticket.model.entity.TicketDisposition;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 人工处置审计 Mapper。
 */
@Mapper
public interface TicketDispositionMapper extends BaseMapper<TicketDisposition> {
}
