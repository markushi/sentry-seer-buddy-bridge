# Option 1: Drive a Freeform Seer Chat Through the Sentry Monolith

This document is the plan and the reference for option 1: use the Sentry API
endpoints that exist today. You do not change the monolith, and you do not
call Seer directly.

All code references point to `sentry` (`src/sentry/...`) unless the text says
`seer`.

---

## 1. Summary of the Flow

| Step | Call | Result |
|---|---|---|
| 1 | `POST /api/0/organizations/{org}/seer/explorer-chat/` | Starts a new run. Gives you `run_id`. |
| 2 | `GET /api/0/organizations/{org}/seer/explorer-chat/{run_id}/` | Poll until `status` is `completed`. Read the recommendations from the last block. |
| 3 | `POST /api/0/organizations/{org}/seer/explorer-chat/{run_id}/` | Send the refined list of actions as a new turn. |
| 4 | `GET .../seer/explorer-chat/{run_id}/` | Poll again. `blocks[].file_patches` shows the code changes. |
| 5 | `POST /api/0/organizations/{org}/seer/explorer-update/{run_id}/` with `payload.type = "create_pr"` | Starts the PR task. Returns `202`. |
| 6 | `GET .../seer/explorer-chat/{run_id}/` | Poll `repo_pr_states` for `pr_url`. |

---

## 2. Authentication: You Must Use a User Token

This is the most important constraint of option 1.

- Step 3 (continue) fails with `403` if the token has no user:
  `PermissionDenied("A user account is required to continue a conversation.")`
  (`seer/endpoints/organization_seer_agent_chat.py:301`).
- Step 5 (update) fails the same way:
  `PermissionDenied("A user account is required to update a conversation.")`
  (`seer/endpoints/organization_seer_agent_update.py:76`).
- Step 1 (new run) permits a null user. `SeerAgentClient` writes
  `user_id = None` in that condition (`seer/agent/client.py:498`).

Therefore an organization token (internal integration) can start a run, but it
cannot continue the run and it cannot make the PR. Use a **user auth token**.

Necessary scope: `org:read` for all endpoints in this document
(`scope_map` of each permission class).

Header:

```
Authorization: Bearer <user-auth-token>
Content-Type: application/json
```

### 2.1 Ownership

`resolve_seer_run(..., for_continue=True, user_id=...)` compares the caller
with the owner of the run. If they are different, you get `403`:
"This conversation belongs to another user and is read-only."
(`seer/endpoints/utils.py:31`).

Consequence: one service account must start, continue and finish each run.
Do not mix accounts in one run.

### 2.2 Access Gate

`has_seer_agent_access_with_detail` (`seer/agent/client_utils.py:346`) makes
these checks:

1. Base Seer access: the `gen-ai-features` feature, `hideAiFeatures` is off,
   and the organization accepted the Seer conditions.
2. The feature flag `organizations:seer-explorer`.
3. `organization.flags.allow_joinleave` (open team membership).

If one check fails, the endpoint gives `403` with a descriptive `detail`.

### 2.3 Options and Flags That Change the Behavior

| Item | Type | Effect |
|---|---|---|
| `organizations:seer-explorer` | feature flag | Necessary for all chat and update calls. |
| `sentry:enable_seer_coding` | org option | Necessary for `enable_coding` and for the `create_pr` payload. |
| `organizations:seer-explorer-chat-coding` | feature flag | Second condition for `enable_coding`. |
| `organizations:seer-explorer-code-mode-tools` | feature flag | If off, Code Mode is `"off"`. If on, the default is `"only"`. |
| `seer-run-questions` | feature flag | Necessary for `expand=questions` on the runs list. |

`enable_coding` is `sentry:enable_seer_coding` AND
`organizations:seer-explorer-chat-coding`. Without both, the agent cannot
write code, and step 5 has no patches to put in a PR.

---

## 3. `POST .../seer/explorer-chat/` — Start a Run

Route: `api/urls.py:2462`, name `sentry-api-0-organization-seer-explorer-chat`.
Publish status: `PRIVATE`. Rate limits: 25/60 s per IP, 25/60 s per user,
100/3600 s per organization.

### 3.1 Accepted Fields

The serializer is `SeerAgentChatSerializer`
(`seer/endpoints/organization_seer_agent_chat.py:88`). It accepts only these
fields:

| Field | Type | Default | Notes |
|---|---|---|---|
| `query` | string | — | Required. Cannot be blank. This holds your prompt and your trace payload. |
| `insert_index` | int, null | — | Puts the message at an index. Leave it out. |
| `on_page_context` | string, null | — | Free context. If it parses as a JSON object with a `nodes` key, the endpoint converts it with `snapshot_to_markdown`. |
| `page_name` | string, null | `None` | Route name of the UI page. |
| `page_location` | object, null | `None` | Keys `url`, `name`, `params`, `query`. All optional. |
| `sent_at` | list of strings | `None` | Maximum 4 items, each maximum 64 characters. Display strings only. |
| `override_bash_mode_enabled` | bool | `False` | Turns on the bash tools. |
| `override_ce_enable` | bool | `True` | Context-engine override. |
| `override_code_mode_enable` | `"off"`, `"on"`, `"only"`, or bool | — | Booleans map to `"on"`/`"off"`. Only effective if the code-mode flag is on. |
| `ui_tools` | string | — | A JSON string of UI tool definitions. |

Fields that the endpoint sets and you cannot control:
`is_interactive=True`, `reasoning_effort="medium"`, `organization_id`,
`user_org_context`, `enable_coding`, `enable_code_mode_tools`.

### 3.2 What Option 1 Cannot Do

The serializer has **no** `artifact_key` and no `artifact_schema`. Seer
supports them (`seer/automation/explorer/models.py:820`) and
`SeerAgentClient.start_run` forwards them (`seer/agent/client.py:394`), but no
public field reaches them.

Consequence: you cannot get a schema-validated JSON list of recommendations.
You get prose in `blocks[].message`. Two possible answers:

- Ask for a strict format in the `query` text (for example, "Answer only with
  a JSON array of objects with the keys `title`, `category`, `rationale`"),
  then parse the answer and tolerate a failure.
- Later, add the two fields to the serializer. That is a small change, but it
  is not option 1.

`blocks[].artifacts` is always in the response model, so a subsequent change
does not break your client.

### 3.3 Request Example

```json
{
  "query": "Here is a mobile trace ... Give recommended actions.",
  "page_name": "external:trace-advisor",
  "sent_at": ["2026-08-18T09:12:00Z"]
}
```

### 3.4 Response

`200` with:

```json
{"run_id": 12345, "sentry_run_id": "3f2c…-uuid"}
```

`run_id` is the numeric Seer state id. `sentry_run_id` is the UUID of the
Sentry mirror row. The URL of the subsequent calls accepts each of the two.

Errors: `400` with the serializer errors; `403` with `detail` from the access
gate; `500` `{"detail": "Failed to start or continue chat session"}`.

---

## 4. `POST .../seer/explorer-chat/{run_id}/` — Continue the Run

Route name: `...-chat-run-id` (`api/urls.py:2470`).

Same serializer and same rate limits as section 3. Put the refined list of
actions in `query`.

Necessary conditions:

- A user token (section 2).
- The caller is the owner of the run.

Response `200`:

```json
{"run_id": 12345, "sentry_run_id": "3f2c…-uuid"}
```

`resolve_seer_run` with `for_continue=True` can give:

| Condition | Status | Body |
|---|---|---|
| `run_id` is numeric but too large | `400` | `{"detail": "Invalid run_id"}` |
| `run_id` is not a number and not a UUID | `400` | `{"detail": "Invalid run_id"}` |
| The UUID is unknown | `404` | `{"session": null}` |
| The run belongs to another user | `403` | ownership message |
| The mirror row failed | `422` | error detail |
| The Seer run is not yet created | `409` | "This run is still being created; retry shortly." |

Do not send a new turn while `status` is `processing`. Poll first.

---

## 5. `GET .../seer/explorer-chat/{run_id}/` — Read the State

Rate limits: 100/60 s per IP, 100/60 s per user, 1000/60 s per organization.
A poll interval of 1 s to 2 s is safe.

Response `200`:

```json
{
  "session": { "...SeerRunState..." },
  "sentry_run_id": "3f2c…-uuid"
}
```

`SeerRunState` (`seer/agent/client_models.py:260`) gives:

| Field | Type | Notes |
|---|---|---|
| `run_id` | int | |
| `status` | string | `processing`, `completed`, `error`, `awaiting_user_input` |
| `blocks` | list of `MemoryBlock` | The conversation. |
| `updated_at` | datetime | |
| `owner_user_id` | int, null | |
| `pending_user_input` | object, null | `id`, `input_type`, `data` |
| `repo_pr_states` | list of `RepoPRState` | The result of `create_pr`. |

`metadata`, `coding_agents` and `usage` have `exclude=True`. They are not in
the public response.

`MemoryBlock` (`:159`) gives `id`, `message`, `timestamp`, `loading`,
`artifacts`, `file_patches`, `merged_file_patches`, `pr_commit_shas`, `todos`,
`tool_links`, `tool_results`.

`RepoPRState` (`:103`) gives `repo_name`, `provider`, `branch_name`,
`pr_number`, `pr_url`, `pr_id`, `commit_sha`, `pr_creation_status`
(`creating`, `completed`, `error`), `pr_creation_error`, `title`,
`description`, `integration_id`.

Note: the monolith model has no `pr_creation_error_code` and no
`idempotency_key`. Seer sends them, but the monolith removes them.

### 5.1 Polling Rules

1. Wait for `status == "completed"` before you read the answer.
2. If `status == "error"`, stop. Do not send a new turn.
3. If `status == "awaiting_user_input"`, answer with the update endpoint
   (section 6.2).
4. Read the last block whose `loading` is `false`.
5. A `404` with `{"session": null}` and a `{"session": {"status":
   "processing"}}` body can occur before the Seer run exists. Retry.

---

## 6. `POST .../seer/explorer-update/{run_id}/` — Events

Route: `api/urls.py:2490`. Publish status `PRIVATE`. POST only. Scope
`org:read`. This endpoint has no special rate-limit override.

Body: an object with a `payload` object. The endpoint adds `run_id` and
`organization_id` and forwards the body to Seer
`/v1/automation/explorer/update`.

Response: **`202`** with the Seer JSON (`{"run_id": ...}`). `202` means that
the task is in the queue, not that the work is complete.

Coding payload types are `select_solution`, `create_branch` and `create_pr`
(`seer/autofix/constants.py:3`). For them the organization option
`sentry:enable_seer_coding` must be on. If not, you get `403`
`{"detail": "Code generation is disabled for this organization"}`.

### 6.1 `interrupt`

```json
{"payload": {"type": "interrupt"}}
```

Stops the run that operates now.

### 6.2 `user_input_response`

```json
{
  "payload": {
    "type": "user_input_response",
    "input_id": "<session.pending_user_input.id>",
    "response_data": {"...": "..."}
  }
}
```

### 6.3 `create_pr`

```json
{
  "payload": {
    "type": "create_pr",
    "repo_name": "getsentry/my-app",
    "pr_description_suffix": "Made by the trace advisor.",
    "ready_for_review": true,
    "idempotency_key": "trace-advisor-<your-id>",
    "verify_content": false
  }
}
```

Fields come from `ExplorerCreatePRPayload`
(`seer/automation/explorer/models.py:929`).

Two behaviors that you must know:

- The endpoint **always removes** a client-supplied `author` and puts
  `commit_author_for_user(request.user, ...)` in its place. The commits get
  the identity of the token owner.
- Seer's `start_explorer_create_pr` gives `False` and puts no task in the
  queue if the run has no patches, or if the named repo has none
  (`seer/automation/explorer/tasks.py:801`). You still get `202`.

Therefore, do not use `202` as proof. Poll `repo_pr_states` until one item
has `pr_creation_status == "completed"` and a `pr_url`. Use a timeout.

To know which repos have patches, read `blocks[].file_patches` from the GET
state. The Seer `/repos` endpoint is not available through the monolith.

---

## 7. `GET .../seer/runs/` — List Runs

Route: `api/urls.py:2480`. Publish status `EXPERIMENTAL`. Scope `org:read`.
It reads the Sentry mirror tables, not Seer.

Query parameters:

| Parameter | Notes |
|---|---|
| `query` | Structured search. Supports `source`, `type`, `project`, `is:agent`/`!is:agent`, `is:mine`/`!is:mine`, and free text against the title. |
| `expand` | Repeatable. `expand=questions` adds the built-in question outputs. Needs the `seer-run-questions` feature. |
| `question` | Repeatable free text. Maximum 5. Needs the same feature. |
| date range | The standard `statsPeriod` / `start` / `end` parameters. |
| `project` | The standard project filter. |

POST accepts the same parameters in the body.

Outputs correlate by position: the built-in set first, then your `question`
parameters in the order that you sent them.

Use this endpoint for a list of runs in a UI. It is not necessary for the
flow.

---

## 8. `GET .../seer/explorer-pr-groups/` — Not for You

Route: `api/urls.py:2486`. It lists the issues that have a PR from Seer, and
it filters on `category_key="autofix"`. Your runs have no issue and no
autofix category, so this endpoint does not show them.

---

## 9. Failure Modes to Handle

| Symptom | Cause | Action |
|---|---|---|
| `403` "A user account is required…" | Organization token | Use a user token. |
| `403` ownership message | A different account continues the run | Use one account for each run. |
| `403` "Code generation is disabled…" | `sentry:enable_seer_coding` is off | Turn on the option. |
| `403` with a Seer-access detail | Missing flag, `hideAiFeatures`, no acknowledgement, or `allow_joinleave` is off | Correct the organization setup. |
| `409` "still being created" | You polled or continued too early | Retry with a backoff. |
| `202` but no PR appears | No patches for the repo | Check `file_patches` first. Then poll with a timeout. |
| `429` | Rate limit | Respect 25 POST/min and 100 GET/min. |
| Prose instead of JSON | No artifact schema in option 1 | Ask for the format in the prompt and parse defensively. |

---

## 10. Code Map

| Item | Location |
|---|---|
| URL routes | `api/urls.py:2462`–`2496` |
| Chat endpoint and serializer | `seer/endpoints/organization_seer_agent_chat.py` |
| Update endpoint | `seer/endpoints/organization_seer_agent_update.py` |
| Run id resolution | `seer/endpoints/utils.py:31` |
| Access gate | `seer/agent/client_utils.py:346` |
| Seer client | `seer/agent/client.py` |
| Response models | `seer/agent/client_models.py` |
| Coding payload types | `seer/autofix/constants.py:3` |
| Runs list | `seer/endpoints/organization_seer_runs.py` |
| Seer-side contracts | seer `src/seer/automation/explorer/models.py` |
| Seer-side routes | seer `src/seer/routes/explorer.py` |

For the direct Seer path (no monolith), see
`api_docs/explorer_external_client.md`.
