#!/usr/bin/env bash
# kt_web_dist's :serve (app/main/web): node serve.mjs <site> [port].
exec "$1" "$2" "$3" "${@:4}"
