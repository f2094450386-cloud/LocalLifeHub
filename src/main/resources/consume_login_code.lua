---
--- Atomically verify and consume a login verification code.
---
--- KEYS[1] = login code key
--- ARGV[1] = submitted code
--- return 1: matched and deleted
--- return 0: missing or mismatched
---

local cachedCode = redis.call('get', KEYS[1])
if cachedCode == false or cachedCode ~= ARGV[1] then
    return 0
end

redis.call('del', KEYS[1])
return 1
