-- set_control_flag.lua
-- Set or clear a control flag for a run.
--
-- KEYS[1] = control key (echomind:team:run:{runId}:control)
-- ARGV[1] = flag name (e.g. "stopping", "paused")
-- ARGV[2] = flag value (non-empty to set, empty string to clear)

local control_key = KEYS[1]
local flag = ARGV[1]
local value = ARGV[2]

if value == '' then
    redis.call('HDEL', control_key, flag)
else
    redis.call('HSET', control_key, flag, value)
end

return 1
