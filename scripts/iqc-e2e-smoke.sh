#!/usr/bin/env bash
set -euo pipefail

# 写入型发布门禁：只允许在专用验收环境显式开启，会创建 Agent、规则、会话、任务和结果。
if [[ "${IQC_E2E_WRITE_ENABLED:-false}" != "true" ]]; then
  echo "拒绝执行：请在专用验收环境设置 IQC_E2E_WRITE_ENABLED=true" >&2
  exit 2
fi

base_url="${IQC_BASE_URL:-http://localhost:8040}"
access_token="${IQC_ACCESS_TOKEN:-}"
cookie_jar="${IQC_COOKIE_JAR:-}"
poll_attempts="${IQC_E2E_POLL_ATTEMPTS:-30}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/iqc-e2e.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT
run_id="$(date +%s)"

if [[ -z "$access_token" && -z "$cookie_jar" ]]; then
  echo "必须提供 IQC_ACCESS_TOKEN 或 IQC_COOKIE_JAR" >&2
  exit 2
fi

auth_args=()
[[ -n "$access_token" ]] && auth_args+=(--header "Authorization: Bearer $access_token")
[[ -n "$cookie_jar" ]] && auth_args+=(--cookie "$cookie_jar")

request() {
  local method="$1" path="$2" body="${3:-}" output="$tmp_dir/response.json" status
  local -a args=(--silent --show-error --output "$output" --write-out "%{http_code}" --request "$method" --header "Accept: application/json")
  args+=("${auth_args[@]}")
  if [[ -n "$body" ]]; then args+=(--header "Content-Type: application/json" --data "$body"); fi
  status="$(curl "${args[@]}" "${base_url%/}$path")"
  if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
    echo "FAIL $method $path (HTTP $status)" >&2
    jq -c . "$output" >&2 || true
    return 1
  fi
  jq -c 'if type == "object" and has("data") then .data else . end' "$output"
}

agent="$(request POST /api/iqc/config/agents "{\"name\":\"E2E Agent $run_id\",\"code\":\"E2E_AGENT_$run_id\",\"description\":\"quality gate\",\"configJson\":\"{\\\"mode\\\":\\\"RULE\\\"}\"}")"
agent_id="$(jq -r .id <<<"$agent")"
request POST "/api/iqc/config/agents/$agent_id/submit" >/dev/null
request POST "/api/iqc/config/agents/$agent_id/approve" >/dev/null

rule="$(request POST /api/iqc/config/rules "{\"name\":\"E2E 优惠检测 $run_id\",\"code\":\"E2E_RULE_$run_id\",\"category\":\"SALES_COMPLIANCE\",\"ruleType\":\"KEYWORD\",\"targetRole\":\"agent\",\"expression\":\"优惠\",\"deduction\":20,\"riskLevel\":\"HIGH\",\"veto\":false}")"
rule_id="$(jq -r .id <<<"$rule")"
request POST "/api/iqc/config/rules/$rule_id/submit" >/dev/null
request POST "/api/iqc/config/rules/$rule_id/approve" >/dev/null

conversation_file="$tmp_dir/iqc-e2e-$run_id.txt"
printf '0(agent):[00:00:01]今天有优惠\n1(user):[00:00:03]我再考虑一下\n' >"$conversation_file"
upload_output="$tmp_dir/upload.json"
upload_status="$(curl --silent --show-error --output "$upload_output" --write-out "%{http_code}" "${auth_args[@]}" --form "file=@$conversation_file;type=text/plain" "${base_url%/}/api/iqc/conversations/import")"
[[ "$upload_status" -ge 200 && "$upload_status" -lt 300 ]] || { echo "FAIL conversation import (HTTP $upload_status)" >&2; exit 1; }
conversation_id="$(jq -r 'if has("data") then .data.conversationId else .conversationId end' "$upload_output")"

task="$(request POST /api/iqc/tasks "{\"name\":\"E2E Task $run_id\",\"conversationId\":\"$conversation_id\",\"agentId\":\"$agent_id\",\"ruleIds\":[\"$rule_id\"]}")"
task_id="$(jq -r .id <<<"$task")"
request POST "/api/iqc/tasks/$task_id/run" >/dev/null

for ((attempt = 1; attempt <= poll_attempts; attempt++)); do
  task="$(request GET "/api/iqc/tasks/$task_id")"
  status="$(jq -r .status <<<"$task")"
  case "$status" in
    SUCCEEDED) break ;;
    FAILED|PARTIAL_FAILED|CANCELLED) echo "FAIL task $task_id reached $status" >&2; exit 1 ;;
  esac
  sleep 1
done
[[ "${status:-}" == "SUCCEEDED" ]] || { echo "FAIL task $task_id did not finish" >&2; exit 1; }

results="$(request GET "/api/iqc/results?taskId=$task_id&size=20")"
jq -e '.total >= 2 and ([.records[].resultStatus] | index("HIT") != null)' <<<"$results" >/dev/null
effect="$(request GET "/api/iqc/config/agents/$agent_id/versions/1/effect")"
jq -e '.taskCount == 1 and .resultCount >= 2' <<<"$effect" >/dev/null

# 审计异步投递到 base-sysadmin，允许短暂传播延迟但不允许静默缺失。
audit_found=false
for ((attempt = 1; attempt <= 10; attempt++)); do
  audit_page="$(request POST /api/sysadmin/audit/log/conditions '{"current":1,"size":20,"module":"IQC_TASK"}')"
  if jq -e --arg task_id "$task_id" '[.records[] | select((.requestUrl // "") | contains($task_id))] | length > 0' <<<"$audit_page" >/dev/null; then
    audit_found=true
    break
  fi
  sleep 1
done
[[ "$audit_found" == "true" ]] || { echo "FAIL IQC_TASK 审计事件未进入 base-sysadmin" >&2; exit 1; }

echo "IQC write E2E passed: agent=$agent_id rule=$rule_id conversation=$conversation_id task=$task_id"
