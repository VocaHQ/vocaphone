# vocaphone — one entry point for three applications.
#
# Each application owns its own justfile, because the three share no toolchain
# at all. This file only aggregates them, so both of these work:
#
#   cd server && just run       # what you type while working on one app
#   just server run             # the same recipe, from anywhere in the repo
#   just --list server          # that app's recipes on their own
#
# server/ is the VocaHQ/vocagateway git submodule. Init it before server recipes:
#   git submodule update --init --recursive
#
# mod? makes the module optional so a clone without submodules can still run
# `just`, `just ios …`, `just android …`, and `just doctor`. Required `mod`
# would fail at parse time before any recipe (including the ci skip) can run.
#
# Recipes here are the cross-cutting ones: they fan out over all three.

mod android
mod ios
mod? server

# List the modules and the cross-cutting recipes.
default:
    @{{ just_executable() }} --list --unsorted

# Run every application's checks, skipping any whose toolchain is absent.
ci:
    #!/usr/bin/env bash
    # Nobody has all three toolchains installed at once — the iOS leg cannot run
    # off macOS — so a missing one is reported and skipped rather than failed.
    # Whatever can run, runs, and the exit code reflects only those legs.
    set -uo pipefail
    status=0
    just_bin='{{ just_executable() }}'

    echo "==> server"
    if [ ! -f server/justfile ]; then
        echo "skipped  server submodule not checked out (git submodule update --init)"
    elif command -v uv >/dev/null 2>&1; then
        # The gateway follows the django-modern-rest convention, where `test`
        # is the run-everything recipe and `unit` is just pytest.
        "${just_bin}" server::test || status=1
    else
        echo "skipped  uv is not installed"
    fi

    echo
    echo "==> android"
    if "${just_bin}" android::_sdk >/dev/null 2>&1; then
        "${just_bin}" android::ci || status=1
    else
        echo "skipped  no Android SDK on this machine"
    fi

    echo
    echo "==> ios"
    if [ "$(uname -s)" = "Darwin" ] && command -v xcodegen >/dev/null 2>&1; then
        "${just_bin}" ios::ci || status=1
    else
        echo "skipped  needs macOS with xcodegen installed"
    fi

    exit "${status}"

# Report the toolchain state of all three applications.
doctor:
    #!/usr/bin/env bash
    # Always exits 0: not having the Android SDK on a machine you only write
    # Swift on is a fact, not a fault. Each application's own `just doctor` is
    # the one that fails when something it needs is missing.
    set -uo pipefail
    just_bin='{{ just_executable() }}'
    if [ -f server/justfile ]; then
        echo "==> server"
        "${just_bin}" server::doctor || true
        echo
    else
        echo "==> server"
        echo "skipped  server submodule not checked out (git submodule update --init)"
        echo
    fi
    for app in android ios; do
        echo "==> ${app}"
        "${just_bin}" "${app}::doctor" || true
        echo
    done
