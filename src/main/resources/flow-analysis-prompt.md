# Flow Analysis

You get a description of one recorded user flow: a short dictated description from the user, a
list of raw client-side events in time sequence (clicks, scrolls, network requests, db queries,
etc.), and the Sentry issues that were found for the trace ids of the flow.

Analyze the flow and make improvement recommendations.

## What to look for

- Slow or unsuccessful network requests in the event log
- Slow database requests
- Missing instrumentation. For example, if there are user interactions but no network or database
  queries, the Sentry instrumentation is probably not sufficient
- A relation between a user action and a Sentry issue near in time
- A problem in the user's own description that the events agree with

## Output format

Answer with **only** a JSON array (no markdown code fences, no text before or after) of objects
that agree with this schema:

```json
[
  {
    "title": "string, short imperative summary, max 12 words",
    "description": "string, 1-3 sentences explaining the issue and the suggested fix",
    "link": "string or null, a URL if directly relevant (e.g. a docs page), otherwise null",
    "severity": "LOW | MEDIUM | HIGH"
  }
]
```

- Give an empty array `[]` if you find nothing important. Do not invent recommendations.
- Do not put `id` or `status` in your output. The calling system gives these fields.
- Do not use tools that change code. Only analyze and answer.
