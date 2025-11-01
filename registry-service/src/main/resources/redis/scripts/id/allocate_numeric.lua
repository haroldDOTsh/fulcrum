local recycleKey = KEYS[1]
local counterKey = KEYS[2]
local prefix = ARGV[1]

local recycled = redis.call('ZRANGE', recycleKey, 0, 0)
if recycled[1] ~= nil then
    redis.call('ZREM', recycleKey, recycled[1])
    return recycled[1]
end

local nextValue = redis.call('INCR', counterKey)
return prefix .. nextValue
