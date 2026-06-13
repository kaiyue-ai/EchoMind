-- release_slot_for_retry.lua
-- Atomically release an execution slot for step retry.
-- Avoids read-modify-write race on running_count when multiple steps retry concurrently.
--
-- KEYS[1] = run dag key (echomind:team:run:{runId}:dag)
-- KEYS[2] = step key (echomind:team:run:{runId}:step:{stepId})
-- ARGV[1] = stepId
-- ARGV[2] = retryCount
--
-- Returns: new running_count

local dag_key = KEYS[1]
local step_key = KEYS[2]
local step_id = ARGV[1]
local retry_count = ARGV[2]

-- Atomically decrement running_count (floor at 0)
local running = tonumber(redis.call('HGET', dag_key, 'running_count') or '0')
local new_running = math.max(0, running - 1)
redis.call('HSET', dag_key, 'running_count', new_running)

-- Set step status to RETRYING and update retry_count
redis.call('HSET', step_key, 'status', 'RETRYING', 'retry_count', retry_count)

return new_running
