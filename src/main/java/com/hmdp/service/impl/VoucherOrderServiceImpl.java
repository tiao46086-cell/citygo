package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMqConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderCancelMessage;
import com.hmdp.dto.VoucherOrderCreateStatus;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.dto.VoucherOrderStatus;
import com.hmdp.dto.VoucherOrderView;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.UserMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpAuthenticationException;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.hmdp.utils.RedisConstants.PENDING_NEXT_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_RETRYING;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_STOPPED;
import static com.hmdp.utils.RedisConstants.PENDING_STATE_WAITING;
import static com.hmdp.utils.RedisConstants.PENDING_USER_FIELD;
import static com.hmdp.utils.RedisConstants.PENDING_VOUCHER_FIELD;
import static com.hmdp.utils.RedisConstants.SECKILL_FAILED_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_HOLD_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_LIMIT_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_DELAY_MILLIS;
import static com.hmdp.utils.RedisConstants.SECKILL_PENDING_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_RESTORE_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_VOUCHER_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;
    private static final DefaultRedisScript<Long> LIMIT_SCRIPT;
    /** 同一个用户 1 秒内最多打这么多次秒杀接口，多出来的请求不进入 Lua */
    private static final int SECKILL_LIMIT_COUNT = 5;
    private static final int SECKILL_LIMIT_SECONDS = 1;
    /** 等待 broker 确认的时间。超时只能说明确认没回来，消息仍可能已经进队列 */
    private static final long CONFIRM_WAIT_SECONDS = 3L;
    /** 0 还没分出结果；1 未支付；2 已支付；4 已取消；7 下单失败，不能再变成未支付 */
    private static final int ORDER_STATUS_OPEN = 0;
    private static final int ORDER_STATUS_UNPAID = 1;
    private static final int ORDER_STATUS_PAID = 2;
    private static final int ORDER_STATUS_CANCELLED = 4;
    private static final int ORDER_STATUS_FAILED = 7;
    /** 与 tb_voucher.type 的约定一致：0 普通券，1 秒杀券 */
    private static final int VOUCHER_TYPE_NORMAL = 0;
    private static final int VOUCHER_TYPE_SECKILL = 1;
    private static final int VOUCHER_STATUS_ON = 1;
    /** 下单最终失败后，查询接口在这段时间内返回「下单失败」 */
    private static final long SECKILL_FAILED_MINUTES = 10L;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
        LIMIT_SCRIPT = new DefaultRedisScript<>();
        LIMIT_SCRIPT.setLocation(new ClassPathResource("seckill_limit.lua"));
        LIMIT_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private VoucherMapper voucherMapper;

    @Resource
    private UserMapper userMapper;

    /** 取消和回补要走接口代理，本类里直接调用不会打开事务 */
    @Resource
    @Lazy
    private IVoucherOrderService self;

    @Resource
    private IShopService shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Value("${citygo.seckill.unpaid-timeout:15m}")
    private Duration unpaidTimeout;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long allowed = stringRedisTemplate.execute(
                LIMIT_SCRIPT,
                Collections.singletonList(SECKILL_LIMIT_KEY + userId),
                String.valueOf(SECKILL_LIMIT_COUNT),
                String.valueOf(SECKILL_LIMIT_SECONDS)
        );
        if (allowed == null || allowed == 0L) {
            return Result.fail("操作太频繁，请稍后再试");
        }
        // 先有订单号，再预占。Redis 里的资格才能对上这一单
        Long orderId = redisIdWorker.nextId("order");
        List<String> keys = Arrays.asList(
                SECKILL_STOCK_KEY + voucherId,
                SECKILL_ORDER_KEY + voucherId,
                SECKILL_VOUCHER_KEY + voucherId,
                SECKILL_HOLD_KEY + voucherId,
                SECKILL_PENDING_KEY + orderId
        );
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                userId.toString(),
                orderId.toString(),
                voucherId.toString(),
                String.valueOf(System.currentTimeMillis() + SECKILL_PENDING_DELAY_MILLIS)
        );
        if (result == null) {
            return Result.fail("秒杀失败");
        }
        int code = result.intValue();
        if (code != 0) {
            return Result.fail(seckillFailMessage(code));
        }
        VoucherOrderMessage message = new VoucherOrderMessage();
        message.setOrderId(orderId);
        message.setUserId(userId);
        message.setVoucherId(voucherId);
        CorrelationData correlationData = new CorrelationData(orderId.toString());
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_QUEUE, message, correlationData);
        } catch (AmqpConnectException | AmqpAuthenticationException e) {
            // 连接或账号在发出去之前就失败了，broker 里没有这条消息
            log.error("秒杀消息没有发出去, orderId={}", orderId, e);
            restoreSeckill(voucherId, userId, orderId);
            return Result.fail("下单失败，请重试");
        } catch (RuntimeException e) {
            // 已经调用了发送，但不能确定进没进队列。预占留着，查询接口会看到处理中
            log.error("秒杀消息发送结果不确定, orderId={}", orderId, e);
            return Result.ok(orderId.toString());
        }
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            boolean accepted = confirm != null && confirm.isAck() && correlationData.getReturned() == null;
            if (accepted) {
                return Result.ok(orderId.toString());
            }
            log.error("秒杀消息被拒绝, orderId={}, confirm={}", orderId, confirm);
        } catch (TimeoutException e) {
            log.error("等待发布确认超时, orderId={}", orderId);
            return Result.ok(orderId.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("等待发布确认被中断, orderId={}", orderId);
            return Result.ok(orderId.toString());
        } catch (ExecutionException e) {
            log.error("发布确认失败, orderId={}", orderId, e);
            return Result.ok(orderId.toString());
        }
        restoreSeckill(voucherId, userId, orderId);
        return Result.fail("下单失败，请重试");
    }

    @Override
    @Transactional
    public Result placeOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Voucher voucher = voucherId == null ? null : voucherService.getById(voucherId);
        if (voucher == null || voucher.getStatus() == null || voucher.getStatus() != VOUCHER_STATUS_ON) {
            return Result.fail("优惠券不存在");
        }
        if (voucher.getType() != null && voucher.getType() == VOUCHER_TYPE_SECKILL) {
            return Result.fail("请使用限时抢购");
        }
        if (voucher.getType() == null || voucher.getType() != VOUCHER_TYPE_NORMAL) {
            return Result.fail("优惠券不存在");
        }
        int deducted = voucherMapper.deductNormalStock(voucherId);
        if (deducted != 1) {
            return Result.fail("库存不足");
        }
        Long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setStatus(ORDER_STATUS_UNPAID);
        save(voucherOrder);
        try {
            scheduleUnpaidCancel(orderId);
        } catch (RuntimeException e) {
            // 订单已经扣了库存。消息没发出去也保留，扫描器会按创建时间取消并回补
            log.error("普通订单的取消消息没有发出去，交给扫描器, orderId={}", orderId, e);
        }
        return Result.ok(orderId.toString());
    }

    @Override
    public Result queryOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order != null && userId.equals(order.getUserId()) && !hiddenByDemoCutoff(userId, orderId)) {
            VoucherOrderStatus view = new VoucherOrderStatus();
            view.setOrderId(orderId);
            view.setStatus(order.getStatus());
            view.setState(orderState(order));
            return Result.ok(view);
        }
        if (order != null) {
            return Result.fail("订单不存在");
        }
        // 只读预占。不能 GET 字符串或改 nextRetryAt，否则补发时间会被查询推迟
        if (pendingBelongsTo(orderId, userId)) {
            VoucherOrderStatus view = new VoucherOrderStatus();
            view.setOrderId(orderId);
            view.setState("处理中");
            return Result.ok(view);
        }
        String failedUser = stringRedisTemplate.opsForValue().get(SECKILL_FAILED_KEY + orderId);
        if (userId.toString().equals(failedUser)) {
            VoucherOrderStatus view = new VoucherOrderStatus();
            view.setOrderId(orderId);
            view.setState("下单失败");
            return Result.ok(view);
        }
        return Result.fail("订单不存在");
    }

    @Override
    public Result pageMine(Integer current) {
        int pageNo = current == null || current < 1 ? 1 : current;
        Long userId = UserHolder.getUser().getId();
        Long after = demoOrderAfter(userId);
        Page<VoucherOrder> page = query()
                .eq("user_id", userId)
                .gt(after != null, "id", after == null ? 0L : after)
                .notIn("status", ORDER_STATUS_OPEN, ORDER_STATUS_FAILED)
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(new Page<>(pageNo, SystemConstants.MAX_PAGE_SIZE));
        List<VoucherOrder> orders = page.getRecords();
        if (orders.isEmpty()) {
            return Result.ok(new ArrayList<VoucherOrderView>(), page.getTotal());
        }
        Set<Long> expiredIds = expiredUnpaidIds(orders);
        List<Long> voucherIds = orders.stream()
                .map(VoucherOrder::getVoucherId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Voucher> vouchers = voucherIds.isEmpty() ? Collections.emptyMap()
                : voucherService.listByIds(voucherIds).stream()
                .collect(Collectors.toMap(Voucher::getId, voucher -> voucher));
        List<Long> shopIds = vouchers.values().stream()
                .map(Voucher::getShopId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Shop> shops = shopIds.isEmpty() ? Collections.emptyMap()
                : shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, shop -> shop));
        List<VoucherOrderView> views = new ArrayList<>();
        for (VoucherOrder order : orders) {
            VoucherOrderView view = new VoucherOrderView();
            view.setOrderId(order.getId());
            view.setStatus(order.getStatus());
            view.setState(orderState(order, expiredIds));
            view.setCreateTime(order.getCreateTime());
            Voucher voucher = vouchers.get(order.getVoucherId());
            if (voucher != null) {
                view.setTitle(voucher.getTitle());
                view.setPayValue(voucher.getPayValue());
                Shop shop = shops.get(voucher.getShopId());
                if (shop != null) {
                    view.setShopName(shop.getName());
                }
            }
            views.add(view);
        }
        return Result.ok(views, page.getTotal());
    }

    @Override
    @Transactional
    public VoucherOrderCreateStatus createVoucherOrder(VoucherOrderMessage message) {
        if (message == null || message.getOrderId() == null
                || message.getUserId() == null || message.getVoucherId() == null) {
            log.error("秒杀订单消息不完整: {}", message);
            return VoucherOrderCreateStatus.INVALID;
        }
        // 先占订单号，再把 status 从 0 改成未支付。改不到说明终止或另一条消息已经赢了，不能扣库存
        getBaseMapper().insertUnclaimed(message.getOrderId(), message.getUserId(), message.getVoucherId());
        int claimed = getBaseMapper().claim(message.getOrderId(), ORDER_STATUS_UNPAID);
        if (claimed != 1) {
            VoucherOrder existing = getById(message.getOrderId());
            if (existing != null && existing.getStatus() != null && existing.getStatus() == ORDER_STATUS_FAILED) {
                restoreFailedAfterCommit(existing);
                log.info("秒杀订单已终止, orderId={}", message.getOrderId());
                return VoucherOrderCreateStatus.TERMINATED;
            }
            deletePendingAfterCommit(message.getOrderId());
            log.info("秒杀订单已存在, orderId={}", message.getOrderId());
            return VoucherOrderCreateStatus.EXISTS;
        }
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", message.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("秒杀订单入库失败，数据库库存不足，回滚 Redis, orderId={}", message.getOrderId());
            update().set("status", ORDER_STATUS_FAILED).eq("id", message.getOrderId()).update();
            restoreFailedAfterCommit(getById(message.getOrderId()));
            return VoucherOrderCreateStatus.STOCK_FAILED;
        }
        scheduleUnpaidCancel(message.getOrderId());
        deletePendingAfterCommit(message.getOrderId());
        return VoucherOrderCreateStatus.CREATED;
    }

    @Override
    public Result payOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        if (orderId == null || hiddenByDemoCutoff(userId, orderId)) {
            return Result.fail("订单不存在");
        }
        long seconds = unpaidTimeout.getSeconds();
        // 期限用数据库的 NOW()，避免 JVM 时区和 JDBC 的 UTC 差 8 小时
        boolean paid = update()
                .set("status", ORDER_STATUS_PAID)
                .setSql("pay_time = NOW()")
                .eq("id", orderId)
                .eq("user_id", userId)
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time > DATE_SUB(NOW(), INTERVAL {0} SECOND)", seconds)
                .update();
        if (paid) {
            return Result.ok();
        }
        VoucherOrder order = getById(orderId);
        if (order == null || !userId.equals(order.getUserId())) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && (order.getStatus() == ORDER_STATUS_FAILED || order.getStatus() == ORDER_STATUS_OPEN)) {
            return Result.fail("订单不存在");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_PAID) {
            return Result.fail("订单已支付");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_CANCELLED) {
            return Result.fail("订单已取消");
        }
        if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_UNPAID && unpaidExpired(orderId)) {
            return Result.fail("订单已超时");
        }
        return Result.fail("订单无法支付");
    }

    @Override
    @Transactional
    public void markOrderCancelled(Long orderId) {
        if (orderId == null) {
            log.error("取消消息没有订单号");
            return;
        }
        boolean cancelled = update()
                .set("status", ORDER_STATUS_CANCELLED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!cancelled) {
            log.info("订单无需取消, orderId={}", orderId);
            return;
        }
        VoucherOrder order = getById(orderId);
        if (order == null) {
            throw new IllegalStateException("订单取消后找不到记录, orderId=" + orderId);
        }
        if (isSeckillVoucher(order.getVoucherId())) {
            boolean stockRestored = seckillVoucherService.update()
                    .setSql("stock = stock + 1")
                    .eq("voucher_id", order.getVoucherId())
                    .update();
            if (!stockRestored) {
                throw new IllegalStateException("秒杀库存回补失败, orderId=" + orderId);
            }
            // 提交前先记下补偿。进程若在回补 Redis 前退出，扫描器还能按订单号重试
            rememberRestore(orderId, order.getUserId(), order.getVoucherId());
            return;
        }
        int restored = voucherMapper.restoreNormalStock(order.getVoucherId());
        if (restored != 1) {
            throw new IllegalStateException("普通券库存回补失败, orderId=" + orderId);
        }
    }

    @Override
    public boolean releaseDemoOrders(Long userId) {
        if (userId == null || hasInFlightSeckill(userId)) {
            return false;
        }
        List<VoucherOrder> orders = query().eq("user_id", userId).list();
        for (VoucherOrder order : orders) {
            if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_OPEN) {
                return false;
            }
        }
        for (VoucherOrder order : orders) {
            if (order.getStatus() != null && order.getStatus() == ORDER_STATUS_UNPAID) {
                self.markOrderCancelled(order.getId());
                self.restoreRedisIfCancelled(order.getId());
            }
        }
        // 已支付的秒杀不退库存。清掉购买资格，新访客才能再买。订单行留下，消息重投仍能认出这一单
        clearSeckillEligibility(userId);
        long cutoff = redisIdWorker.nextId("order");
        int marked = userMapper.update(null, new UpdateWrapper<User>()
                .set("demo_order_after", cutoff)
                .eq("id", userId));
        if (marked != 1) {
            log.error("体验账号没有记下订单分界, userId={}", userId);
            return false;
        }
        return true;
    }

    @Override
    public boolean abandonFailedCreate(Long orderId, Long userId, Long voucherId) {
        if (orderId == null || userId == null || voucherId == null) {
            return true;
        }
        getBaseMapper().insertUnclaimed(orderId, userId, voucherId);
        getBaseMapper().claim(orderId, ORDER_STATUS_FAILED);
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() == ORDER_STATUS_OPEN) {
            return false;
        }
        if (order.getStatus() != ORDER_STATUS_FAILED) {
            stringRedisTemplate.delete(SECKILL_PENDING_KEY + orderId);
            return true;
        }
        Long restored = restoreSeckill(order.getVoucherId(), order.getUserId(), orderId);
        if (restored == null) {
            return false;
        }
        stringRedisTemplate.delete(SECKILL_PENDING_KEY + orderId);
        stringRedisTemplate.opsForValue().set(
                SECKILL_FAILED_KEY + orderId, order.getUserId().toString(), SECKILL_FAILED_MINUTES, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void restoreRedisIfCancelled(Long orderId) {
        if (orderId == null) {
            return;
        }
        String restoreKey = SECKILL_RESTORE_KEY + orderId;
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != ORDER_STATUS_CANCELLED) {
            // 标记可能先于 SQL 提交被扫描到，保留它等待下一轮。
            return;
        }
        if (!isSeckillVoucher(order.getVoucherId())) {
            stringRedisTemplate.delete(restoreKey);
            return;
        }
        Long restored = restoreSeckill(order.getVoucherId(), order.getUserId(), orderId);
        if (restored == null) {
            stringRedisTemplate.opsForHash().put(restoreKey, PENDING_NEXT_FIELD,
                    String.valueOf(System.currentTimeMillis() + SECKILL_PENDING_DELAY_MILLIS));
            return;
        }
        // 1 是这次回补成功，0 是预占已经不是这一单。两种都不用再试
        stringRedisTemplate.delete(restoreKey);
    }

    @Override
    public void markPendingRetrying(Long orderId) {
        putPendingState(orderId, PENDING_STATE_RETRYING);
    }

    @Override
    public void markPendingStopped(Long orderId) {
        putPendingState(orderId, PENDING_STATE_STOPPED);
    }

    @Override
    public void postponePendingRetry(Long orderId) {
        if (orderId == null) {
            return;
        }
        String key = SECKILL_PENDING_KEY + orderId;
        Object state = stringRedisTemplate.opsForHash().get(key, PENDING_STATE_FIELD);
        if (!PENDING_STATE_WAITING.equals(state)) {
            return;
        }
        stringRedisTemplate.opsForHash().put(key, PENDING_NEXT_FIELD,
                String.valueOf(System.currentTimeMillis() + SECKILL_PENDING_DELAY_MILLIS));
    }

    @Override
    public List<Long> findExpiredUnpaidIds(int limit) {
        int size = Math.max(1, Math.min(limit, 50));
        return query()
                .select("id")
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} SECOND)", unpaidTimeout.getSeconds())
                .last("LIMIT " + size)
                .list()
                .stream()
                .map(VoucherOrder::getId)
                .collect(Collectors.toList());
    }

    private void scheduleUnpaidCancel(Long orderId) {
        VoucherOrderCancelMessage cancelMessage = new VoucherOrderCancelMessage();
        cancelMessage.setOrderId(orderId);
        CorrelationData correlationData = new CorrelationData("cancel-" + orderId);
        rabbitTemplate.convertAndSend(RabbitMqConfig.SECKILL_ORDER_DELAY_QUEUE, cancelMessage, correlationData);
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_WAIT_SECONDS, TimeUnit.SECONDS);
            boolean accepted = confirm != null && confirm.isAck() && correlationData.getReturned() == null;
            if (!accepted) {
                throw new IllegalStateException("取消消息被拒绝, orderId=" + orderId + ", confirm=" + confirm);
            }
        } catch (TimeoutException e) {
            log.error("等待取消消息确认超时, orderId={}", orderId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待取消消息确认被中断, orderId=" + orderId, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("取消消息确认失败, orderId=" + orderId, e);
        }
    }

    private boolean pendingBelongsTo(Long orderId, Long userId) {
        String key = SECKILL_PENDING_KEY + orderId;
        DataType type = stringRedisTemplate.type(key);
        if (type == DataType.HASH) {
            Object pendingUser = stringRedisTemplate.opsForHash().get(key, PENDING_USER_FIELD);
            return pendingUser != null && userId.toString().equals(pendingUser.toString());
        }
        if (type == DataType.STRING) {
            String pending = stringRedisTemplate.opsForValue().get(key);
            return pending != null && pending.startsWith(userId + ":");
        }
        return false;
    }

    private void deletePendingAfterCommit(Long orderId) {
        String key = SECKILL_PENDING_KEY + orderId;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stringRedisTemplate.delete(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(key);
            }
        });
    }

    /** SQL 提交后才释放资格；失败时 pending 仍在，扫描器可按数据库终态继续回补。 */
    private void restoreFailedAfterCommit(VoucherOrder order) {
        Runnable restore = () -> {
            try {
                self.abandonFailedCreate(order.getId(), order.getUserId(), order.getVoucherId());
            } catch (RuntimeException e) {
                log.error("失败订单回补未完成，保留 pending 重试, orderId={}", order.getId(), e);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            restore.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                restore.run();
            }
        });
    }

    private void rememberRestore(Long orderId, Long userId, Long voucherId) {
        Map<String, String> fields = new HashMap<>();
        fields.put(PENDING_USER_FIELD, String.valueOf(userId));
        fields.put(PENDING_VOUCHER_FIELD, String.valueOf(voucherId));
        fields.put(PENDING_NEXT_FIELD, String.valueOf(System.currentTimeMillis() + SECKILL_PENDING_DELAY_MILLIS));
        stringRedisTemplate.opsForHash().putAll(SECKILL_RESTORE_KEY + orderId, fields);
    }

    private void putPendingState(Long orderId, String state) {
        if (orderId == null) {
            return;
        }
        String key = SECKILL_PENDING_KEY + orderId;
        DataType type = stringRedisTemplate.type(key);
        if (type == null || type == DataType.NONE) {
            return;
        }
        if (type == DataType.STRING) {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (value == null) {
                return;
            }
            String[] parts = value.split(":", 2);
            if (parts.length != 2) {
                return;
            }
            Map<String, String> fields = new HashMap<>();
            fields.put(PENDING_USER_FIELD, parts[0]);
            fields.put(PENDING_VOUCHER_FIELD, parts[1]);
            fields.put(PENDING_STATE_FIELD, state);
            fields.put(PENDING_NEXT_FIELD, "0");
            stringRedisTemplate.delete(key);
            stringRedisTemplate.opsForHash().putAll(key, fields);
            return;
        }
        if (type == DataType.HASH) {
            stringRedisTemplate.opsForHash().put(key, PENDING_STATE_FIELD, state);
        }
    }

    private Long restoreSeckill(Long voucherId, Long userId, Long orderId) {
        Long restored = stringRedisTemplate.execute(
                RESTORE_SCRIPT,
                Arrays.asList(
                        SECKILL_STOCK_KEY + voucherId,
                        SECKILL_HOLD_KEY + voucherId,
                        SECKILL_PENDING_KEY + orderId
                ),
                userId.toString(),
                orderId.toString()
        );
        log.info("回滚秒杀资格, voucherId={}, userId={}, orderId={}, restored={}", voucherId, userId, orderId, restored);
        return restored;
    }

    private boolean unpaidExpired(Long orderId) {
        Long rows = query()
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} SECOND)", unpaidTimeout.getSeconds())
                .count();
        return rows != null && rows > 0;
    }

    private Set<Long> expiredUnpaidIds(List<VoucherOrder> orders) {
        List<Long> unpaidIds = orders.stream()
                .filter(order -> order.getStatus() != null && order.getStatus() == ORDER_STATUS_UNPAID)
                .map(VoucherOrder::getId)
                .collect(Collectors.toList());
        if (unpaidIds.isEmpty()) {
            return Collections.emptySet();
        }
        return query()
                .select("id")
                .in("id", unpaidIds)
                .eq("status", ORDER_STATUS_UNPAID)
                .apply("create_time <= DATE_SUB(NOW(), INTERVAL {0} SECOND)", unpaidTimeout.getSeconds())
                .list()
                .stream()
                .map(VoucherOrder::getId)
                .collect(Collectors.toSet());
    }

    private String orderState(VoucherOrder order) {
        return orderState(order, null);
    }

    private String orderState(VoucherOrder order, Set<Long> expiredIds) {
        Integer status = order.getStatus();
        if (status != null && status == ORDER_STATUS_FAILED) {
            return "下单失败";
        }
        if (status != null && status == ORDER_STATUS_OPEN) {
            return "处理中";
        }
        if (status != null && status == ORDER_STATUS_PAID) {
            return "已支付";
        }
        if (status != null && status == ORDER_STATUS_CANCELLED) {
            return "已取消";
        }
        boolean expired = expiredIds == null ? unpaidExpired(order.getId()) : expiredIds.contains(order.getId());
        if (status != null && status == ORDER_STATUS_UNPAID && expired) {
            return "已超时";
        }
        if (status != null && status == ORDER_STATUS_UNPAID) {
            return "未支付";
        }
        return "未知";
    }

    private Long demoOrderAfter(Long userId) {
        User user = userId == null ? null : userMapper.selectById(userId);
        return user == null ? null : user.getDemoOrderAfter();
    }

    /** 体验账号交给新访客之后，分界订单号以及更早的订单不再展示，但行还在 */
    private boolean hiddenByDemoCutoff(Long userId, Long orderId) {
        Long after = demoOrderAfter(userId);
        return after != null && orderId != null && orderId <= after;
    }

    private boolean isSeckillVoucher(Long voucherId) {
        Voucher voucher = voucherId == null ? null : voucherService.getById(voucherId);
        return voucher != null && voucher.getType() != null && voucher.getType() == VOUCHER_TYPE_SECKILL;
    }

    private boolean hasInFlightSeckill(Long userId) {
        String user = userId.toString();
        ScanOptions options = ScanOptions.scanOptions().match(SECKILL_PENDING_KEY + "*").count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                DataType type = stringRedisTemplate.type(key);
                if (type == DataType.HASH) {
                    Object pendingUser = stringRedisTemplate.opsForHash().get(key, PENDING_USER_FIELD);
                    if (pendingUser != null && user.equals(pendingUser.toString())) {
                        return true;
                    }
                } else if (type == DataType.STRING) {
                    String pending = stringRedisTemplate.opsForValue().get(key);
                    if (pending != null && pending.startsWith(user + ":")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void clearSeckillEligibility(Long userId) {
        String user = userId.toString();
        scanKeys(SECKILL_HOLD_KEY + "*", key -> stringRedisTemplate.opsForHash().delete(key, user));
        scanKeys(SECKILL_ORDER_KEY + "*", key -> stringRedisTemplate.opsForSet().remove(key, user));
    }

    private void scanKeys(String pattern, java.util.function.Consumer<String> action) {
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(100).build();
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                action.accept(cursor.next());
            }
        }
    }

    private String seckillFailMessage(int code) {
        if (code == 1) {
            return "库存不足";
        }
        if (code == 2) {
            return "不能重复下单";
        }
        if (code == 3) {
            return "秒杀尚未开始";
        }
        if (code == 4) {
            return "秒杀已经结束";
        }
        if (code == 5) {
            return "秒杀券不存在";
        }
        return "秒杀失败";
    }
}
