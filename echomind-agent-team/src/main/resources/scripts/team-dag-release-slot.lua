-- release_step_slot.lua
-- Release an execution slot, marking the step as FAILED.
--
-- KEYS[1] = run dag key (echomind:team:run:{runId}:dag)
-- KEYS[2] = step key (echomind:team:run:{runId}:step:{stepId})
-- ARGV[1] = stepId

local dag_key = KEYS[1]
local step_key = KEYS[2]
local step_id = ARGV[1]

-- Mark step as FAILED
redis.call('HSET', step_key, 'status', 'FAILED')

-- Decrement running_count
local running = tonumber(redis.call('HGET', dag_key, 'running_count') or '0')
redis.call('HSET', dag_key, 'running_count', math.max(0, running - 1))

-- Increment completed_count (failed counts as completed for DAG progress)
local completed = tonumber(redis.call('HGET', dag_key, 'completed_count') or '0')
redis.call('HSET', dag_key, 'completed_count', completed + 1)

return 1
