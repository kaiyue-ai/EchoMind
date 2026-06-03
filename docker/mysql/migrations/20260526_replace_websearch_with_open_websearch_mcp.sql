-- Retire the legacy WebSearch Skill after replacing it with open-websearch MCP.
UPDATE echomind_agents agent
SET skill_ids_json = (
    SELECT CONCAT(
        '[',
        COALESCE(GROUP_CONCAT(JSON_QUOTE(remaining.skill_id) ORDER BY remaining.ord SEPARATOR ','), ''),
        ']'
    )
    FROM JSON_TABLE(
        agent.skill_ids_json,
        '$[*]' COLUMNS (
            ord FOR ORDINALITY,
            skill_id VARCHAR(255) PATH '$'
        )
    ) AS remaining
    WHERE remaining.skill_id <> 'web-search'
      AND remaining.skill_id NOT LIKE 'web-search@%'
)
WHERE JSON_VALID(skill_ids_json)
  AND (
      JSON_SEARCH(skill_ids_json, 'one', 'web-search') IS NOT NULL
      OR JSON_SEARCH(skill_ids_json, 'one', 'web-search@%') IS NOT NULL
  );

UPDATE echomind_agents
SET system_prompt = REPLACE(system_prompt, 'skill-websearch', 'open-websearch MCP')
WHERE system_prompt LIKE '%skill-websearch%';

UPDATE echomind_agents
SET system_prompt = REPLACE(system_prompt, 'web-search', 'open-websearch MCP')
WHERE system_prompt LIKE '%web-search%';

UPDATE echomind_agents
SET system_prompt = REPLACE(system_prompt, 'SearXNG', 'open-websearch MCP')
WHERE system_prompt LIKE '%SearXNG%';
