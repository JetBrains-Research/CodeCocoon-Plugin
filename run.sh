#!/bin/bash

PROJECT_PATH=""
echo "Running headless mode with '$PROJECT_PATH'"

./gradlew headless -Proot="$PROJECT_PATH"