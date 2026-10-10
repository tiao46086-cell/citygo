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
    public static final String SECKILL_VOUCHER_KEY = "seckill:voucher:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
