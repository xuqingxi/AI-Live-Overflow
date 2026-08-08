#!/bin/sh
APP_BASE_NAME=`basename "$0"`
APP_HOME=`dirname "$0"`
APP_HOME=`cd "$APP_HOME" && pwd`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD="java"
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
