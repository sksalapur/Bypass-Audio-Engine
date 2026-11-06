#!/system/bin/sh
# ═══════════════════════════════════════════════════════════════════
# Bypass Audiophile Engine — Lightweight Micro HTTP Daemon
# Zero-dependency shell HTTP/1.1 server invoked by toybox nc
# ═══════════════════════════════════════════════════════════════════

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODDIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WEBROOT="$MODDIR/webroot"

# Read the primary HTTP request line from stdin
read -r REQUEST_LINE || exit 0

# Strip \r and parse method and target URI without subprocess forks
REQUEST_LINE=$(printf "%s" "$REQUEST_LINE" | tr -d '\r\n')
[ -z "$REQUEST_LINE" ] && exit 0

METHOD="${REQUEST_LINE%% *}"
REST_URI="${REQUEST_LINE#* }"
FULL_URI="${REST_URI% *}"

# Read remaining request headers until empty line
CONTENT_LENGTH=0
while IFS= read -r HEADER; do
    HEADER=$(printf "%s" "$HEADER" | tr -d '\r\n')
    [ -z "$HEADER" ] && break
    case "$HEADER" in
        [Cc][Oo][Nn][Tt][Ee][Nn][Tt]-[Ll][Ee][Nn][Gg][Tt][Hh]:*)
            CONTENT_LENGTH="${HEADER#*:}"
            CONTENT_LENGTH=$(printf "%s" "$CONTENT_LENGTH" | tr -d ' ')
            ;;
    esac
done

# Read body if Content-Length > 0 and POST
BODY=""
if [ "$METHOD" = "POST" ] && [ -n "$CONTENT_LENGTH" ] && [ "$CONTENT_LENGTH" -gt 0 ] 2>/dev/null; then
    # Read specified number of characters if possible
    BODY=$(head -c "$CONTENT_LENGTH" 2>/dev/null | tr -d '\r\n')
fi

# Separate URI path and query string
URI_PATH=$(echo "$FULL_URI" | cut -d'?' -f1)
case "$FULL_URI" in
    *\?*)
        QUERY_STRING=$(echo "$FULL_URI" | cut -d'?' -f2- | tr -d '\r\n')
        ;;
    *)
        QUERY_STRING=""
        ;;
esac

# Merge body into QUERY_STRING if query string is empty
if [ -z "$QUERY_STRING" ] && [ -n "$BODY" ]; then
    QUERY_STRING="$BODY"
fi

# Helper function to extract query parameter by name
get_param() {
    PARAM_NAME="$1"
    echo "$QUERY_STRING" | tr '&' '\n' | grep "^${PARAM_NAME}=" | head -n1 | cut -d'=' -f2- | tr -d '\r\n '
}

# Helper: send HTTP response headers with CORS
send_headers() {
    STATUS_CODE="${1:-200 OK}"
    CONTENT_TYPE="${2:-text/html; charset=utf-8}"
    CLENGTH="$3"

    printf "HTTP/1.1 %s\r\n" "$STATUS_CODE"
    printf "Content-Type: %s\r\n" "$CONTENT_TYPE"
    printf "Connection: close\r\n"
    printf "Access-Control-Allow-Origin: *\r\n"
    printf "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
    printf "Access-Control-Allow-Headers: Content-Type, Authorization\r\n"
    if [ -n "$CLENGTH" ]; then
        printf "Content-Length: %s\r\n" "$CLENGTH"
    fi
    printf "\r\n"
}

# Handle CORS Preflight
if [ "$METHOD" = "OPTIONS" ]; then
    send_headers "200 OK" "text/plain" "0"
    exit 0
fi

# Route Dispatcher
case "$URI_PATH" in
    /api/telemetry)
        PAYLOAD=$(/system/bin/sh "$MODDIR/scripts/telemetry.sh" 2>/dev/null)
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/json; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /api/bitchord_queue)
        PAYLOAD=$(cat /data/data/com.music.bitchord/shared_prefs/bitchord_last_played.xml 2>/dev/null || echo '<map></map>')
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/xml; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /api/bitchord_stats)
        FILES=$(ls /data/data/com.music.bitchord/files/listening/*.json 2>/dev/null)
        if [ -n "$FILES" ]; then
            send_headers "200 OK" "application/json; charset=utf-8"
            printf "["
            FIRST=1
            for F in $FILES; do
                [ $FIRST -eq 0 ] && printf ","
                cat "$F"
                FIRST=0
            done
            printf "]"
        else
            send_headers "200 OK" "application/json; charset=utf-8" "2"
            printf "[]"
        fi
        ;;

    /api/buds_status)
        PREFS="/data/data/com.music.bypass/shared_prefs/realme_anc_prefs.xml"
        if [ -f "$PREFS" ]; then
            BATT=$(grep -o 'name="cached_battery" value="[0-9]*"' "$PREFS" 2>/dev/null | cut -d'"' -f4)
            MODE=$(grep -o 'name="active_mode">[^<]*' "$PREFS" 2>/dev/null | cut -d'>' -f2)
            [ -z "$BATT" ] && BATT="-1"
            [ -z "$MODE" ] && MODE="ANC"
            BODY="{\"battery\":$BATT,\"mode\":\"$MODE\"}"
            send_headers "200 OK" "application/json; charset=utf-8" "${#BODY}"
            printf "%s" "$BODY"
        else
            send_headers "200 OK" "application/json; charset=utf-8" "27"
            printf '{"battery":-1,"mode":"ANC"}'
        fi
        ;;

    /api/set_anc)
        PARAM_MODE=$(get_param "mode")
        if [ -n "$PARAM_MODE" ]; then
            am broadcast -a com.music.bypass.ACTION_SET_ANC_MODE -p com.music.bypass --es extra_mode "$PARAM_MODE" >/dev/null 2>&1
            BODY="{\"success\":true,\"mode\":\"$PARAM_MODE\"}"
        else
            BODY='{"success":false,"error":"Missing mode parameter"}'
        fi
        send_headers "200 OK" "application/json; charset=utf-8" "${#BODY}"
        printf "%s" "$BODY"
        ;;

    /api/controls|/api/settings)
        PAYLOAD=$(/system/bin/sh "$MODDIR/scripts/controls.sh" get 2>/dev/null)
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/json; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /api/control)
        PARAM_KEY=$(get_param "key")
        PARAM_VAL=$(get_param "value")

        if [ -n "$PARAM_KEY" ] && [ -n "$PARAM_VAL" ]; then
            PAYLOAD=$(/system/bin/sh "$MODDIR/scripts/controls.sh" set "$PARAM_KEY" "$PARAM_VAL" 2>/dev/null)
        else
            PAYLOAD='{"success":false,"error":"Missing key or value parameter"}'
        fi
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/json; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /api/volume)
        PARAM_LVL=$(get_param "level")
        if [ -n "$PARAM_LVL" ]; then
            PAYLOAD=$(/system/bin/sh "$MODDIR/scripts/controls.sh" set_volume "$PARAM_LVL" 2>/dev/null)
        else
            PAYLOAD='{"success":false,"error":"Missing level parameter"}'
        fi
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/json; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /api/restart_audio|/api/restart_audioserver)
        PAYLOAD=$(/system/bin/sh "$MODDIR/scripts/controls.sh" restart_audio 2>/dev/null)
        CLEN=$(printf "%s" "$PAYLOAD" | wc -c | tr -d ' ')
        send_headers "200 OK" "application/json; charset=utf-8" "$CLEN"
        printf "%s" "$PAYLOAD"
        ;;

    /|/index.html)
        TARGET_FILE="$WEBROOT/index.html"
        if [ -f "$TARGET_FILE" ]; then
            CLEN=$(wc -c < "$TARGET_FILE" | tr -d ' ')
            send_headers "200 OK" "text/html; charset=utf-8" "$CLEN"
            cat "$TARGET_FILE"
        else
            send_headers "404 Not Found" "text/plain" "14"
            printf "404 Not Found\n"
        fi
        ;;

    /style.css)
        TARGET_FILE="$WEBROOT/style.css"
        if [ -f "$TARGET_FILE" ]; then
            CLEN=$(wc -c < "$TARGET_FILE" | tr -d ' ')
            send_headers "200 OK" "text/css; charset=utf-8" "$CLEN"
            cat "$TARGET_FILE"
        else
            send_headers "404 Not Found" "text/plain" "14"
            printf "404 Not Found\n"
        fi
        ;;

    /app.js)
        TARGET_FILE="$WEBROOT/app.js"
        if [ -f "$TARGET_FILE" ]; then
            CLEN=$(wc -c < "$TARGET_FILE" | tr -d ' ')
            send_headers "200 OK" "application/javascript; charset=utf-8" "$CLEN"
            cat "$TARGET_FILE"
        else
            send_headers "404 Not Found" "text/plain" "14"
            printf "404 Not Found\n"
        fi
        ;;

    *)
        # Try generic file from webroot
        CLEAN_PATH=$(echo "$URI_PATH" | sed 's/\.\.//g')
        TARGET_FILE="$WEBROOT$CLEAN_PATH"
        if [ -f "$TARGET_FILE" ]; then
            MIME="application/octet-stream"
            case "$TARGET_FILE" in
                *.svg) MIME="image/svg+xml" ;;
                *.png) MIME="image/png" ;;
                *.ico) MIME="image/x-icon" ;;
                *.json) MIME="application/json" ;;
            esac
            CLEN=$(wc -c < "$TARGET_FILE" | tr -d ' ')
            send_headers "200 OK" "$MIME" "$CLEN"
            cat "$TARGET_FILE"
        else
            send_headers "404 Not Found" "text/plain" "14"
            printf "404 Not Found\n"
        fi
        ;;
esac

exit 0

