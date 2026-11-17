#!/bin/sh
# Starts Strapi. The keys it needs to sign sessions and tokens are made on the first start and kept on the data
# volume, so they are never in the repository or the image, and stay the same across restarts.
set -eu
SECRETS=/data/secrets.env
if [ ! -f "$SECRETS" ]; then
  umask 077
  key() { node -e "console.log(require('crypto').randomBytes(24).toString('base64'))"; }
  {
    echo "APP_KEYS=$(key),$(key)"
    for name in API_TOKEN_SALT ADMIN_JWT_SECRET JWT_SECRET TRANSFER_TOKEN_SALT ENCRYPTION_KEY; do
      echo "$name=$(key)"
    done
  } > "$SECRETS"
fi
set -a
. "$SECRETS"
set +a
exec npm run start
