-- 只允许完整空白状态初始化，避免覆盖运行中的库存、资格或半套数据。
for i = 1, #KEYS do
    if redis.call('EXISTS', KEYS[i]) == 1 then
        return 0
    end
end
redis.call('SET', KEYS[1], ARGV[1])
redis.call('HSET', KEYS[2], 'beginTime', ARGV[2])
redis.call('HSET', KEYS[2], 'endTime', ARGV[3])
return 1
