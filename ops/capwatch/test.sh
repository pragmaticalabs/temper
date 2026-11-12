#!/usr/bin/env bash
# Fixture tests for capwatch (no ssh). Exit 0 = all green.
exec python3 "$(dirname "$0")/test_capwatch.py"
