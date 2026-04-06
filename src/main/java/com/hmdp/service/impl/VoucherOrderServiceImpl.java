package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import static com.hmdp.utils.RedisConstants.SECKILL_VOUCHER_ORDER;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, VoucherOrder> kafkaTemplate;

    /**
     * 加载 判断秒杀券库存是否充足 并且 判断用户是否已下单 的Lua脚本
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Transactional
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long result;
        try {
            result = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    UserHolder.getUser().getId().toString()
            );
        } catch (Exception e) {
            log.error("Lua脚本执行失败");
            throw new RuntimeException(e);
        }
        if (!result.equals(0L)) {
            // result为1表示库存不足，result为2表示用户已下单
            int r = result.intValue();
            return Result.fail(r == 2 ? "不能重复下单" : "库存不足");
        }

        // 2、result为0，用户具有秒杀资格，将订单保存到阻塞队列中，实现异步下单
        long orderId = redisIdWorker.nextId(SECKILL_VOUCHER_ORDER);
        // 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        voucherOrder.setVoucherId(voucherId);
        kafkaTemplate.send("voucher-orders", voucherOrder);

        return Result.ok();
    }

    /**
     * 1. 在事务外面加锁的原因：会先释放锁，再提交事务，这样在释放锁和提交事务的间隙，
     * 如果有线程再来获取锁，会导致并发问题。
     * 2. 用代理对象调用第三方的事务方法，防止事务失效。
     */
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {

        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //一人一单
        // 1、判断当前用户是否是第一单
        int count = Math.toIntExact(query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count());
        if (count >= 1) {
            // 当前用户不是第一单
            log.info("不可重复下单");
            return;
        }

        // 5、秒杀券合法，则秒杀券抢购成功，秒杀券库存数量减一
        boolean flag = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!flag) {
            throw new RuntimeException("秒杀券扣减失败");
        }

        flag = this.save(voucherOrder);
        if (!flag) {
            throw new RuntimeException("创建秒杀券订单失败");
        }

    }

}
