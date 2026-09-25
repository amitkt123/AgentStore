# Fake gitleaks: mimics a fatal internal error. Exits 1 (same code as "leaks found") but never
# writes to --report-path, leaving only the empty temp file the scanner pre-created.
exit 1
