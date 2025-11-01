local capacityKey = KEYS[1]
local familySetKey = KEYS[2]
local familiesKey = KEYS[3]
local family = ARGV[1]
local serverId = ARGV[2]

local current = redis.call('HGET', capacityKey, family)
if not current then
    return -1
end

local currentNum = tonumber(current)
if currentNum == nil or currentNum <= 0 then
    return -1
end

local updated = redis.call('HINCRBY', capacityKey, family, -1)
if updated < 0 then
    redis.call('HINCRBY', capacityKey, family, 1)
    return -1
end

if updated == 0 then
    redis.call('SREM', familySetKey, serverId)
else
    redis.call('SADD', familySetKey, serverId)
end

redis.call('SADD', familiesKey, family)

return updated
