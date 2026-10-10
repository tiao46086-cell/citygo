-- 秒杀资格判断。Redis 单线程执行整段脚本，中间不会插入别的命令。
-- KEYS[1] 库存，字符串，seckill:stock:{券id}
-- KEYS[2] 旧的已购用户 Set，seckill:order:{券id}。只用来拦住这次改动之前已经抢到的人
-- KEYS[3] 开始和结束时间，Hash，seckill:voucher:{券id}
-- KEYS[4] 当前预占，Hash，seckill:hold:{券id}，field 是用户 id，value 是订单号
-- KEYS[5] 待确认，字符串，seckill:pending:{订单号}，值是 用户id:券id
-- ARGV[1] 用户 id
-- ARGV[2] 订单号。调用前已经生成，这样预占能对上这一单
-- ARGV[3] 券 id
-- 返回：0 通过，1 库存不足，2 不能重复下单，3 尚未开始，4 已经结束，5 秒杀券不存在
-- 取消成功后预占会被删掉，同一个人可以再抢。回补时必须带原来的订单号。

local stockKey = KEYS[1]
local legacyKey = KEYS[2]
local voucherKey = KEYS[3]
local holdKey = KEYS[4]
local pendingKey = KEYS[5]
local userId = ARGV[1]
local orderId = ARGV[2]
local voucherId = ARGV[3]

if (redis.call('exists', stockKey) == 0) then
    return 5
end

local beginTime = redis.call('hget', voucherKey, 'beginTime')
local endTime = redis.call('hget', voucherKey, 'endTime')
if (beginTime == false or endTime == false) then
    return 5
end

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
if (redis.call('sismember', legacyKey, userId) == 1 or redis.call('hexists', holdKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('hset', holdKey, userId, orderId)
redis.call('set', pendingKey, userId .. ':' .. voucherId)
return 0
