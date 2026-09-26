cat >/dev/null
printf '%s' '{"ok":false,"error":{"code":"DEP_VULN","message":"audit found 2 high vulnerabilities"}}'
exit 1
