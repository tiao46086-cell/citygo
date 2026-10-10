-- 入库失败或消息被拒绝时，把 Lua 预扣的资格还回去。
-- 只在用户还在 Set 里时加库存，重复调用不会把库存越加越多。
-- KEYS[1] seckill:stock:{券id}
-- KEYS[2] seckill:order:{券id}
-- ARGV[1] 用户 id
-- 返回：1 已回滚，0 用户不在 Set 里，不用回滚

local stockKey = KEYS[1]
local orderKey = KEYS[2]
local userId = ARGV[1]

if (redis.call('sismember', orderKey, userId) == 0) then
    return 0
end
redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
return 1
