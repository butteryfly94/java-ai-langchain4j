package com.mayaping.ai.langchain4j.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 复杂度路由配置：分类专用的轻量对话模型
 *
 * 为什么不复用已有的qwenChatModel：那个bean是qwen-max，拿它做"这句话复杂不复杂"的二分类
 * 属于杀鸡用牛刀——会给"规则判不出时"的兜底路径压上约1秒首字节延迟和约10倍token成本。
 * 这里单独建一个qwen-turbo的bean：
 * - temperature压到0.1保证判定稳定可复现（DashScope要求temperature在[0,2)，不用0是避开个别模型的边界校验）
 * - maxTokens给16，只需要吐SIMPLE/COMPLEX一个词，防止模型啰嗦浪费延迟
 *
 * 注意：classpath上已有三个starter自动装配的ChatModel（qwenChatModel/openAiChatModel/ollamaChatModel），
 * 加上这个bean共四个候选，任何按类型注入ChatModel的地方都必须带@Qualifier
 */
@Configuration
public class QueryRouterConfig {

    @Bean
    public ChatModel routerChatModel(@Value("${rag.router.model:qwen-turbo}") String modelName) {
        return QwenChatModel.builder()
                .apiKey(System.getenv("DASH_SCOPE_API_KEY"))
                .modelName(modelName)
                .temperature(0.1f)
                .maxTokens(16)
                .build();
    }
}
