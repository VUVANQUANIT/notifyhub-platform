local count = tonumber(redis.call('GET', KEYS[1]) or '0')
local ttl = redis.call('PTTL', KEYS[1])
if count >= tonumber(ARGV[1]) then
    if ttl < 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]); ttl = tonumber(ARGV[2]) end
    return ttl
end
if count == 0 then
    redis.call('SET', KEYS[1], '1', 'PX', ARGV[2])
else
    redis.call('INCR', KEYS[1])
    if ttl < 0 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
end
return 0
