local occupancyKey = KEYS[1]
local slotId = ARGV[1]
local delta = tonumber(ARGV[2])

if delta == nil then
    return redis.error_reply("delta must be numeric")
end

local newValue = redis.call('HINCRBY', occupancyKey, slotId, delta)

if newValue <= 0 then
    redis.call('HDEL', occupancyKey, slotId)
    return 0
end

return newValue
