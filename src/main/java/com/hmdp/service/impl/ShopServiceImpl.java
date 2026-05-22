package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_DELAY_DELETE_SECONDS;
import static com.hmdp.utils.RedisConstants.CACHE_HOT_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_LOGICAL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final ExecutorService CACHE_DELETE_EXECUTOR = Executors.newSingleThreadExecutor();

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        Shop hotShop = cacheClient.queryWithLogicalExpire(
                CACHE_HOT_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_LOGICAL_TTL,
                TimeUnit.MINUTES);
        if (hotShop != null) {
            return Result.ok(hotShop);
        }

        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                CACHE_SHOP_TTL,
                TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺更新失败");
        }
        deleteShopCacheAfterCommit(id);
        return Result.ok();
    }

    @Override
    public Result preheatHotShop(Long id) {
        Shop shop = getById(id);
        if (shop == null) {
            cacheClient.delete(CACHE_SHOP_KEY + id);
            cacheClient.delete(CACHE_HOT_SHOP_KEY + id);
            return Result.fail("店铺不存在");
        }
        cacheClient.setWithLogicalExpire(CACHE_HOT_SHOP_KEY + id, shop, CACHE_SHOP_LOGICAL_TTL, TimeUnit.MINUTES);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if (x == null || y == null) {
            Page<Shop> page = lambdaQuery()
                    .eq(Shop::getTypeId, typeId)
                    .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int end = current * SystemConstants.MAX_PAGE_SIZE;
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if (content.size() < from) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = new ArrayList<>(content.size());
        Map<String, Distance> distanceMap = new HashMap<>(content.size());
        content.stream().skip(from).forEach(result -> {
            String shopId = result.getContent().getName();
            ids.add(Long.valueOf(shopId));
            distanceMap.put(shopId, result.getDistance());
        });

        String join = StrUtil.join(",", ids);
        List<Shop> shopList = lambdaQuery()
                .in(Shop::getId, ids)
                .last("order by field(id," + join + ")")
                .list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shopList);
    }

    private void deleteShopCacheAfterCommit(Long id) {
        Runnable deleteTask = () -> {
            String key = CACHE_SHOP_KEY + id;
            String hotKey = CACHE_HOT_SHOP_KEY + id;
            cacheClient.delete(key);
            cacheClient.delete(hotKey);
            CACHE_DELETE_EXECUTOR.submit(() -> {
                try {
                    TimeUnit.SECONDS.sleep(CACHE_SHOP_DELAY_DELETE_SECONDS);
                    cacheClient.delete(key);
                    cacheClient.delete(hotKey);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteTask.run();
                }
            });
        } else {
            deleteTask.run();
        }
    }
}
