-- 秒杀资格判断。Redis 单线程执行整段脚本，中间不会插入别的命令。
-- KEYS[1] 库存，字符串，seckill:stock:{券id}
-- KEYS[2] 已抢到的用户，Set，seckill:order:{券id}
-- KEYS[3] 开始和结束时间，Hash，seckill:voucher:{券id}，字段 beginTime / endTime，单位是秒
-- ARGV[1] 用户 id
-- KEYS[5] 待确认，Hash，seckill:pending:{订单号}
--   userId、voucherId、state=waiting、nextRetryAt（毫秒，到点才允许补发）
-- ARGV[1] 用户 id
-- ARGV[2] 订单号。调用前已经生成，这样预占能对上这一单
-- ARGV[3] 券 id
-- ARGV[4] 下次允许补发的时间，毫秒。缺省当作 0
local voucherKey = KEYS[3]
local userId = ARGV[1]

if (redis.call('exists', stockKey) == 0) then
    return 5
end

local beginTime = redis.call('hget', voucherKey, 'beginTime')
local endTime = redis.call('hget', voucherKey, 'endTime')
if (beginTime == false or endTime == false) then
    return 5
end

-- time 返回 {当前秒, 微秒}，取第 1 个和券上的秒数比较
local nowSecond = tonumber(redis.call('time')[1])
if (nowSecond < tonumber(beginTime)) then
    return 3
end
if (nowSecond > tonumber(endTime)) then
    return 4
end

if (tonumber(redis.call('get', stockKey)) <= 0) then
    return 1
end
if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
