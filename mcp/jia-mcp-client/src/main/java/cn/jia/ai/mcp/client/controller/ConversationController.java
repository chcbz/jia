package cn.jia.ai.mcp.client.controller;

import cn.jia.ai.mcp.client.memory.ElasticsearchChatMemoryRepository;
import cn.jia.core.entity.JsonResult;
import cn.jia.kefu.service.KefuMessageService;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

@RestController
@RequestMapping("/mcp/conversation")
@RequiredArgsConstructor
public class ConversationController {
    private final ElasticsearchVectorStore vectorStore;
    private final KefuMessageService kefuMessageService;

    @RequestMapping(value = "/delete", method = RequestMethod.DELETE)
    public Object deleteConversation(@RequestParam(name = "id") String id) {
        ElasticsearchChatMemoryRepository.builder().vectorStore(vectorStore).build()
                .deleteByConversationId(id);
        kefuMessageService.delete(id);
        return JsonResult.success();
    }

    @RequestMapping(value = "/content", method = RequestMethod.GET)
    public Object listConversation(@RequestParam(name = "id") String id) {
        ElasticsearchChatMemoryRepository repository =
                ElasticsearchChatMemoryRepository.builder().vectorStore(vectorStore).build();
        return JsonResult.success(repository.findByConversationId(id).stream().sorted((o1, o2) ->
                Optional.ofNullable(o1.getMetadata().get("timestamp"))
                        .map(o -> (Long) o).orElse(0L)
                        .compareTo(Optional.ofNullable(o2.getMetadata().get("timestamp"))
                                .map(o -> (Long) o).orElse(0L))));
    }
}
