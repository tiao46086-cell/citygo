-- 按用户限流。同一秒内的 INCR 和 EXPIRE 放在一个脚本里，避免只加了次数却没设过期时间。
-- KEYS[1] seckill:limit:{用户id}
-- ARGV[1] 窗口内最多允许的次数
-- ARGV[2] 窗口长度，单位秒
-- 返回：1 放行，0 超过次数

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSeconds = tonumber(ARGV[2])
local count = redis.call('incr', key)
if (count == 1 or redis.call('ttl', key) == -1) then
    redis.call('expire', key, windowSeconds)
end
if (count > limit) then
    return 0
end
return 1
