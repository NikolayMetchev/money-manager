@echo off
REM Install Git hooks for the project on Windows
REM This is a wrapper that calls the bash script using Git Bash

echo Installing Git hooks...
bash "%~dp0install-hooks.sh"
