---
--- Atomically release pre-reserved seckill stock and per-user qualification.
---
--- return 0: already released (idempotent skip)
--- return 1: newly released
---
--- ARGV[1] = voucherId
--- ARGV[2] = userId
--- ARGV[3] = orderId
---

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local releaseKey = 'seckill:release:' .. orderId
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

if redis.call('exists', releaseKey) == 1 then
    return 0
end

redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
redis.call('set', releaseKey, '1', 'EX', 3600)
return 1
