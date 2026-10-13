-- long 超过 Lua double 的精度范围。用十进制字符串比较，INCR 后 GET 精确值。
local function greater(a, b)
    return #a > #b or (#a == #b and a > b)
end
local last = redis.call('GET', KEYS[1]) or '0'
if greater(ARGV[2], last) then
    last = ARGV[2]
end
if greater(ARGV[1], last) then
    redis.call('SET', KEYS[1], ARGV[1])
else
    redis.call('SET', KEYS[1], last)
    redis.call('INCR', KEYS[1])
end
return redis.call('GET', KEYS[1])
