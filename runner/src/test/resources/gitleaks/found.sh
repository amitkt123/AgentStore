# Fake gitleaks: one finding in <source>/config.py, written to --report-path; exit code 1 means "leaks found".
report=""
source=""
while [ $# -gt 0 ]; do
  case "$1" in
    --report-path) shift; report="$1" ;;
    --*) ;;
    *) source="$1" ;;
  esac
  shift
done
printf '[{"File":"%s/config.py","StartLine":3,"RuleID":"aws-access-token","Secret":"REDACTED"}]' "$source" > "$report"
exit 1
