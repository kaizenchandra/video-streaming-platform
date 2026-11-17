#!/bin/sh
set -eu
: "${ORIGIN_VERIFY_SECRET:?Cloud origin verification secret is required}"
: "${API_UPSTREAM:?Set the private API service host:port}"
case "$ORIGIN_VERIFY_SECRET" in *[!a-zA-Z0-9]*) echo 'Invalid origin secret format' >&2; exit 1;; esac
if [ "${#ORIGIN_VERIFY_SECRET}" -lt 32 ]; then echo 'Origin secret too short' >&2; exit 1; fi
case "$API_UPSTREAM" in *[!a-zA-Z0-9.:-]*) echo 'Invalid API upstream' >&2; exit 1;; esac
