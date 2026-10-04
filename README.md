# Breeze Core

Breeze Core is the standalone Spring Boot Core Platform service for BreezeBuild.

Next.js calls `POST /api/users/provision` to provision the authenticated Clerk user and
`GET /api/users/provisioned` to check whether that user already exists in Core. The
check is read-only: it returns `204` for a provisioned user or the standard
`CORE_USER_NOT_PROVISIONED` error (`428`) otherwise. Both endpoints require a valid
Clerk session JWT; the user ID comes from the verified token.

## Projects

Core stores project metadata. All project endpoints require a Clerk session JWT and
use its verified `sub` claim to scope access to the owning user. A project owned by
another user is returned as `PROJECT_NOT_FOUND` (`404`). Project creation records
a Temporal workflow-start request in the same database transaction as the project.
It does not create a source-code workspace yet; that belongs to Breeze Workspace.

| Method | Path | Action |
| --- | --- | --- |
| `POST` | `/api/projects` | Create (`201`) |
| `GET` | `/api/projects` | List your projects |
| `GET` | `/api/projects/{projectId}` | Read one project |
| `PUT` | `/api/projects/{projectId}` | Replace name and description |
| `DELETE` | `/api/projects/{projectId}` | Delete (`204`) |

Create and update accept a required `name` (up to 255 characters) and optional
`description` (up to 2000 characters). Delete currently removes Core metadata;
cross-service cleanup will be coordinated when Workspace project creation exists.
`workSpaceInitializedOn` is read-only and remains null until Workspace creation is
confirmed. A Workspace creation API and Temporal worker are not implemented yet,
so a started workflow remains queued rather than completing initialization.

## Stack

- Java 21
- Spring Boot 4
- Gradle
- PostgreSQL
- Flyway
- Clerk JWT authentication
- Clerk Java Backend API SDK for profile retrieval

## Prerequisites

- Java 21
- PostgreSQL running locally (default port: `5434`)

## Local configuration

Create an ignored `.env` file in this directory. Do not commit real credentials.

```properties
DB_URL=jdbc:postgresql://localhost:5434/breeze_core
DB_USERNAME=postgres
DB_PASSWORD=replace-me
CLERK_ISSUER_URI=https://your-clerk-instance.clerk.accounts.dev
CLERK_JWK_SET_URI=https://your-clerk-instance.clerk.accounts.dev/.well-known/jwks.json
CLERK_ALLOWED_AUTHORIZED_PARTIES=http://localhost:3000
CLERK_SECRET_KEY=sk_replace-me
```

`CLERK_SECRET_KEY` is needed only for Clerk profile synchronization. Without it,
provisioning still succeeds and profile-sync events remain pending; project
initialization events can still be sent to Temporal. Keep the real key in the
ignored `.env` or your secret manager.

## Outbox and Temporal

Provisioning commits the Core user and one initial profile-sync event together. A dedicated
Resilience4j `ThreadPoolBulkhead` claims events with PostgreSQL `SKIP LOCKED`, performs
remote calls outside the claim transaction, and retries failures with a lease and
bounded backoff. Project creation similarly commits project metadata and a Temporal
workflow-start event together. The worker uses the workflow ID
`project-initialization-{projectId}` so delivery retries do not create a second run.
Completing the outbox event means Temporal accepted the start, not that a Workspace
exists.

Core connects to `localhost:7233` in Temporal's `default` namespace by default and
starts `ProjectInitialization` on the `breeze-project-initialization` task queue.
Override these with `BREEZE_TEMPORAL_TARGET`, `BREEZE_TEMPORAL_NAMESPACE`, and
`BREEZE_TEMPORAL_PROJECT_TASK_QUEUE`. Until a worker registers that workflow and
the Workspace creation contract exists, do not treat the queued workflow as
successful project initialization.

The bulkhead has no in-memory queue, so a busy pool leaves work in PostgreSQL. Configure it
with `BREEZE_OUTBOX_WORKER_THREADS` (default `1`). Optional tuning variables are
`BREEZE_OUTBOX_POLL_INTERVAL_MS` (`1000`), `BREEZE_OUTBOX_LEASE_SECONDS` (`60`),
`BREEZE_OUTBOX_MAX_ATTEMPTS` (`5`), and `BREEZE_OUTBOX_RETRY_BASE_SECONDS` (`5`).
Restart Core after changing these settings. Existing `PENDING` users created before this
migration receive an event on their next provisioning request.

## Collaboration

Make future changes on feature branches and propose them through pull requests
targeting `main`. The repository's `main` branch requires a PR; do not push to
it directly or merge a PR on the reviewer's behalf.

## Run locally

```powershell
.\gradlew.bat bootRun
```

## Run tests

```powershell
.\gradlew.bat test
```

## Observability stack

Breeze Core uses New Relic for infrastructure monitoring, log collection, and Java APM.

### New Relic Java APM agent

The Java agent is available under `observability/newrelic/`:

- `newrelic.jar` is the New Relic Java agent and is intentionally ignored by Git.
- `newrelic.yml` is versioned, contains no secret, and enables agent-side log forwarding. Distributed tracing is currently disabled.
- New Relic agent diagnostic logs are written to the ignored `logs/` directory.

Set the license key only in the shell session that starts the JVM. Do not add it to source code or `newrelic.yml`.

```powershell
$env:NEW_RELIC_LICENSE_KEY = 'your-new-relic-license-key'
.\gradlew.bat bootJar
java -javaagent:observability/newrelic/newrelic.jar `
  -Dnewrelic.config.file=observability/newrelic/newrelic.yml `
  -jar build/libs/breeze-core-0.0.1-SNAPSHOT.jar
```

When the agent JAR and configuration are present, `bootRun` attaches the New Relic Java agent.
