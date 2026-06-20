---
--- Voucher seckill qualification script.
---
--- return 0: success
--- return 1: stock is missing or not enough
--- return 2: duplicate order by same user
--- return 3: activity metadata is missing
--- return 4: activity has not started
--- return 5: activity has ended
---

local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local beginTimeKey = 'seckill:begin:' .. voucherId
local endTimeKey = 'seckill:end:' .. voucherId

local beginTime = tonumber(redis.call('get', beginTimeKey))
local endTime = tonumber(redis.call('get', endTimeKey))
if beginTime == nil or endTime == nil then
    return 3
end

local redisTime = redis.call('TIME')
local now = tonumber(redisTime[1]) * 1000 + math.floor(tonumber(redisTime[2]) / 1000)
if now < beginTime then
    return 4
end
if now > endTime then
    return 5
end

local stock = tonumber(redis.call('get', stockKey))
if (stock == nil or stock <= 0) then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
