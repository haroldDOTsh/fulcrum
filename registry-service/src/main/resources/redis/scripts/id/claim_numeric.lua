local recycleKey = KEYS[1]
local counterKey = KEYS[2]
local idValue = ARGV[1]
local number = tonumber(ARGV[2])

redis.call('ZREM', recycleKey, idValue)

local currentValue = redis.call('GET', counterKey)
local current = 0
if currentValue then
    current = tonumber(currentValue)
end

if current < number then
    redis.call('SET', counterKey, number)
end

return 'OK'
