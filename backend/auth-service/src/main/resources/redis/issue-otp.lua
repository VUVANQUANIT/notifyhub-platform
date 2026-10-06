if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
redis.call('HSET', KEYS[1], 'digest', ARGV[1], 'attempts', '0')
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
