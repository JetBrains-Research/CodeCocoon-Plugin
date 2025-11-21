#!/bin/bash

PROJECT_PATH="/Users/vartiukhov/dev/projects/samples/ij-demo"
echo "Running headless mode with '$PROJECT_PATH'"

./gradlew headless -Proot="$PROJECT_PATH"