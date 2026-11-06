#!/bin/sh
APP_BASE_NAME=`basename "$0"`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec "$JAVACMD" "$@"
