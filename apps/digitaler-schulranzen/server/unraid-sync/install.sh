#!/bin/bash
BASE="/mnt/user/appdata/digitaler-schulranzen-sync"
echo "=== Digitaler Schulranzen Sync-Server ==="
if ! command -v docker >/dev/null 2>&1; then
  echo "FEHLER: Docker wurde nicht gefunden. Termius bleibt offen."
else
  mkdir -p "$BASE/data"
  cd "$BASE" || echo "FEHLER: $BASE konnte nicht geoeffnet werden."
  if [ -f "$BASE/compose.yml" ]; then
    echo "[1/3] Docker-Image bauen..."
    if docker compose -f "$BASE/compose.yml" up -d --build; then
      echo "[2/3] Container gestartet."
      sleep 2
      echo "[3/3] Status:"
      docker ps --filter "name=schulranzen-sync"
      IP=$(hostname -I | awk '{print $1}')
      echo
      echo "API lokal: http://${IP}:8788"
      echo "Test:      http://${IP}:8788/health"
      echo "Daten:     $BASE/data"
    else
      echo "FEHLER beim Docker-Build/Start. Termius bleibt offen."
      docker compose -f "$BASE/compose.yml" logs --tail=80 2>/dev/null || true
    fi
  else
    echo "FEHLER: compose.yml fehlt. Termius bleibt offen."
  fi
fi
echo "----- Fertig - SSH/Termius wird NICHT beendet -----"
