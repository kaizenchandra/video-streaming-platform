# Official image currently publishes amd64; Docker Desktop/OrbStack can emulate it on ARM64.
FROM --platform=linux/amd64 clamav/clamav:1.4.6@sha256:cbc30de94b6e9b33ac14136ff45306cee02a1f9ae522394a3cc5b9215d1affe9
RUN sed -i '/^StreamMaxLength /d; /^MaxFileSize /d; /^MaxScanSize /d; /^AlertExceedsMax /d; /^SelfCheck /d; /^ConcurrentDatabaseReload /d' /etc/clamav/clamd.conf \
 && printf '\nStreamMaxLength 100M\nMaxFileSize 100M\nMaxScanSize 400M\nAlertExceedsMax yes\nSelfCheck 15\nConcurrentDatabaseReload no\n' >> /etc/clamav/clamd.conf
