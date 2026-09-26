cat >/dev/null
printf '%s' '{"ok":true,"data":"'
head -c 300000 /dev/zero | tr '\0' 'a'
printf '%s' '"}'
