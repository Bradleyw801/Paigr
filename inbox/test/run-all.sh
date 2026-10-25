#!/bin/sh
# Runs every suite in this folder, one after another, and prints each one's result.
# Needs Node 22.5+; the e2e-* suites also need Playwright with Chromium (PLAYWRIGHT=/path/to/node_modules/playwright).
cd "$(dirname "$0")/.." || exit 1
pass=0; fail=0
for t in test/test.js test/test2.js test/tr2.test.js test/sw.test.js test/merge-keep.test.js test/merge-overlap.test.js \
         test/merge-repeat.test.js test/merge-told.test.js test/tomb.test.js test/races2.js \
         server/test/store.test.js server/test/selfhost.test.js \
         test/e2e.js test/e2e-legit.js test/e2e-dishonest.js test/e2e-move.js test/e2e-names.js test/e2e-native.js \
         test/e2e-samekey.js test/e2e-words.js test/e2e-features.js server/test/e2e-selfhost.js; do
  case "$t" in *e2e*) [ "${SKIP_E2E:-0}" = "1" ] && continue ;; esac
  if out=$(timeout 600 node "$t" 2>&1); then pass=$((pass+1)); echo "PASS $t"
  else fail=$((fail+1)); echo "FAIL $t"; echo "$out" | tail -25 | sed 's/^/    /'; fi
done
echo "suites passed: $pass, failed: $fail"
[ "$fail" -eq 0 ]
