#!/usr/bin/env bash
# Docker's entrypoint sources non-executable .sh files, so isolate runner state.
bash "${SILVERCARE_DATABASE_DIR:-/opt/silvercare/database}/migrate.sh" initialize
