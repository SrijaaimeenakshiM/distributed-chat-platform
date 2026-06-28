package com.project.chat.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.project.chat.service.RedisMessageSubscriber;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * Lettuce connection pool configuration.
     * Lettuce connections are thread-safe; pooling reduces
     * connection establishment overhead under high concurrency.
     */
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration(redisHost, redisPort);
        if (redisPassword != null && !redisPassword.isBlank()) {
            serverConfig.setPassword(redisPassword);
        }

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(5);
        poolConfig.setMaxWait(Duration.ofMillis(1000));
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build())
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .build();

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
                .poolConfig(poolConfig)
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofSeconds(2))
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(serverConfig, clientConfig);
        factory.setValidateConnection(true);
        return factory;
    }

    /**
     * Primary RedisTemplate with JSON serialization.
     * Keys are Strings; values are JSON-serialized objects.
     * GenericJackson2JsonRedisSerializer stores type info so
     * deserialization reconstructs the correct Java type.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        objectMapper.activateDefaultTyping(
                objectMapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setEnableTransactionSupport(false); // pub/sub doesn't use transactions
        template.afterPropertiesSet();
        return template;
    }

    /**
     * StringRedisTemplate for pub/sub message payloads (JSON strings)
     * and simple string operations like rate limiting counters.
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * MessageListenerAdapter wraps our RedisMessageSubscriber so Spring
     * can call it from the listener container's thread pool.
     * "handleMessage" is the method name the adapter will invoke.
     */
    @Bean
    public MessageListenerAdapter messageListenerAdapter(RedisMessageSubscriber subscriber) {
        MessageListenerAdapter adapter = new MessageListenerAdapter(subscriber, "handleMessage");
        adapter.setSerializer(new StringRedisSerializer());
        return adapter;
    }

    /**
     * RedisMessageListenerContainer subscribes to chat:room:* pattern on startup.
     * Every server instance subscribes to ALL room channels; Redis pub/sub
     * delivers messages from other nodes, enabling cross-server fan-out
     * without a shared in-memory broker.
     *
     * Uses a dedicated thread pool (not the main executor) to avoid
     * listener processing blocking unrelated request threads.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Subscribe to ALL room channels via wildcard pattern.
        // Pattern: "chat:room:*" matches chat:room:1, chat:room:abc, etc.
        container.addMessageListener(messageListenerAdapter, new PatternTopic("chat:room:*"));

        // Dedicated thread for the listener loop (not a virtual thread —
        // this is a long-lived blocking subscription, not a short task).
        container.setTaskExecutor(java.util.concurrent.Executors.newSingleThreadExecutor(
                r -> {
                    Thread t = new Thread(r, "redis-listener");
                    t.setDaemon(true);
                    return t;
                }));

        log.info("[Redis] Subscribed to pattern: chat:room:*");
        return container;
    }

    /**
     * Separate ObjectMapper bean for Redis subscriber message deserialization.
     * Configured without type embedding to parse plain JSON chat payloads.
     */
    @Bean(name = "redisObjectMapper")
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature
                .FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
