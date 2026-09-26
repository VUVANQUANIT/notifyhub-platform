# NotifyHub CI/CD

## Goals

The pipeline protects the main branch, produces reproducible service images and keeps release credentials out of the repository. It separates verification from delivery so a pull request can never publish an image.

```text
Pull request
  -> Maven verify
  -> Compose validation
  -> Build five service images
  -> Trivy HIGH/CRITICAL gate
  -> Dependency review + CodeQL
  -> protected merge to main

SemVer tag (vX.Y.Z)
  -> rerun the complete CI gate
  -> publish five images to GHCR
  -> attach SBOM and build provenance
  -> expose immutable image digests for a deployment adapter
```

## Workflows

### CI

`.github/workflows/ci.yml` runs on pull requests, pushes to `main`, manual dispatch and as a reusable workflow.

- Builds and tests all Maven modules with Java 21 and Maven Wrapper 3.9.16.
- Uploads Surefire/Failsafe reports even when verification fails.
- Validates `docker-compose.yml` without starting infrastructure.
- Builds every service image through the shared `backend/Dockerfile`.
- Rejects images with fixed HIGH or CRITICAL vulnerabilities.
- Reviews newly introduced dependencies and rejects AGPL/GPL dependencies.

### CodeQL

`.github/workflows/codeql.yml` scans Java on relevant pull requests, pushes to `main`, every Monday and on manual request. CodeQL is separate from the fast build workflow so security reporting remains visible and independently retryable.

### Delivery

`.github/workflows/delivery.yml` runs for SemVer tags such as `v0.1.0`. It invokes the reusable CI workflow before publishing.

Each service is published to:

```text
ghcr.io/<owner>/<repository>-<service>:<version>
```

Published images receive a full SemVer tag, a major/minor tag, the source SHA tag and `latest` only for a stable release. BuildKit emits an SBOM and provenance, GitHub records an additional registry attestation, and each immutable image digest is retained as a workflow artifact for 90 days. The workflow uses only `GITHUB_TOKEN` and rejects release commits that are not reachable from `main`.

Manual delivery is available for recovery or release candidates and requires a SemVer-like version input.

## Repository configuration

Configure a branch ruleset for `main`:

1. Require a pull request and at least one approval.
2. Require branches to be up to date before merging.
3. Require `Backend verify`, `Compose validation`, all five `Container / ...` checks, `Dependency review`, and `Analyze Java`.
4. Require conversation resolution and block force pushes/deletions.
5. Enable linear history and automatic branch deletion.

Keep the default workflow token read-only at repository level. The delivery job requests `packages: write`, `attestations: write` and `id-token: write` only for the job that needs them.

For private repositories, confirm that the plan supports Dependency Review, CodeQL and artifact attestations. If a feature is unavailable, do not silently bypass the gate; replace it with an equivalent scanner first.

## Release procedure

Create releases from a commit that has already passed the protected `main` checks:

```powershell
git tag -s v0.1.0 -m "NotifyHub v0.1.0"
git push origin v0.1.0
```

Deploy by immutable digest rather than a mutable tag. Tags remain useful to humans, but a runtime manifest should ultimately reference `image@sha256:...`.

## Deployment adapter

The repository does not yet define a runtime target, so this design deliberately stops at continuous delivery to GHCR. Add one deployment workflow after choosing Kubernetes, ECS, Azure Container Apps or a VM platform. That workflow should:

- accept the five image digests produced by Delivery;
- authenticate to the platform with GitHub OIDC instead of a long-lived cloud key;
- use protected `staging` and `production` GitHub environments;
- serialize each environment with a concurrency group;
- deploy to staging, run smoke tests, then require approval for production;
- retain the previous digest set and automatically roll back when health checks fail.

Infrastructure manifests and the deployment adapter should live in a separate commit from application changes.

## Dependency maintenance

Dependabot checks Maven dependencies, GitHub Actions and the Docker base image weekly. Keep these updates small and let the same CI/security gates verify them.
