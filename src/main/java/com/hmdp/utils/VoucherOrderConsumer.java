package com.hmdp.utils;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
@Slf4j
@KafkaListener(topics = "voucher-orders", groupId = "voucher-order-group")
public class VoucherOrderConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private RedissonClient redissonClient;

    @KafkaHandler
    public void handleVoucherOrder(VoucherOrder voucherOrder, Acknowledgment acknowledgment){
        // 1. 获取用户ID
        Long userId =  voucherOrder.getUserId();
        // 2. 创建分布式锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 3. 尝试获取锁
        boolean locked = lock.tryLock();
        if (!locked) {
            log.error("不允许重复下单！");
            return;
        }
        try {
            // 4. 处理订单创建逻辑
            voucherOrderService.createVoucherOrder(voucherOrder);
            log.info("订单创建成功，orderId: {}", voucherOrder.getId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("处理订单失败，将触发重试。orderId: {}, error: {}", voucherOrder.getId(), e.getMessage(), e);
            // 只要不调用 acknowledge()，Spring Kafka 就会认为消费失败
            // 结合配置的重试机制，它会再次拉取这条消息
            throw new RuntimeException("订单处理失败", e);
        } finally {
            lock.unlock();
        }
    }
}