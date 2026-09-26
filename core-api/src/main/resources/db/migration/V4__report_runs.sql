-- FRD S6.1 "Scheduled Reporting" - history of generated reports.
--
-- The rendered document is stored inline (`content` BYTEA) rather than in
-- object storage. That is a deliberate trade-off: reports here are small
-- (tens of KB) and it keeps the platform deployable on a free tier with no S3
-- credentials. Swap `content` for a `storage_url` column and stream to object
-- storage once report volume or size makes that worthwhile.

CREATE TABLE report_run (
    id            UUID PRIMARY KEY,
    report_type   VARCHAR(50)   NOT NULL,
    format        VARCHAR(10)   NOT NULL,
    account       VARCHAR(100),
    period_from   DATE,
    period_to     DATE,
    status        VARCHAR(20)   NOT NULL,
    requested_by  VARCHAR(100)  NOT NULL,
    file_name     VARCHAR(255),
    content_type  VARCHAR(100),
    size_bytes    BIGINT,
    content       BYTEA,
    error_message VARCHAR(1000),
    created_at    TIMESTAMPTZ   NOT NULL,
    completed_at  TIMESTAMPTZ
);

CREATE INDEX idx_report_run_created_at ON report_run (created_at);
CREATE INDEX idx_report_run_type_status ON report_run (report_type, status);
