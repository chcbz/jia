package cn.jia.ai.mcp.client.controller;

import cn.jia.core.entity.JsonResult;
import org.springframework.ai.vectorstore.elasticsearch.ElasticsearchVectorStore;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import lombok.RequiredArgsConstructor;
import cn.jia.ai.mcp.client.memory.ElasticsearchChatMemoryRepository;

@RestController
@RequestMapping("/mcp/conversation")
@RequiredArgsConstructor
public class ConversationController {
    private final ElasticsearchVectorStore vectorStore;

    @RequestMapping(value = "/delete/{id}", method = RequestMethod.DELETE)
    public Object deleteConversation(@PathVariable String id) {
        ElasticsearchChatMemoryRepository.builder().vectorStore(vectorStore).build()
                .deleteByConversationId(id);
        return JsonResult.success();
    }
}
