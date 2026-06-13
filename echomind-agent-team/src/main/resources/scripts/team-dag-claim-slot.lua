-- claim_step_slot.lua
-- Atomically claim an execution slot from the pending_ready list
-- and mark the claimed step as RUNNING, eliminating the status/running_count gap.
--
-- KEYS[1] = run dag key (echomind:team:run:{runId}:dag)
--
-- Returns: stepId (if slot claimed) or nil (no slot available)

local dag_key = KEYS[1]

local max_concurrent = tonumber(redis.call('HGET', dag_key, 'max_concurrent') or '7')
local running_count = tonumber(redis.call('HGET', dag_key, 'running_count') or '0')
local pending_json = redis.call('HGET', dag_key, 'pending_ready')

if pending_json == false then
    return nil
end

local pending = cjson.decode(pending_json)
if #pending == 0 then
    return nil
end

if running_count >= max_concurrent then
    return nil
end

-- Pop first ready step
local claimed = table.remove(pending, 1)

-- Derive step key from dag key: echomind:team:run:{runId}:dag → echomind:team:run:{runId}:step:{claimed}
local step_key = string.gsub(dag_key, ":dag$", ":step:" .. claimed)

-- Atomically update: step status = RUNNING, running_count +1, pending_ready
redis.call('HSET', step_key, 'status', 'RUNNING')
redis.call('HSET', dag_key, 'running_count', running_count + 1)
if #pending == 0 then
    redis.call('HDEL', dag_key, 'pending_ready')
else
    redis.call('HSET', dag_key, 'pending_ready', cjson.encode(pending))
end

return claimed
