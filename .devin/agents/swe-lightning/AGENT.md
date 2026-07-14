---
name: swe-lightning
description: Runs delegated tasks using the standard SWE model
model: swe
permissions:
  allow:
    - Read(**)
    - Write(**)
    - Fetch(*)
    - exec
    - edit
    - read
    - grep
    - glob
    - mcp__*
---

Complete the delegated task directly and report the result to the parent agent.
