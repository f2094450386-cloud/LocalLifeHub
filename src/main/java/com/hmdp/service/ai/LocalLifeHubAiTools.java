package com.hmdp.service.ai;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.entity.Voucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IShopTypeService;
import com.hmdp.service.IVoucherService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LocalLifeHubAiTools {

    private static final int MAX_SHOP_RESULT_SIZE = 5;
    private static final int MAX_VOUCHER_RESULT_SIZE = 10;
    private static final int MAX_CONTEXT_SHOP_SIZE = 3;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Resource
    private IShopService shopService;
    @Resource
    private IShopTypeService shopTypeService;
    @Resource
    private IVoucherService voucherService;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private AiAuditService auditService;

    @Tool(name = "query_shop_info", value = "按店铺 id、店铺名称或店铺类型查询商户信息。找不到时返回未查询到相关数据。")
    public String queryShopInfo(
            @P(value = "店铺 id，例如 1", required = false) Long shopId,
            @P(value = "店铺名称关键词，例如 海底捞", required = false) String shopName,
            @P(value = "店铺类型 id，例如 1", required = false) Long typeId,
            @P(value = "店铺类型名称，例如 美食、KTV", required = false) String typeName) {
        List<Shop> shops = doQueryShops(shopId, shopName, typeId, typeName);
        if (CollUtil.isEmpty(shops)) {
            auditService.logToolCall("query_shop_info",
                    "shopId=" + shopId + " shopName=" + shopName + " typeId=" + typeId + " typeName=" + typeName,
                    "未查询到相关数据");
            return "未查询到相关数据";
        }

        Map<Long, String> typeNameMap = buildShopTypeNameMap(shops);
        List<Map<String, Object>> records = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            records.add(buildShopRecord(shop, typeNameMap));
        }
        String result = JSONUtil.toJsonStr(records);
        auditService.logToolCall("query_shop_info",
                "shopId=" + shopId + " shopName=" + shopName + " typeId=" + typeId + " typeName=" + typeName,
                result);
        return result;
    }

    @Tool(name = "query_voucher_info", value = "按店铺 id 或优惠券 id 查询优惠券信息。找不到时返回未查询到相关数据。")
    public String queryVoucherInfo(
            @P(value = "店铺 id，例如 1", required = false) Long shopId,
            @P(value = "优惠券 id，例如 1", required = false) Long voucherId) {
        List<Voucher> vouchers = doQueryVouchers(shopId, voucherId);
        if (CollUtil.isEmpty(vouchers)) {
            auditService.logToolCall("query_voucher_info",
                    "shopId=" + shopId + " voucherId=" + voucherId,
                    "未查询到相关数据");
            return "未查询到相关数据";
        }

        List<Map<String, Object>> records = new ArrayList<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            records.add(buildVoucherRecord(voucher));
        }
        String result = JSONUtil.toJsonStr(records);
        auditService.logToolCall("query_voucher_info",
                "shopId=" + shopId + " voucherId=" + voucherId,
                result);
        return result;
    }

    public String buildReferenceContext(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }
        List<Shop> shops = findMentionedShops(message);
        if (CollUtil.isEmpty(shops)) {
            return "";
        }

        Map<Long, String> typeNameMap = buildShopTypeNameMap(shops);
        List<Map<String, Object>> records = new ArrayList<>(shops.size());
        for (Shop shop : shops) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shop", buildShopRecord(shop, typeNameMap));

            List<Voucher> vouchers = doQueryVouchers(shop.getId(), null);
            List<Map<String, Object>> voucherRecords = new ArrayList<>();
            for (Voucher voucher : vouchers) {
                voucherRecords.add(buildVoucherRecord(voucher));
            }
            item.put("vouchers", voucherRecords);
            records.add(item);
        }
        return JSONUtil.toJsonStr(records);
    }

    private List<Shop> doQueryShops(Long shopId, String shopName, Long typeId, String typeName) {
        if (shopId != null) {
            Shop shop = shopService.getById(shopId);
            if (shop == null) {
                return new ArrayList<>();
            }
            List<Shop> shops = new ArrayList<>(1);
            shops.add(shop);
            return shops;
        }
        if (StrUtil.isBlank(shopName) && typeId == null && StrUtil.isBlank(typeName)) {
            return new ArrayList<>();
        }

        List<Long> typeIds = resolveTypeIds(typeId, typeName);
        if (typeId != null || StrUtil.isNotBlank(typeName)) {
            if (CollUtil.isEmpty(typeIds)) {
                return new ArrayList<>();
            }
        }

        LambdaQueryWrapper<Shop> queryWrapper = new LambdaQueryWrapper<>();
        if (StrUtil.isNotBlank(shopName)) {
            queryWrapper.like(Shop::getName, shopName.trim());
        }
        if (CollUtil.isNotEmpty(typeIds)) {
            queryWrapper.in(Shop::getTypeId, typeIds);
        }
        queryWrapper.last("LIMIT " + MAX_SHOP_RESULT_SIZE);
        return shopService.list(queryWrapper);
    }

    private List<Voucher> doQueryVouchers(Long shopId, Long voucherId) {
        if (shopId == null && voucherId == null) {
            return new ArrayList<>();
        }
        LambdaQueryWrapper<Voucher> queryWrapper = new LambdaQueryWrapper<>();
        if (voucherId != null) {
            queryWrapper.eq(Voucher::getId, voucherId);
        }
        if (shopId != null) {
            queryWrapper.eq(Voucher::getShopId, shopId);
        }
        queryWrapper.eq(Voucher::getStatus, 1);
        queryWrapper.last("LIMIT " + MAX_VOUCHER_RESULT_SIZE);
        return voucherService.list(queryWrapper);
    }

    private List<Shop> findMentionedShops(String message) {
        String normalizedMessage = normalizeText(message);
        List<Shop> allShops = shopService.lambdaQuery().last("LIMIT 200").list();
        List<Shop> matched = new ArrayList<>();
        for (Shop shop : allShops) {
            String normalizedName = normalizeText(shop.getName());
            if (StrUtil.isBlank(normalizedName)) {
                continue;
            }
            String shortName = stripShopSuffix(normalizedName);
            if (normalizedMessage.contains(normalizedName)
                    || normalizedName.contains(normalizedMessage)
                    || (StrUtil.isNotBlank(shortName) && normalizedMessage.contains(shortName))) {
                matched.add(shop);
            }
            if (matched.size() >= MAX_CONTEXT_SHOP_SIZE) {
                break;
            }
        }
        return matched;
    }

    private Map<String, Object> buildShopRecord(Shop shop, Map<Long, String> typeNameMap) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", shop.getId());
        item.put("name", shop.getName());
        item.put("typeId", shop.getTypeId());
        item.put("typeName", typeNameMap.get(shop.getTypeId()));
        item.put("area", shop.getArea());
        item.put("address", shop.getAddress());
        item.put("avgPriceYuan", shop.getAvgPrice());
        item.put("score", shop.getScore() == null ? null : shop.getScore() / 10.0);
        item.put("sold", shop.getSold());
        item.put("comments", shop.getComments());
        item.put("openHours", shop.getOpenHours());
        return item;
    }

    private Map<String, Object> buildVoucherRecord(Voucher voucher) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", voucher.getId());
        item.put("shopId", voucher.getShopId());
        item.put("shopName", getShopName(voucher.getShopId()));
        item.put("title", voucher.getTitle());
        item.put("subTitle", voucher.getSubTitle());
        item.put("rules", voucher.getRules());
        item.put("payValueYuan", toYuan(voucher.getPayValue()));
        item.put("actualValueYuan", toYuan(voucher.getActualValue()));
        item.put("type", voucher.getType());
        item.put("status", voucher.getStatus());
        fillSeckillInfo(item, voucher.getId());
        return item;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("（", "(")
                .replace("）", ")")
                .replaceAll("\\s+", "")
                .toLowerCase();
    }

    private String stripShopSuffix(String normalizedName) {
        int index = normalizedName.indexOf("(");
        if (index > 0) {
            return normalizedName.substring(0, index);
        }
        return normalizedName;
    }

    private List<Long> resolveTypeIds(Long typeId, String typeName) {
        List<Long> typeIds = new ArrayList<>();
        if (typeId != null) {
            typeIds.add(typeId);
        }
        if (StrUtil.isNotBlank(typeName)) {
            List<ShopType> shopTypes = shopTypeService.lambdaQuery()
                    .like(ShopType::getName, typeName.trim())
                    .list();
            for (ShopType shopType : shopTypes) {
                typeIds.add(shopType.getId());
            }
        }
        return typeIds;
    }

    private Map<Long, String> buildShopTypeNameMap(List<Shop> shops) {
        List<Long> typeIds = new ArrayList<>();
        for (Shop shop : shops) {
            if (shop.getTypeId() != null && !typeIds.contains(shop.getTypeId())) {
                typeIds.add(shop.getTypeId());
            }
        }
        Map<Long, String> typeNameMap = new HashMap<>();
        if (CollUtil.isEmpty(typeIds)) {
            return typeNameMap;
        }
        List<ShopType> shopTypes = shopTypeService.listByIds(typeIds);
        for (ShopType shopType : shopTypes) {
            typeNameMap.put(shopType.getId(), shopType.getName());
        }
        return typeNameMap;
    }

    private String getShopName(Long shopId) {
        if (shopId == null) {
            return null;
        }
        Shop shop = shopService.getById(shopId);
        return shop == null ? null : shop.getName();
    }

    private void fillSeckillInfo(Map<String, Object> item, Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            item.put("seckill", false);
            return;
        }
        item.put("seckill", true);
        item.put("stock", seckillVoucher.getStock());
        item.put("beginTime", seckillVoucher.getBeginTime() == null ? null : DATE_TIME_FORMATTER.format(seckillVoucher.getBeginTime()));
        item.put("endTime", seckillVoucher.getEndTime() == null ? null : DATE_TIME_FORMATTER.format(seckillVoucher.getEndTime()));
    }

    private String toYuan(Long value) {
        if (value == null) {
            return null;
        }
        return String.format("%.2f", value / 100.0);
    }
}
