# Terms of Service

These terms cover the use of the public HTTP service at `https://api.github-store.org/v1/` and its direct-origin mirror at `https://api-direct.github-store.org/v1/` (collectively, "the service"). Both hostnames serve identical responses.

The service is the optional backend for GitHub Store, a free open-source app store for KMP / Compose Multiplatform applications. It is also the source for any embeddable badges or status images the project may publish.

These terms are short on purpose. We are not lawyers; if you need certainty about whether your specific use is acceptable, ask first at `abuse@github-store.org`.

---

## What this is

The service is:

- a thin search and listing API on top of public GitHub repository data,
- a stateless OAuth proxy for GitHub's device-flow login (`/v1/auth/device/*`),
- a telemetry sink for the GitHub Store client app,
- a store for public ratings and reviews of the listed apps, written by signed-in GitHub users,
- the origin for any badge or status images we publish.

The service is intentionally optional. The GitHub Store client falls back to cached JSON files on GitHub when the service is unreachable. You can build clients that never depend on us.

The source code is published under the Apache License 2.0 — see [`LICENSE`](./LICENSE). These terms govern your use of the *hosted instance* we operate, not the source code itself. You are free to run your own copy under the license.

---

## No warranty, no SLA

The service is provided as-is. There is no guarantee of uptime, latency, accuracy, or fitness for any particular purpose. We try to keep it up; we don't promise we will.

This is a free service run by a single person on a single VPS. Don't build anything safety-critical, business-critical, or money-critical on top of it without your own fallback. (Apache-2.0 Sections 7 and 8 say the same thing in the legal voice.)

We may take the service down for maintenance, change endpoints, change response shapes (additively, not breaking — see `API_CLIENT_GUIDE.md`), or shut it down entirely with reasonable notice on the project's public channels.

---

## Acceptable use

Use the service in good faith. Specifically:

- **Respect the rate limits.** Documented values are in the project's `README.md`. They exist so one heavy user doesn't degrade the service for everyone else.
- **No scraping of the index.** The service exposes search and listings, not a bulk dump. If you want all the data, you can run the underlying Python fetcher (it's open source) against your own GitHub token quota.
- **No automated mass requests beyond the published rate limits.** This includes distributed scraping that tries to evade per-IP limits. If you have a legitimate use case that needs higher throughput, email `abuse@github-store.org` first.
- **No use of the service to attack, probe, or degrade GitHub's API on behalf of someone else.** The token-rotation pool exists to give *real users* a usable experience, not to launder traffic.
- **No embedding our badges or APIs on sites whose primary purpose is illegal content, mass spam, malware distribution, or harassment.** This is not a content-moderation regime over what you can do; it is a narrow ban on using us as infrastructure for those specific things.
- **Don't impersonate the service, the project, or the operator.**
- **Don't try to deanonymize other users from the data we expose.** We do not expose per-user trails, and trying to reconstruct one from public listings is out of bounds.

If you operate the GitHub Store client app or any compatible client, the same rules apply transparently — the client doesn't need to do anything special.

---

## Ratings and reviews

The service stores public ratings and reviews of the apps it lists.

- **Who can write.** You need to sign in with GitHub, and your GitHub account must be at least 30 days old. You can write one review per repository; writing again edits it. You can't rate a repository you have push access to.
- **Your review is public.** It is shown with your GitHub login and avatar to anyone, signed in or not, and is cached by the CDN like other public listings.
- **Keep it honest and about the app.** No spam or advertising. No hate speech, harassment or threats. No other people's personal data. Nothing illegal. No impersonation. No rating manipulation: no reviewing your own project through another account, no paid or traded reviews, no coordinated voting or reporting.
- **Your words stay yours.** You keep the rights to what you write. By posting it you let us store, display, cache and distribute it through the service and the apps that use it for as long as it stays posted. Cached and backup copies expire on their own schedule, described in [`PRIVACY.md`](./PRIVACY.md).
- **Developer replies.** Anyone with push access to the GitHub repository can post one public reply per review on behalf of the project. The same rules apply to replies.
- **Moderation.** A report never removes a review by itself. Moderators chosen by the operator look at reports and may hide or delete a review (with its reply), and may stop an account from writing reviews, replies, votes and reports. These decisions are logged. If you think one was wrong, email `abuse@github-store.org`.
- **Deleting.** You can delete your own review from the app at any time.
- **Reviews are their authors' opinions**, not the operator's. We don't verify what they say.

---

## Rate limits

The current rate limits per endpoint are documented in `README.md`. They are enforced per IP address (with `CF-Connecting-IP` / `X-Forwarded-For` taken into account) and may change.

You can supply your own GitHub Personal Access Token via the `X-GitHub-Token` header on `/v1/search` and `/v1/search/explore`. When you do, GitHub's API quota is charged against your token, not ours, and you get correspondingly more headroom on the upstream side. The backend's own per-IP rate limits still apply on top.

Review writes (reviews, replies, votes and reports) are also limited per GitHub account.

---

## Right to revoke access

We may block individual IPs, IP ranges, GitHub OAuth installations, or `X-GitHub-Token` values from the service if we observe behavior that violates these terms. We may do this without notice if the behavior is causing active harm (e.g., resource exhaustion, attacks). For non-urgent cases we'll usually email `abuse@github-store.org`-listed contacts first if we can identify them.

We may also stop a GitHub account from writing reviews, replies, votes and reports (see "Ratings and reviews" above).

If you believe you've been blocked in error, email `abuse@github-store.org` with what you were doing and we'll take a look.

---

## DMCA / takedown

The service does not host original content. It indexes and proxies metadata about public GitHub repositories. If you believe a repository should not be indexed because it infringes your rights:

- The right place to file is **GitHub itself**. Removing the upstream repository will cause it to drop out of our index on the next fetcher run.
- If you have a reason that's specific to *our* index (for example, the upstream is gone but a stale entry remains), email `dmca@github-store.org` with the repository identifier and the basis of the claim. We aim to respond within 7 days.

We are a thin proxy. Most takedown requests belong upstream.

---

## Privacy

What data the service handles is described in [`PRIVACY.md`](./PRIVACY.md). Using the service implies you've read it.

---

## Changes

These terms live in the public source repository. Changes are visible in `git log -- TERMS.md`. Material changes will be announced in release notes before they take effect.

---

## Governing law

These terms are governed by the laws of the Republic of Estonia, where the operator is based. Disputes that can't be resolved by email to `abuse@github-store.org` would be heard in Estonian courts.

If you're using the service from inside the EU, your local consumer-protection rules still apply where they're mandatory; this clause doesn't override those.

---

## Contact

- Operations / abuse / takedown questions: `abuse@github-store.org`
- DMCA: `dmca@github-store.org`
- Security: `security@github-store.org`
- Privacy: `privacy@github-store.org`

Last updated: 2026-10-25
