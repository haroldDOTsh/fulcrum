local slotPlayersKey = KEYS[1]
local activeHashKey = KEYS[2]

local players = redis.call('SMEMBERS', slotPlayersKey)

if #players == 0 then
    redis.call('DEL', slotPlayersKey)
    return {}
end

for _, playerId in ipairs(players) do
    redis.call('HDEL', activeHashKey, playerId)
end

redis.call('DEL', slotPlayersKey)

return players
