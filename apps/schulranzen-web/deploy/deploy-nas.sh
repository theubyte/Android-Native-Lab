#!/usr/bin/env bash
# Puts the web app on the NAS as a small nginx container. Run from apps/schulranzen-web.
# Only what the app needs is copied: no tests, no docs and never inhalt/.
set -euo pipefail
PORT="${1:-8850}"
DIR=/mnt/user/appdata/schulranzen-web

ssh nas "mkdir -p $DIR/html.new"
tar -cf - index.html manifest.webmanifest sw.js css js fonts icons beispiele | ssh nas "tar -xf - -C $DIR/html.new"
scp -q deploy/nginx.conf "nas:$DIR/nginx.conf"
ssh nas "rm -rf $DIR/html && mv $DIR/html.new $DIR/html"
ssh nas "cat > $DIR/docker-compose.yml" <<EOF
services:
  schulranzen-web:
    image: nginx:1.29-alpine
    container_name: schulranzen-web
    restart: unless-stopped
    ports:
      - "$PORT:80"
    volumes:
      - ./html:/usr/share/nginx/html:ro
      - ./nginx.conf:/etc/nginx/conf.d/default.conf:ro
EOF
ssh nas "cd $DIR && docker compose up -d"
