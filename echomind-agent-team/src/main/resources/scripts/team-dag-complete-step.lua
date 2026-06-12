-- complete_step.lua
-- Atomically complete a step and cascade-check dependents for readiness.
--
-- KEYS[1]           = run dag key (echomind:team:run:{runId}:dag)
-- KEYS[2..N]         = dependent step keys (echomind:team:run:{runId}:step:{depId})
-- ARGV[1]            = completed stepId
-- ARGV[2]            = step output (truncated)
-- ARGV[3]            = completed step key (echomind:team:run:{runId}:step:{stepId})
--
-- Returns: { newlyReadyStepId1, newlyReadyStepId2, ... }

local dag_key = KEYS[1]
local completed_step_key = ARGV[3]
local completed_step_id = ARGV[1]
local output = ARGV[2]

-- 1. Mark current step COMPLETED. StepCompleted events may be retried, so only the
-- first completion is allowed to mutate run counters.
local previous_status = redis.call('HGET', completed_step_key, 'status')
local first_completion = previous_status ~= 'COMPLETED'
redis.call('HSET', completed_step_key, 'status', 'COMPLETED', 'output_json', output)

-- 2. Update run-level counters
if first_completion then
    local running = tonumber(redis.call('HGET', dag_key, 'running_count') or '0')
    local completed = tonumber(redis.call('HGET', dag_key, 'completed_count') or '0')
    redis.call('HSET', dag_key, 'running_count', math.max(0, running - 1), 'completed_count', completed + 1)
end

-- 3. Check each dependent step
local newly_ready = {}
for i = 2, #KEYS do
    local dep_key = KEYS[i]
    local dep_status = redis.call('HGET', dep_key, 'status')
    if dep_status == 'BLOCKED' or dep_status == 'PENDING' then
        -- Check if ALL deps of this dependent are COMPLETED
        local deps_json = redis.call('HGET', dep_key, 'deps_json')
        if deps_json ~= false then
            local ok = true
            -- deps_json is a JSON array like ["step-a","step-b"]
            -- We iterate by checking each dep's status
            local deps = cjson.decode(deps_json)
            for _, dep_id in ipairs(deps) do
                local dep_step_key = string.gsub(dep_key, ':[^:]+$', ':' .. dep_id)
                local s = redis.call('HGET', dep_step_key, 'status')
                if s ~= 'COMPLETED' then
                    ok = false
                    break
                end
            end
            if ok then
                redis.call('HSET', dep_key, 'status', 'READY')
                local dep_id = string.gsub(dep_key, '.*:', '')
                table.insert(newly_ready, dep_id)
            end
        end
    end
end

-- 4. Append newly ready steps to pending_ready list
if #newly_ready > 0 then
    local pending_json = redis.call('HGET', dag_key, 'pending_ready')
    local pending = {}
    if pending_json ~= false then
        pending = cjson.decode(pending_json)
    end
    local present = {}
    for _, sid in ipairs(pending) do
        present[sid] = true
    end
    for _, sid in ipairs(newly_ready) do
        if not present[sid] then
            table.insert(pending, sid)
            present[sid] = true
        end
    end
    redis.call('HSET', dag_key, 'pending_ready', cjson.encode(pending))
end

return newly_ready
