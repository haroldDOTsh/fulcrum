local activeHashKey = KEYS[1]
local newSlotSetKey = KEYS[2]
local playerId = ARGV[1]
local slotId = ARGV[2]
local slotPlayersPrefix = ARGV[3]

local previousSlot = redis.call('HGET', activeHashKey, playerId)

if slotId ~= nil and slotId ~= '' then
    redis.call('HSET', activeHashKey, playerId, slotId)
    redis.call('SADD', newSlotSetKey, playerId)
else
    redis.call('HDEL', activeHashKey, playerId)
end

if previousSlot ~= false and previousSlot ~= nil and previousSlot ~= '' and previousSlot ~= slotId then
    redis.call('SREM', slotPlayersPrefix .. previousSlot, playerId)
end

if slotId == nil or slotId == '' then
    if newSlotSetKey ~= nil and newSlotSetKey ~= '' then
        redis.call('SREM', newSlotSetKey, playerId)
    end
end

if previousSlot == false or previousSlot == nil then
    return ''
else
    return previousSlot
end
