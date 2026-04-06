package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private LocalDateTime expiredTime;
    private Object data;

}
