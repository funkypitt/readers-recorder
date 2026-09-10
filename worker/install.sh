#!/bin/sh
# Installs the worker as a systemd user service watching ~/kDrive/Recordings.
set -e
mkdir -p ~/.config/systemd/user
cp "$(dirname "$0")/readers-recorder-worker.service" ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now readers-recorder-worker
systemctl --user status readers-recorder-worker --no-pager | head -5
