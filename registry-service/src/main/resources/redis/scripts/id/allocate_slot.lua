local recycleKey = KEYS[1]
local counterKey = KEYS[2]
local maxSuffix = tonumber(ARGV[1])

local recycled = redis.call('ZRANGE', recycleKey, 0, 0)
if recycled[1] ~= nil then
    redis.call('ZREM', recycleKey, recycled[1])
    return recycled[1]
end

local nextIndex = redis.call('INCR', counterKey)
if nextIndex > maxSuffix then
    return redis.error_reply('SLOT_LIMIT_REACHED')
end

-- 64 + 1 = 65 => 'A'
local letter = string.char(64 + nextIndex)
return letter
