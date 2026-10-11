package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop:type:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";

    public static final String ICR_ID_KEY = "icr:";

    public static final String SECKILL_LIMIT_KEY = "seckill:limit:";
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 改成 Hash 之前的已购用户 Set。新的预占写在 SECKILL_HOLD_KEY */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_HOLD_KEY = "seckill:hold:";
    public static final String SECKILL_PENDING_KEY = "seckill:pending:";
    /** 数据库已取消、Redis 还没回补时留着，扫描器按订单号重试 */
    public static final String SECKILL_RESTORE_KEY = "seckill:restore:";
    public static final String SECKILL_VOUCHER_KEY = "seckill:voucher:";
    /** 待确认预占是 Hash：userId、voucherId、state、nextRetryAt。查询只读，不改 nextRetryAt */
    public static final String PENDING_USER_FIELD = "userId";
    public static final String PENDING_VOUCHER_FIELD = "voucherId";
    public static final String PENDING_STATE_FIELD = "state";
    public static final String PENDING_NEXT_FIELD = "nextRetryAt";
    public static final String PENDING_STATE_WAITING = "waiting";
    public static final String PENDING_STATE_RETRYING = "retrying";
    public static final String PENDING_STATE_STOPPED = "stopped";
    /** 刚写入或刚补发后，这段时间里扫描器不再发同一单 */
    public static final long SECKILL_PENDING_DELAY_MILLIS = 10_000L;
    /** 当前用户上传过的相对路径，删除时核对 */
    public static final String UPLOAD_BLOG_KEY = "upload:blog:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
