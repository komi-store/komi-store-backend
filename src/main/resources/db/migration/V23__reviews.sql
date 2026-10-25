-- Ratings and reviews (client contract: docs/agents/reviews.md in komi-store).
-- Every statement is IF NOT EXISTS so a partially-applied first run re-runs clean.

-- One row per reviewed repository, carrying its rating aggregate. GitHub repos
-- are keyed by numeric id so a rename keeps its reviews; owner/name is the
-- latest seen and refreshed on every write. Forge repos have no stable id and
-- are keyed by host + owner + name. Deliberately no FK to repos: reviews exist
-- for repos outside the curated catalog, and catalog rows get deleted.
--
-- review_count / rating_sum / stars_N count VISIBLE reviews only and are kept
-- in step with `reviews` inside the same transaction as every write, hide,
-- unhide and delete (PostgresReviewStore locks this row first).
CREATE TABLE IF NOT EXISTS review_repos (
    id           BIGSERIAL   PRIMARY KEY,
    host         TEXT        NOT NULL,
    github_id    BIGINT,
    owner        TEXT        NOT NULL,
    name         TEXT        NOT NULL,
    review_count INTEGER     NOT NULL DEFAULT 0,
    rating_sum   INTEGER     NOT NULL DEFAULT 0,
    stars_1      INTEGER     NOT NULL DEFAULT 0,
    stars_2      INTEGER     NOT NULL DEFAULT 0,
    stars_3      INTEGER     NOT NULL DEFAULT 0,
    stars_4      INTEGER     NOT NULL DEFAULT 0,
    stars_5      INTEGER     NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK ((host = 'github.com') = (github_id IS NOT NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_review_repos_github_id
    ON review_repos (github_id) WHERE github_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_repos_forge
    ON review_repos (host, lower(owner), lower(name)) WHERE github_id IS NULL;
-- Public reads resolve GitHub repos by name when the catalog doesn't know them.
CREATE INDEX IF NOT EXISTS idx_review_repos_name
    ON review_repos (host, lower(owner), lower(name));

-- One review per GitHub user per repo. author_login / author_avatar_url are a
-- snapshot refreshed whenever the author writes. Hidden reviews keep their row
-- (the author still sees it in /mine) but leave the public list and aggregate.
-- hidden_by NULL with hidden_at set means the operator's X-Admin-Token.
CREATE TABLE IF NOT EXISTS reviews (
    id                TEXT        PRIMARY KEY,
    repo_id           BIGINT      NOT NULL REFERENCES review_repos(id) ON DELETE CASCADE,
    author_id         BIGINT      NOT NULL,
    author_login      TEXT        NOT NULL,
    author_avatar_url TEXT,
    rating            SMALLINT    NOT NULL CHECK (rating BETWEEN 1 AND 5),
    text              TEXT,
    version           TEXT,
    platform          TEXT        CHECK (platform IN ('android', 'windows', 'macos', 'linux')),
    app_label         TEXT,
    score             INTEGER     NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    hidden_at         TIMESTAMPTZ,
    hidden_by         BIGINT,
    hidden_reason     TEXT,
    UNIQUE (repo_id, author_id)
);

CREATE INDEX IF NOT EXISTS idx_reviews_author ON reviews (author_id);
CREATE INDEX IF NOT EXISTS idx_reviews_hidden_at ON reviews (hidden_at DESC) WHERE hidden_at IS NOT NULL;

-- Up/down votes: one per user per review, value +1 or -1. reviews.score is
-- their sum (upvotes minus downvotes) and moves with each vote change.
CREATE TABLE IF NOT EXISTS review_votes (
    review_id  TEXT        NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    voter_id   BIGINT      NOT NULL,
    value      SMALLINT    NOT NULL CHECK (value IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (review_id, voter_id)
);

CREATE INDEX IF NOT EXISTS idx_review_votes_voter ON review_votes (voter_id);

-- Reports never hide anything by themselves. An open report (resolved_at
-- NULL) puts its review in the moderation queue; hide and dismiss close it.
CREATE TABLE IF NOT EXISTS review_reports (
    review_id   TEXT        NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    reporter_id BIGINT      NOT NULL,
    reason      TEXT        NOT NULL CHECK (reason IN ('spam', 'offensive', 'off_topic', 'other')),
    note        TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    resolved_at TIMESTAMPTZ,
    resolution  TEXT        CHECK (resolution IN ('hidden', 'dismissed')),
    PRIMARY KEY (review_id, reporter_id)
);

CREATE INDEX IF NOT EXISTS idx_review_reports_open
    ON review_reports (review_id, created_at DESC) WHERE resolved_at IS NULL;

-- One developer reply per review, from a user with push access to the repo.
CREATE TABLE IF NOT EXISTS review_replies (
    review_id         TEXT        PRIMARY KEY REFERENCES reviews(id) ON DELETE CASCADE,
    author_id         BIGINT      NOT NULL,
    author_login      TEXT        NOT NULL,
    author_avatar_url TEXT,
    text              TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Users a moderator stopped from writing reviews, replies, votes and reports.
-- banned_by NULL means the operator's X-Admin-Token.
CREATE TABLE IF NOT EXISTS review_bans (
    github_id  BIGINT      PRIMARY KEY,
    reason     TEXT,
    banned_by  BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Audit log of every moderation action. review_id is not a FK so entries
-- outlive a hard delete. moderator_id NULL means the operator's X-Admin-Token.
CREATE TABLE IF NOT EXISTS review_moderation_log (
    id             BIGSERIAL   PRIMARY KEY,
    moderator_id   BIGINT,
    action         TEXT        NOT NULL,
    review_id      TEXT,
    target_user_id BIGINT,
    reason         TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_review_moderation_log_created
    ON review_moderation_log (created_at DESC);
