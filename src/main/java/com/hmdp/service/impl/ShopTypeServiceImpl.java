package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
//        return Result.ok(typeList);
        String key = RedisConstants.CACHE_SHOP_TYPE_KEY + "index";
        String typeJson = stringRedisTemplate.opsForValue().get(key);
        List<ShopType> typeList = new ArrayList<>();
        if(StrUtil.isNotBlank(typeJson)){
            typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }

        typeList = query().orderByAsc("sort").list();
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList));

        return Result.ok(typeList);
    }
}
