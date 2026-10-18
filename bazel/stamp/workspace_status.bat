@echo off
rem Bazel's workspace status command on Windows (.bazelrc): workspace_status.sh's commit.
for /f %%c in ('git rev-parse HEAD') do echo STABLE_GIT_COMMIT %%c
