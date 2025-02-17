package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisDate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queeryWithPassThrough(id);
        //互斥锁解决缓存穿透问题
        Shop shop = queeryWithMutex(id);
       if(shop == null){
           return Result.fail("店铺不存在! ");
       }
        //7. 返回
        return Result.ok(shop);
    }

    public  Shop queeryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //4. 不存在，根据id查询数据库
        if(shopJson != null){
            return null;
        }
        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                Thread.sleep(50);
                return queeryWithMutex(id);
            }
            //4.3 失败，休眠并重试
            //4.4 成功，根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);

            //5. 不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                //6. 不存在，返回错误
                return null;
            }
            //6. 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unLock(lockKey);
        }

        //8. 返回
        return shop;
    }


    public  Shop queeryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY + id;
        //从redis查询缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //4. 不存在，根据id查询数据库
        if(shopJson != null){
            return null;
        }
        Shop shop = getById(id);
        //5. 不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //6. 不存在，返回错误
            return null;
        }
        //6. 存在，写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //7. 返回
        return shop;
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id,Long expireSeconds) {
        //1. 查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisDate redisDate = new RedisDate();
        redisDate.setData(shop);
        redisDate.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));

        //3.写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisDate));


    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空！");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }
}
