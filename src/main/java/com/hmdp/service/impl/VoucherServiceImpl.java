package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.TransactionUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.time.ZoneId;

import static com.hmdp.utils.RedisConstants.SECKILL_BEGIN_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_END_TIME_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 保存秒杀库存到 Redis，事务提交后执行避免 DB 回滚脏写
        TransactionUtils.afterCommit(() -> {
            stringRedisTemplate.opsForValue().set(
                    SECKILL_STOCK_KEY + voucher.getId(),
                    voucher.getStock().toString()
            );
            stringRedisTemplate.opsForValue().set(
                    SECKILL_BEGIN_TIME_KEY + voucher.getId(),
                    String.valueOf(voucher.getBeginTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            );
            stringRedisTemplate.opsForValue().set(
                    SECKILL_END_TIME_KEY + voucher.getId(),
                    String.valueOf(voucher.getEndTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            );
        });
    }
}
