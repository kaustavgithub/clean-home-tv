#!/bin/bash
# Connect to the Google TV over wireless debugging, pairing first only if needed.
#
#   ./tv-connect.sh             connect (and pair if the Mac isn't paired yet)
#   ./tv-connect.sh install     connect, install CleanHome.apk, then disconnect
#   ./tv-connect.sh disconnect  disconnect and stop adb
#   ./tv-connect.sh lan         (while on Wi-Fi) also allow debugging over Ethernet on
#                               port 5555 until the TV restarts
#
# Set TV_IP (e.g. `export TV_IP=192.168.1.20`) to skip the IP question.
#
# Turn on the TV's Settings > System > Developer options > Wireless debugging first.
# On Ethernet that switch is greyed out; it works if `lan` was run since the last restart.
# Run this from a normal iTerm tab: macOS may block adb's network access elsewhere.

DIR="$(cd "$(dirname "$0")" && pwd)"
APK="$DIR/CleanHome.apk"

# The TV's IP comes from the TV_IP environment variable; if it isn't set, ask for it.
choose_ip() {
    [ -n "$TV_IP" ] && return
    echo "The TV's IP is under Settings > System > Developer options > Wireless debugging"
    echo "(or Settings > Network & Internet when on Ethernet)."
    read -r -p "TV IP: " TV_IP
    [ -z "$TV_IP" ] && { echo "No IP given."; exit 1; }
}

# Port of an mDNS service the TV is advertising, e.g. _adb-tls-connect or _adb-tls-pairing.
mdns_port() {
    adb mdns services 2>/dev/null | awk -v ip="$TV_IP" -v svc="$1" \
        '$0 ~ svc && index($NF, ip ":") == 1 { split($NF, a, ":"); print a[2]; exit }'
}

is_connected() {
    [ "$(adb -s "$TV_IP:$1" get-state 2>/dev/null)" = "device" ]
}

try_connect() {
    adb connect "$TV_IP:$1" >/dev/null 2>&1
    is_connected "$1"
}

pair() {
    echo
    echo "This Mac isn't paired with the TV yet (or the pairing was removed)."
    echo "On the TV: Wireless debugging > Pair device with pairing code."
    local port code
    sleep 1
    port=$(mdns_port _adb-tls-pairing)
    if [ -n "$port" ]; then
        echo "Found the pairing popup on port $port."
    else
        read -r -p "Pairing port (the number after $TV_IP: in the popup): " port
    fi
    read -r -p "6-digit code: " code
    adb pair "$TV_IP:$port" "$code" || { echo "Pairing failed. Try again with a fresh code."; exit 1; }
}

connect() {
    choose_ip
    # If nothing is connected, restart adb so its background server is started from this
    # terminal. A server started by another app may be blocked by macOS from reaching the TV.
    if ! adb devices 2>/dev/null | grep -q "device$"; then
        adb kill-server >/dev/null 2>&1
    fi
    adb start-server >/dev/null 2>&1

    # Ethernet: older-style network debugging, enabled with `lan` since the last restart.
    if try_connect 5555; then
        echo "Connected to $TV_IP:5555 (the 'lan' mode, active until the TV restarts;"
        echo "this is not the port shown under Wireless debugging, and that's fine)"
        export ANDROID_SERIAL="$TV_IP:5555"
        return
    fi

    local port
    port=$(mdns_port _adb-tls-connect)
    if [ -n "$port" ] && is_connected "$port"; then
        echo "Already connected to $TV_IP:$port"
        export ANDROID_SERIAL="$TV_IP:$port"
        return
    fi
    if [ -z "$port" ]; then
        echo "Couldn't find the TV's debugging port automatically."
        read -r -p "Port shown under Wireless debugging (after $TV_IP:): " port
    fi

    if ! try_connect "$port"; then
        pair
        try_connect "$port" || { echo "Still can't connect to $TV_IP:$port. Is Wireless debugging on?"; exit 1; }
    fi
    echo "Connected to $TV_IP:$port"
    export ANDROID_SERIAL="$TV_IP:$port"
}

case "${1:-connect}" in
    connect)
        connect
        ;;
    install)
        [ -f "$APK" ] || { echo "Not found: $APK"; exit 1; }
        connect
        adb install -r "$APK" && echo "Clean Home updated."
        adb disconnect >/dev/null
        echo "Disconnected. You can turn off Wireless debugging on the TV."
        ;;
    lan)
        connect
        adb tcpip 5555 || exit 1
        echo
        echo "Done. Until the TV restarts you can plug Ethernet back in and run:"
        echo "  $0 install      (enter the TV's Ethernet IP when asked)"
        echo "The TV may ask to 'Allow USB debugging' the first time: tick 'Always allow' and press Allow."
        ;;
    disconnect)
        adb disconnect >/dev/null 2>&1
        adb kill-server >/dev/null 2>&1
        echo "Disconnected. You can turn off Wireless debugging on the TV."
        ;;
    *)
        echo "Usage: $0 [connect|install|disconnect|lan]"
        exit 1
        ;;
esac
