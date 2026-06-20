---
--- Atomically release pre-reserved seckill stock and per-user qualification.
---
--- return 0: already released (idempotent skip)
--- return 1: newly released
--- return 2: qualification did not exist, stock was not changed
---
--- ARGV[1] = voucherId
--- ARGV[2] = userId
--- ARGV[3] = orderId
--- ARGV[4] = release marker ttl seconds
---

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local releaseTtlSeconds = tonumber(ARGV[4])

local releaseKey = 'seckill:release:' .. orderId
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

if redis.call('exists', releaseKey) == 1 then
    return 0
end

local removed = redis.call('srem', orderKey, userId)
if removed == 0 then
    redis.call('set', releaseKey, '1', 'EX', releaseTtlSeconds)
    return 2
end

redis.call('incrby', stockKey, 1)
redis.call('set', releaseKey, '1', 'EX', releaseTtlSeconds)
return 1
