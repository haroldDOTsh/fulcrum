local capacityKey = KEYS[1]
local familySetKey = KEYS[2]
local familiesKey = KEYS[3]
local family = ARGV[1]
local serverId = ARGV[2]

local updated = redis.call('HINCRBY', capacityKey, family, 1)

if updated and updated > 0 then
    redis.call('SADD', familySetKey, serverId)
end

redis.call('SADD', familiesKey, family)

return updated or 0
