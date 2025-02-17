package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch letch = new CountDownLatch(300);

        Runnable runnable = () -> {
            for (int i = 0; i < 10; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            letch.countDown();
        };
        long start = System.currentTimeMillis();
        for(int i=0;i<300;i++){
            es.submit(runnable);
        }
        letch.await();
        long end = System.currentTimeMillis();
        System.out.println("time="+(end-start));

    }

    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }

}
