package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host}")
    private String host;
    @Value("${spring.redis.port}")
    private String port;
    @Value("${spring.redis.password}")
    private String password;

    /**
     * 创建Redisson配置对象，然后交给IOC管理
     *
     * @return
     */
    @Bean
    public RedissonClient redissonClient() {
        // 获取Redisson配置对象
        Config config = new Config();
        // 添加redis地址，这里添加的是单节点地址，也可以通过 config.userClusterServers()添加集群地址
        config.useSingleServer().setAddress("redis://" + this.host + ":" + this.port)
                .setPassword(this.password);
        // 获取RedisClient对象，并交给IOC进行管理
        return Redisson.create(config);
    }
}
