package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static cn.hutool.poi.excel.sax.AttributeName.r;
import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);

    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData), time, timeUnit);
    }


    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        //4. 不存在，根据id查询数据库
        if(json != null){
            return null;
        }
        R r = dbFallBack.apply(id);
        //5. 不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //6. 不存在，返回错误
            return null;
        }
        //6. 存在，写入redis
        this.set(key,r,time,timeUnit);

        //7. 返回
        return r;
    }

    private static final ExecutorService CACHE_REBUID_EXECUTOR = Executors.newFixedThreadPool(10);

    public  <R,ID> R queeryWithLogicalExpire(String keyPrefix, ID id,Class<R> type, Function<ID, R> dbFallBack,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            //3. 存在，直接返回
            return null;
        }
        //4. 命中，需要先把json反序列化为对象
        RedisDate redisDate = JSONUtil.toBean(json, RedisDate.class);
        R r = JSONUtil.toBean((JSONObject) redisDate.getData(), type);
        LocalDateTime expireTime = redisDate.getExpireTime();
        //5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //6. 未过期，直接返回店铺信息
            return r;
        }
        //6. 已过期，需要缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            // TODO 6.3成功，开启独立线程 实现缓冲重建
            CACHE_REBUID_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });

        }

        //7. 返回
        return r;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }


}
