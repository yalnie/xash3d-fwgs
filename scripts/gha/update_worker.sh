#!/bin/bash
set -e

if [ -z "$CF_API_TOKEN" ] || [ -z "$CF_ACCOUNT_ID" ]; then
	echo "::warning ::Cloudflare API credentials (CF_API_TOKEN, CF_ACCOUNT_ID) not found. Skipping Worker update."
	exit 0
fi

WORKER_NAME="${CF_WORKER_NAME:-xash3d}"
REPO="${GITHUB_REPOSITORY:-yalnie/xash3d-fwgs}"

GIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
VERSION_NAME="0.21-$GIT_HASH"

# Calculate build number matching Android build.gradle.kts:
# days since 2015-04-01 * 10000 + minute_of_day
BASE_DATE="2015-04-01"
NOW_SEC=$(date +%s)
BASE_SEC=$(date -d "$BASE_DATE" +%s)
DAYS=$(( (NOW_SEC - BASE_SEC) / 86400 ))
HOUR=$(date +%H)
MIN=$(date +%M)
HOUR=$((10#$HOUR))
MIN=$((10#$MIN))
MINUTE_OF_DAY=$(( HOUR * 60 + MIN ))
VERSION_CODE=$(( DAYS * 10000 + MINUTE_OF_DAY ))

if [ -n "$CUSTOM_CHANGELOG" ]; then
	CHANGELOG="$CUSTOM_CHANGELOG"
else
	CHANGELOG=$(git log -1 --format=%s 2>/dev/null || echo "Automated release update")
fi

# Escape double quotes and backslashes in changelog
CHANGELOG=$(echo "$CHANGELOG" | sed 's/\\/\\\\/g; s/"/\\"/g')

DOWNLOAD_URL="https://github.com/${REPO}/releases/download/continuous/xash3d-fwgs-android.apk"

echo "Updating Cloudflare Worker ($WORKER_NAME) for repository $REPO:"
echo "  Version Name: $VERSION_NAME"
echo "  Version Code: $VERSION_CODE"
echo "  Commit: $GIT_HASH"
echo "  Changelog: $CHANGELOG"
echo "  Download URL: $DOWNLOAD_URL"

PAYLOAD=$(cat <<EOF
{
  "latest_version_name": "$VERSION_NAME",
  "latest_version_code": "$VERSION_CODE",
  "latest_version_commit": "$GIT_HASH",
  "changelog": "$CHANGELOG",
  "platforms": {
    "android": {
      "download_url": "$DOWNLOAD_URL",
      "is_direct_download": true,
      "file_name": "xash3d-fwgs-android.apk"
    }
  }
}
EOF
)

if [ -n "$CF_KV_NAMESPACE_ID" ]; then
	echo "Updating via Cloudflare KV namespace $CF_KV_NAMESPACE_ID (key: latest)..."
	RESP=$(curl -s -w "\n%{http_code}" -X PUT \
		"https://api.cloudflare.com/client/v4/accounts/${CF_ACCOUNT_ID}/storage/kv/namespaces/${CF_KV_NAMESPACE_ID}/values/latest" \
		-H "Authorization: Bearer ${CF_API_TOKEN}" \
		-H "Content-Type: application/json" \
		--data "$PAYLOAD")
else
	echo "Updating Worker script directly ($WORKER_NAME)..."
	WORKER_SCRIPT=$(cat <<EOF
export default {
  async fetch(request) {
    const data = $PAYLOAD;
    return new Response(JSON.stringify(data), {
      headers: {
        "content-type": "application/json;charset=UTF-8",
        "access-control-allow-origin": "*"
      }
    });
  }
};
EOF
	)

	RESP=$(curl -s -w "\n%{http_code}" -X PUT \
		"https://api.cloudflare.com/client/v4/accounts/${CF_ACCOUNT_ID}/workers/scripts/${WORKER_NAME}" \
		-H "Authorization: Bearer ${CF_API_TOKEN}" \
		-H "Content-Type: application/javascript" \
		--data "$WORKER_SCRIPT")
fi

HTTP_CODE=$(echo "$RESP" | tail -n1)
BODY=$(echo "$RESP" | sed '$d')

if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
	echo "Successfully updated Cloudflare Worker! (HTTP $HTTP_CODE)"
else
	echo "::error ::Failed to update Cloudflare Worker (HTTP $HTTP_CODE): $BODY"
	exit 1
fi
