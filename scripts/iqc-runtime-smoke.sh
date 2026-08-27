#!/usr/bin/env bash
set -euo pipefail

# 只读运行探针。默认不创建会话、任务或结果；写操作必须另行设计并显式授权。
base_url="${IQC_BASE_URL:-http://localhost:8040}"
cookie_jar="${IQC_COOKIE_JAR:-}"
access_token="${IQC_ACCESS_TOKEN:-}"
max_response_time_ms="${IQC_MAX_RESPONSE_TIME_MS:-2000}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/iqc-smoke.XXXXXX")"
trap 'rm -rf "$tmp_dir"' EXIT

request() {
  local path="$1"
  local output="$tmp_dir/response.json"
  local status
  local -a args=(--silent --show-error --output "$output" --header "Accept: application/json")
  if [[ -n "$cookie_jar" ]]; then args+=(--cookie "$cookie_jar"); fi
  if [[ -n "$access_token" ]]; then args+=(--header "Authorization: Bearer $access_token"); fi
  local result elapsed_seconds elapsed_ms
  result="$(curl "${args[@]}" --write-out "%{http_code} %{time_total}" "${base_url%/}$path")"
  status="${result%% *}"
  elapsed_seconds="${result#* }"
  elapsed_ms="$(awk -v seconds="$elapsed_seconds" 'BEGIN { printf "%d", seconds * 1000 }')"
  if [[ "$status" != "200" ]]; then
    echo "FAIL $path (HTTP $status)" >&2
    if [[ "$status" == "401" || "$status" == "302" ]]; then
      echo "需要通过 IQC_GATEWAY 完成 OAuth2 登录，并提供 IQC_COOKIE_JAR 或 IQC_ACCESS_TOKEN。" >&2
    fi
    return 1
  fi
  if ! jq -e 'if type == "object" and has("code") then (.code == 0 or .code == 200) and has("data") else true end' "$output" >/dev/null; then
    echo "FAIL $path (统一响应协议或错误码异常)" >&2
    jq -c . "$output" >&2 || true
    return 1
  fi
  if (( elapsed_ms > max_response_time_ms )); then
    echo "FAIL $path (响应 ${elapsed_ms}ms，基线 ${max_response_time_ms}ms)" >&2
    return 1
  fi
  echo "PASS $path (${elapsed_ms}ms)"
}

for path in \
  "/api/iqc/bootstrap" \
  "/api/iqc/dashboard" \
  "/api/iqc/conversations" \
  "/api/iqc/tasks" \
  "/api/iqc/results" \
  "/api/iqc/config/agents" \
  "/api/iqc/config/rules" \
  "/api/iqc/templates" \
  "/api/iqc/settings" \
  "/api/iqc/dictionaries?codes=iqc_rule_type,iqc_risk_level,iqc_target_role,iqc_result_status,iqc_task_status" \
  "/actuator/opensabreGovernanceRegistration"; do
  request "$path"
done

# 发布环境中三类启动注册必须全部成功；FAILED/重试中都不能通过门禁。
jq -e '
  .["error-catalog"].state == "SUCCEEDED" and
  .dictionary.state == "SUCCEEDED" and
  .["resource-permissions"].state == "SUCCEEDED"
' "$tmp_dir/response.json" >/dev/null || {
  echo "FAIL OpenSabre 治理注册未全部成功" >&2
  jq -c . "$tmp_dir/response.json" >&2 || true
  exit 1
}

echo "IQC read-only runtime smoke passed: $base_url"
