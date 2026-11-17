# Only the private qualification fixture has this benign, explicitly labeled test rule.
FROM --platform=linux/amd64 streaming-clamav:qualified
COPY --chown=clamav:clamav docker/clamav/coverage-canary.ndb /var/lib/clamav/streamworks-coverage.ndb
