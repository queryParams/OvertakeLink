#!/bin/bash
# Сборка OvertakeLink без установки чего-либо в систему: всё берётся из ~/.local/opt.
set -euo pipefail
O=$HOME/.local/opt
export JAVA_HOME=$O/jdk PATH=$O/jdk/bin:$PATH
export ANDROID_HOME=$O/android-sdk ANDROID_USER_HOME=$O/android-sdk/.user
export GRADLE_USER_HOME=$O/gradle-home
cd "$(dirname "$0")"
./gradlew --no-daemon -q "${@:-assemblePlaystoreDebug}"
