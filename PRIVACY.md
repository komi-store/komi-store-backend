# Privacy

This document describes what the GitHub Store backend does with data, where it lives, and how to ask for it back or get it deleted. It covers the HTTP service at `https://api.github-store.org/v1/` and the direct-origin mirror at `https://api-direct.github-store.org/v1/`. The two hostnames serve identical responses from the same machine.

The service is a small, optional component behind a free open-source app store. It is run by a solo developer. If something here reads less polished than a Big Tech privacy page, that is because nobody is paid to write euphemisms. We are not lawyers. Treat this as an honest description of practice, not a legal opinion. If you need certainty about your specific use case, consult a lawyer.

---

## What we collect today

Everything in this section is what the running backend handles right now, as of the last-updated date at the bottom.

### GitHub OAuth identity

When you sign in inside the GitHub Store client app, the client runs GitHub's device-flow OAuth against the backend's `/v1/auth/device/start` and `/v1/auth/device/poll` endpoints. The backend forwards your request to GitHub with our app's `client_id` attached and forwards GitHub's response straight back to the client.

- The access token GitHub issues passes through the backend in memory only. It is not logged, not cached, not written to a database, not sent to error tracking.
- The token lives on your device, in the client app, and nowhere else on our side.
- The GitHub user ID and login that the client derives from the token are used as your account key in the backend's tables (telemetry attribution, reviews, future subscription state). They are never sold, shared, or exported.

### Ratings and reviews

Reviews are public by design. This is what the backend handles when you rate or review an app in the client.

**Checking who you are.** Writing a review, voting, reporting and replying need GitHub sign-in. The client sends your GitHub token with those requests, and with the request that loads your own review state. The backend uses the token for GitHub API calls on your behalf: `GET /user` (your GitHub user ID, login, avatar URL and account creation date) and a repository lookup (whether you have push access to the repository you are reviewing or replying on). The token is never logged, stored or sent to error tracking. GitHub's answers are kept in memory for up to 10 minutes so repeated taps don't call GitHub again; that cache is keyed by a keyed hash (HMAC) of the token, not the token itself. The account creation date is used only for the rule that accounts must be at least 30 days old and is not written to the database.

**What is stored, and what is public:**

| Data | Stored | Public |
|------|--------|--------|
| Your review: the rating, optional text (up to 2000 characters), and the app version, platform and app label you reviewed | yes | yes |
| Your GitHub user ID, login and avatar URL, as a snapshot refreshed whenever you write | yes | yes, next to your review |
| When the review was written and last edited | yes | yes |
| Votes: which reviews you voted up or down, with your GitHub user ID | yes | only each review's net score; your own votes are shown only to you |
| Reports: the review, your GitHub user ID, the reason and your optional note (up to 500 characters) | yes | no. Moderators see the reason and note, not who reported |
| Developer replies: the text, and the replier's GitHub user ID, login and avatar URL | yes | yes |
| Moderation: why a review was hidden; bans (GitHub user ID and reason); an audit log of every moderation action (the moderator's GitHub user ID, the action, the review and the user it affected, the reason, the time) | yes | no |

Public review lists and rating summaries are served to anyone without sign-in and cached by the CDN for a few minutes, like other public listings. Once something is public, third parties we don't control may copy it.

**Deleting your review.** You can delete your own review from the app at any time, including after a moderator hid it or stopped your account from reviewing. Deleting removes the review, the votes on it, the reports about it and any developer reply from the database right away. CDN copies of public lists expire within about five minutes, and encrypted database backups roll over within 30 days. To remove your votes or reports, or everything tied to your GitHub user ID, email us (see "Your rights").

**Moderation.** A report never hides a review by itself. A small group of moderators (GitHub accounts the operator lists in the backend's configuration, plus the operator) reviews reports and can hide a review, restore it, dismiss its reports, delete it, or stop an account from writing reviews, replies, votes and reports. A hidden review leaves the public list and the rating but stays visible to its author. Every moderation action goes into the audit log.

### Telemetry events (`POST /v1/events`)

The client can batch telemetry events to the backend. This is opt-in in the client. Each event carries the fields defined in `src/main/kotlin/.../model/EventRequest.kt`:

- event type (e.g., `view_repo`, `install_attempt`, `install_success`)
- repository identifier (owner/name)
- platform (`android` / `windows` / `macos` / `linux`)
- a `device_id` string supplied by the client
- a timestamp

Before storage the `device_id` is hashed (SHA-256). The raw value is not retained. The hash is used to deduplicate signals and rate-limit ranking influence per device; it is not reversible to the original ID.

Events feed the ranking job (`SignalAggregationWorker`) that produces the `search_score` you see on category and topic listings. They are not sold, not shared, and not used for advertising.

### Search-miss tracking

When `/v1/search` returns fewer than 5 results, the backend writes one row to the `SearchMisses` table containing:

- `sha256(query)` — the SHA-256 hash of the query string
- the result count (0 to 4)
- a timestamp

The raw query string is **never** persisted. It exists in memory for the duration of the request and is discarded. The hash lets the warming worker re-run the most-missed queries against GitHub on a schedule without us being able to reconstruct what users actually searched for.

### Rate-limit IPs

The Ktor rate-limit plugin keys requests off `CF-Connecting-IP`, then `X-Forwarded-For`, then the socket IP. These keys live in process memory only. They are not written to disk, not written to the database, and not sent to error tracking. They are flushed when the process restarts.

### Aggregate access logs

Standard request logs are emitted to stdout and rotated by the container runtime. Each line records: a request ID, HTTP status, method, and path. We deliberately do not log: the User-Agent, the request body, the response body, or arbitrary headers. Logs are kept for 14 days (see "Data retention" below).

### Error tracking (Sentry, EU region)

The backend sends uncaught exceptions and structured error events to Sentry's EU ingest endpoint (`*.ingest.de.sentry.io`). Sentry events include the request ID, the exception, and a stack trace. They do not include `X-GitHub-Token`, search query strings, or the device-flow OAuth token — these are explicitly stripped or never attached as Sentry tags or breadcrumbs.

---

## What we may collect when paid features launch

This section is forward-looking. None of these are running in production today. They describe what would be added when the corresponding paid feature ships. The roadmap is in the client repository (`roadmap/PAID_FEATURES.md`). No commitment is made to any specific ship date, and any feature listed here may be cut or redesigned. If a feature ships, this document will be updated before the feature is enabled.

### Subscription state

If/when subscriptions launch, the backend will store, per account:

- a Stripe customer ID
- a billing email (the address you give Stripe; not used for marketing)
- subscription status (`active`, `trialing`, `past_due`, `canceled`)
- entitlement expiry timestamps

We do not see your card number, CVC, or full PAN. Stripe handles all of that.

### Mobile in-app purchase receipts

For iOS and Android subscriptions purchased through the platform stores, the backend will receive Google Play purchase tokens or Apple receipt tokens. These are forwarded to Google/Apple for verification and stored only as proof of entitlement. We do not store the underlying account email from the platform store.

### Library sync (zero-knowledge)

If/when library sync launches, your library list is encrypted on your device with a key derived from a passphrase you control. The backend stores the encrypted blob. The backend cannot decrypt it. We do not have the key, we cannot recover the key for you, and a server-side breach of these blobs would yield ciphertext only. This is what "zero-knowledge" means here.

### Per-repo developer analytics (k-anonymous)

If/when "Dev Claim Pro" launches, repository owners who claim their repo will see aggregate analytics for their own repo. The aggregation is k-anonymous: any bin shown contains at least 5 events. No user IDs, no device hashes, and no per-user breakdowns are exposed in this feature, even to us internally.

### Push notification tokens

If/when announcement subscriptions launch, users who explicitly opt in to push notifications from a specific repository will register a device push token (FCM on Android, APNs on iOS/macOS). The token is used solely to deliver notifications you asked for, and is deleted when you unsubscribe.

---

## What we never collect

These are hard guarantees. If any of them changes, this document will be updated and announced before the change takes effect.

- **Raw search queries.** Only `sha256(query)` plus a result count.
- **Browse / view history at the user level.** Telemetry events are aggregated to drive ranking, not stitched into per-user trails.
- **"Users who installed X also installed Y."** No cross-app correlation across users is computed or stored.
- **Marketing emails without explicit opt-in.** If you don't tick a box, you don't get email.
- **Precise location.** No GeoIP lookup, no lat/long. At most we know an ISO country code from CDN headers, and we don't store it past the request.
- **The OAuth access token after the device-flow poll completes.** It passes through the suspending handler and out to the HTTP response; nothing else.
- **The GitHub token sent with review requests.** It is used for that request's GitHub calls and nothing else; the short-lived identity cache is keyed by a hash of it.
- **A public record of who voted for or reported a review.** Only each review's net score is public, and the moderation queue doesn't show who reported.

---

## Where data lives

- **Application server, Postgres, Meilisearch:** one VPS in Hetzner's Falkenstein, Germany region (EU).
- **Error tracking:** Sentry, EU region (Frankfurt).
- **TLS certificates:** Let's Encrypt (issued from CDN/Caddy on the VPS).
- **Edge cache:** Gcore CDN. Cached responses are public listings (categories, topics, repo details, review lists and rating summaries). Review writes and your own review state are marked `no-store` and are never cached. The CDN does not see authenticated requests for telemetry or auth — those are sent to the direct origin or pass through uncached. The CDN sees client IPs and request paths for cached endpoints.

The operator is based in Estonia. The hosting and processing all happens inside the EU/EEA.

---

## Who has access

- **Operator (solo developer).** SSH access to the VPS, database access via SSH tunnel, Sentry project access. No team members, no contractors, no analytics vendors.
- **Review moderators.** GitHub accounts the operator lists as moderators can see the moderation queue (reported and hidden reviews, with report reasons and notes but not who reported) and act on reviews. They have no access to the database or to anything else in this document.
- **Hosting / infrastructure providers** listed below have access to the data they directly process; none of them are given application data on top of that.

### Subprocessors today

| Provider       | Role                                | Region |
|----------------|-------------------------------------|--------|
| Hetzner Online | VPS hosting, network, disk          | DE     |
| Sentry         | Error and exception tracking        | EU     |
| Let's Encrypt  | TLS certificate issuance            | global |
| Gcore          | CDN edge cache for public listings  | global |

### Subprocessors that would be added with paid features

| Provider             | Role                                            |
|----------------------|-------------------------------------------------|
| Stripe               | Subscription billing, card processing           |
| Google (Play Billing)| Android in-app purchase verification            |
| Apple (App Store)    | iOS / macOS in-app purchase verification        |
| FCM (Google)         | Android/desktop push notifications (opt-in)     |
| APNs (Apple)         | iOS/macOS push notifications (opt-in)           |

This list will be kept current. Adding a subprocessor requires a documented update here.

---

## Data retention

| Data                              | Retention                              |
|-----------------------------------|----------------------------------------|
| Telemetry events (`Events` table) | 90 days, then deleted                  |
| Search-miss rows (hashes only)    | 30 days, then deleted                  |
| Application access logs           | 14 days, then rotated out              |
| Rate-limit IP buckets             | In-memory only; gone on process restart|
| Sentry events                     | Sentry's default retention for our plan|
| OAuth access tokens               | Never persisted (in-memory pass-through only) |
| Reviews and developer replies     | Until the author deletes them or a moderator deletes them |
| Hidden reviews                    | Until the author or a moderator deletes them |
| Votes and reports                 | As long as the review they belong to exists |
| Review bans                       | Until a moderator lifts the ban          |
| Moderation audit log              | No automatic expiry; kept to handle abuse and appeals |
| Review identity cache             | In memory only, up to 10 minutes         |

The retention windows above are applied by scheduled cleanup jobs running in the backend itself. Forward-looking paid-feature data (subscription state, encrypted library blobs, push tokens) will have their own retention policies documented here when they ship.

---

## Your rights

You can ask us to:

- Tell you what we have on you (access).
- Correct anything that's wrong (rectification).
- Delete it (erasure).
- Export it in a portable format (portability).
- Stop processing it for a specific purpose (objection / restriction).

These rights track the rights enumerated in GDPR Articles 15–22. We don't claim certified compliance with any particular regulation; we describe the practice.

To exercise any of these, email **privacy@github-store.org** from the email address linked to your GitHub account, or include enough information for us to identify the data (your GitHub login is usually enough, since it's the account key). We aim to respond within 30 days. If we need longer (e.g., a complicated export), we'll tell you why and give a new estimate.

There is no charge for these requests. There is no form. Plain English in the email is fine.

For your own review you don't need to email: delete it from the app at any time. Email for anything else, such as your votes and reports.

---

## International transfers

The operator is in Estonia. The servers are in Germany. Sentry processing is in the EU. All of these are inside the EU/EEA, so transfer to a third country is not part of normal operation.

If a future subprocessor is outside the EEA (for example, Stripe's billing pipeline, or APNs), the transfer will rely on whatever standard mechanism that provider uses (Standard Contractual Clauses, adequacy decisions, etc.) and will be listed in the subprocessor table above before it goes live.

---

## Children

The service is not directed at people under 16. The client application's distribution and our domain registration both reflect this. We do not knowingly accept telemetry, accounts, or any other data from people we know to be under 16. If you are a parent or guardian and believe a child has used the service, email `privacy@github-store.org` and we will delete the associated data.

---

## Security

If you believe you've found a security issue, please follow the process in [`SECURITY.md`](./SECURITY.md). Don't post vulnerabilities in public GitHub issues.

---

## Changes to this policy

This file is checked into the public source repository. Every change to it lives in `git log -- PRIVACY.md` and is publicly inspectable. Material changes (new data category, new subprocessor, change to a "never collect" guarantee) will be announced in the project's release notes and on the project's social channels before they take effect. The "Last updated" date at the bottom moves on every commit that touches this file.

---

## Contact

- General privacy questions, data requests: `privacy@github-store.org`
- Abuse: `abuse@github-store.org`
- Security: `security@github-store.org`

Last updated: 2026-10-25
