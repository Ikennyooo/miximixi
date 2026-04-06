package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.RedisData;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    /**
     * 线程池
     */
    public static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {

        // 调用解决缓存穿透的方法
//        Shop shop = cacheClient.handleCachePenetration(CACHE_SHOP_KEY, id, Shop.class,
//                this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        if (Objects.isNull(shop)) {
//            return Result.fail("店铺不存在");
//        }

        // 调用解决缓存击穿的方法
        Shop shop = cacheClient.handleCacheBreakdown(CACHE_SHOP_KEY, id, Shop.class,
                this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (Objects.isNull(shop)) {
            return Result.fail("店铺不存在");
        }

        return Result.ok(shop);
    }


    public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //查询Redis里是否有该id商户数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //过滤后剩下""和null，要的是""
        if (shopJson != null) {
            return null;
        }
        //null之后（缓存中没有）
        //尝试获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //如果没有获取成功
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }

            //获取成功doubleCheck
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(shopJson)) {
                shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //过滤后剩下""和null，要的是""
            if (shopJson != null) {
                return null;
            }

            //获取成功并且再次确认Redis里面没有之后，查询数据库
            shop = getById(id);

            //模拟重建延时
            Thread.sleep(200);

            //数据库也没有，将null写入缓存
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //写入Redis缓存中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unlock(lockKey);
        }
        //返回
        return shop;
    }


    public Shop queryWithLogicExpired(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //查询Redis里是否有该id商户数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expiredTime = redisData.getExpiredTime();
        //没过期，返回商铺信息
        if (expiredTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        //过期之后：
        //尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //获取到了，开启独立线程
        if (isLock) {
            //获取到了之后doubleCheck
            shopJson = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(shopJson)) {
                return null;
            }
            redisData = JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            shop = JSONUtil.toBean(data, Shop.class);
            expiredTime = redisData.getExpiredTime();
            //没过期，返回商铺信息
            if (expiredTime.isAfter(LocalDateTime.now())) {
                return shop;
            }
            //doubleCheck完后：
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveShopToCache(id, CACHE_SHOP_LOGICAL_TTL);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
    }


    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //查询Redis里是否有该id商户数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //过滤后剩下""和null，要的是""
        if (shopJson != null) {
            return null;
        }

        //若没有，查询数据库
        Shop shop = getById(id);
        //数据库也没有，将null写入缓存
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //写入Redis缓存中
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return shop;
    }

    public void saveShopToCache(Long id, Long expireTime) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpiredTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", TRY_LOCK_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    @Override
    @Transactional
    public Result update(Shop shop) {

        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok();
    }
}
