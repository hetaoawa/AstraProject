#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$root"
if [ ! -f config/application.yml ]; then
    cp config/application.example.yml config/application.yml
    echo 'Created config/application.yml. Edit it and set QQ_APP_ID / QQ_BOT_SECRET, then start again.'
    exit 0
fi
exec java -jar astra.jar "$@"
