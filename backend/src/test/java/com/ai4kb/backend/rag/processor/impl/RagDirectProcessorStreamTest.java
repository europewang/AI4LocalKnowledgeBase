package com.ai4kb.backend.rag.processor.impl;

import com.ai4kb.backend.user.client.RagFlowClient;
import com.ai4kb.backend.user.entity.Permission;
import com.ai4kb.backend.user.entity.User;
import com.ai4kb.backend.user.mapper.PermissionMapper;
import com.ai4kb.backend.user.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

class RagDirectProcessorStreamTest {

    @Test
    void convertStream_shouldKeepReference_whenReferenceArrivesInMessageField() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RagDirectProcessor processor = new RagDirectProcessor(
                ragFlowClient,
                permissionMapper,
                userMapper,
                objectMapper
        );

        Flux<String> upstream = Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半面积计算\"}}]}",
                "data: {\"choices\":[{\"message\":{\"content\":\"可参考规范\",\"reference\":{\"chunks\":[{\"document_name\":\"规范A\",\"content\":\"按顶盖投影面积1/2\",\"similarity\":0.93}]}}}]}",
                "data: [DONE]"
        );

        @SuppressWarnings("unchecked")
        Flux<String> converted = (Flux<String>) ReflectionTestUtils.invokeMethod(
                processor,
                "_convertRagFlowStreamToAnswer",
                upstream,
                (java.util.function.Supplier<Mono<com.fasterxml.jackson.databind.JsonNode>>) Mono::empty
        );
        List<String> items = converted.collectList().block();

        Assertions.assertNotNull(items);
        Assertions.assertEquals(3, items.size());
        Assertions.assertTrue(items.get(0).contains("\"answer\":\"半面积计算\""));
        Assertions.assertTrue(items.get(1).contains("\"reference\":["));
        Assertions.assertTrue(items.get(1).contains("\"document_name\":\"规范A\""));
        Assertions.assertEquals("[DONE]", items.get(2));
    }

    @Test
    void convertStream_shouldAppendReferencePayload_whenStreamHasNoReference() {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RagDirectProcessor processor = new RagDirectProcessor(
                ragFlowClient,
                permissionMapper,
                userMapper,
                objectMapper
        );

        Flux<String> upstream = Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半面积计算按投影面积处理\"}}]}",
                "data: [DONE]"
        );
        com.fasterxml.jackson.databind.JsonNode fallbackRef;
        try {
            fallbackRef = objectMapper.readTree("[{\"document_name\":\"规范B\",\"content\":\"按投影面积1/2\",\"similarity\":0.91}]");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        @SuppressWarnings("unchecked")
        Flux<String> converted = (Flux<String>) ReflectionTestUtils.invokeMethod(
                processor,
                "_convertRagFlowStreamToAnswer",
                upstream,
                (java.util.function.Supplier<Mono<com.fasterxml.jackson.databind.JsonNode>>) () -> Mono.just(fallbackRef)
        );
        List<String> items = converted.collectList().block();

        Assertions.assertNotNull(items);
        Assertions.assertEquals(3, items.size());
        Assertions.assertTrue(items.get(0).contains("\"answer\":\"半面积计算按投影面积处理\""));
        Assertions.assertTrue(items.get(1).contains("\"answer\":\"\""));
        Assertions.assertTrue(items.get(1).contains("\"reference\":["));
        Assertions.assertTrue(items.get(1).contains("\"document_name\":\"规范B\""));
        Assertions.assertEquals("[DONE]", items.get(2));
    }

    @Test
    void process_shouldFallbackToCompletionWithReference_whenStreamFails() throws Exception {
        RagFlowClient ragFlowClient = Mockito.mock(RagFlowClient.class);
        PermissionMapper permissionMapper = Mockito.mock(PermissionMapper.class);
        UserMapper userMapper = Mockito.mock(UserMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        RagDirectProcessor processor = new RagDirectProcessor(
                ragFlowClient,
                permissionMapper,
                userMapper,
                objectMapper
        );

        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        Permission permission = new Permission();
        permission.setUserId(1L);
        permission.setResourceType("DATASET");
        permission.setResourceId("ds-1");
        Mockito.when(userMapper.selectOne(Mockito.any())).thenReturn(user);
        Mockito.when(permissionMapper.selectList(Mockito.any())).thenReturn(List.of(permission));
        Mockito.when(ragFlowClient.listDatasets(1, 100)).thenReturn(Mono.just(objectMapper.readTree("""
                {"data":[{"id":"ds-1","chunk_count":1,"document_count":1}]}
                """)));
        Mockito.when(ragFlowClient.createConversation(Mockito.anyString(), Mockito.anyList())).thenReturn(Mono.just("conv-1"));
        Mockito.when(ragFlowClient.chatStream("conv-1", "如何进行半面积计算"))
                .thenReturn(Flux.error(new RuntimeException("stream down")));
        Mockito.when(ragFlowClient.chatCompletion("conv-1", "如何进行半面积计算"))
                .thenReturn(Mono.just(objectMapper.readTree("""
                        {"choices":[{"message":{"content":"半面积按顶盖水平投影面积的1/2计算","reference":{"chunks":[{"document_name":"规范C","content":"按顶盖水平投影面积1/2计算","similarity":0.9}]}}}]}
                        """)));

        List<String> items = processor.process("admin", "如何进行半面积计算", true).collectList().block();

        Assertions.assertNotNull(items);
        Assertions.assertEquals(2, items.size());
        Assertions.assertTrue(items.get(0).contains("\"answer\":\"半面积按顶盖水平投影面积的1/2计算\""));
        Assertions.assertTrue(items.get(0).contains("\"reference\":["));
        Assertions.assertTrue(items.get(0).contains("\"document_name\":\"规范C\""));
        Assertions.assertEquals("[DONE]", items.get(1));
    }
}
