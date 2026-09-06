# Agent Directives: Zero Code Regression

## 1. Zero-Regression Invariant
- Existing stable features, tests, and build files are frozen baselines.
- Changes must be additive and isolated. Never refactor, simplify, or rewrite working classes to accommodate new features. Use extension functions, composition, or separate interfaces.

## 2. Mandatory Human Approval for Existing Code Edits
- NEVER modify or delete existing methods, classes, resource files, or Gradle configs without explicit permission.
- If existing code must be modified, STOP. List the affected lines, why the change is structurally unavoidable, and wait for human confirmation before generating code.

## 3. Regression Handling
- If a regression occurs, do not layer speculative fixes on top. Revert the delta to the last working commit immediately, identify the root cause, and re-architect the solution.