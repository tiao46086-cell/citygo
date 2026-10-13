-- 把某一次预占还回去。只在 Hash 里记下的订单号就是这一单时才加库存。
-- 旧的失败消息重投时，如果这个人已经又抢了一单，订单号对不上，不能动新的预占。
-- 同一次预占重复调用，第二次 Hash 里已经没有这个订单号，返回 0。
-- KEYS[1] seckill:stock:{券id}
-- KEYS[2] seckill:hold:{券id}
-- KEYS[3] seckill:pending:{订单号}
-- ARGV[1] 用户 id
-- ARGV[2] 订单号
-- 返回：1 已回补，0 当前预占不是这一单

local stockKey = KEYS[1]
local holdKey = KEYS[2]
local pendingKey = KEYS[3]
local userId = ARGV[1]
local orderId = ARGV[2]

if (redis.call('hget', holdKey, userId) ~= orderId) then
    return 0
end
-- Lua 报错不会撤销已执行命令。先加库存，成功后再删除资格。
-- 库存 key 丢失时不能凭一次回补重建整个活动库存。
if redis.call('exists', stockKey) == 0 then
    return redis.error_reply('seckill stock missing; restore retained')
end
redis.call('incrby', stockKey, 1)
redis.call('hdel', holdKey, userId)
redis.call('del', pendingKey)
return 1
