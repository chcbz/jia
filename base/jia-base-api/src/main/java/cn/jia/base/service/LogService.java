package cn.jia.base.service;

import cn.jia.base.entity.LogEntity;
import cn.jia.common.service.IBaseService;
import cn.jia.core.common.EsRequestWrapper;

public interface LogService extends IBaseService<LogEntity> {
    /** Captures and sanitizes request metadata without writing to the database. */
    LogEntity captureLog(EsRequestWrapper esRequestWrapper);

    /** Persists an already-sanitized detached audit record in an independent transaction. */
    LogEntity persistLog(LogEntity logEntity);

    /** Compatibility entry point for callers that explicitly require synchronous persistence. */
    LogEntity addLog(EsRequestWrapper esRequestWrapper);
}
