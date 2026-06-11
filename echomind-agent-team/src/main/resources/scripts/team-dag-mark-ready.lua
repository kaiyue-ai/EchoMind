-- mark_step_ready.lua
-- Mark a specific step as READY and add it to the pending_ready list.
-- Used during DAG initialization and when a RETRYING step is re-queued.
--
-- KEYS[1] = step key (echomind:team:run:{runId}:step:{stepId})
-- KEYS[2] = run dag key (echomind:team:run:{runId}:dag)
-- ARGV[1] = stepId
--
-- Returns: 1 if marked ready, 0 if deps not met

local step_key = KEYS[1]
local dag_key = KEYS[2]
local step_id = ARGV[1]

-- Check if step already terminal
local status = redis.call('HGET', step_key, 'status')
if status == 'COMPLETED' or status == 'FAILED' or status == 'SUPERSEDED' then
    return 0
end

-- Check all dependencies are COMPLETED
local deps_json = redis.call('HGET', step_key, 'deps_json')
if deps_json ~= false then
    local deps = cjson.decode(deps_json)
    for _, dep_id in ipairs(deps) do
        -- Derive dependent step key: replace the step-id suffix
        local dep_step_key = string.gsub(step_key, ':[^:]+$', ':' .. dep_id)
        local dep_status = redis.call('HGET', dep_step_key, 'status')
        if dep_status ~= 'COMPLETED' then
            return 0  -- deps not met
        end
    end
end

-- Mark READY
redis.call('HSET', step_key, 'status', 'READY')

-- Add to pending_ready
local pending_json = redis.call('HGET', dag_key, 'pending_ready')
local pending = {}
if pending_json ~= false then
    pending = cjson.decode(pending_json)
end
table.insert(pending, step_id)
redis.call('HSET', dag_key, 'pending_ready', cjson.encode(pending))

return 1
