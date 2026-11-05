package cn.jia.chat.archive.http;

import cn.jia.chat.archive.service.ArchiveMutationResult;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.util.JsonUtil;

/** Adapts durable business receipts back into the common HTTP envelope boundary. */
final class ArchiveMutationResponses {
    private ArchiveMutationResponses() {}

    static JsonResult<?> envelope(ArchiveMutationResult result) {
        JsonResult<?> envelope = JsonUtil.getMapper().readValue(result.body(), JsonResult.class);
        // A replay belongs to this HTTP request, not the request that stored the receipt.
        // ApiEnvelopeAdvice assigns correlation; durable bytes remain unchanged.
        envelope.setRequestId(null);
        return envelope;
    }
}
