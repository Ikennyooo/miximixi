package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    /**
     * RedisTemplate
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 锁的名称
     */
    private final String name;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    private static final String KEY_PREFIX = "lock:";
    /**
     * ID前缀
     */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        //resources就是ClassPath下的
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    /**
     * 获取锁
     *
     * @param timeoutSec 超时时间
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().threadId();
        // SET lock:name id EX timeoutSec NX
        Boolean result = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 释放锁
     */
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().threadId());
    }

    /*@Override
    public void unlock() {
        String currentThreadFlag = ID_PREFIX + Thread.currentThread().getId();
        String redisThreadFlag = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if (currentThreadFlag.equals(redisThreadFlag)) {
            // 一致，说明当前的锁就是当前线程的锁，可以直接释放
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}

