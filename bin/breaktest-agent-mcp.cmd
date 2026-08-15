@echo off
rem
rem Licensed to the Apache Software Foundation (ASF) under one or more
rem contributor license agreements.  See the NOTICE file distributed with
rem this work for additional information regarding copyright ownership.
rem The ASF licenses this file to you under the Apache License, Version 2.0
rem (the "License"); you may not use this file except in compliance with
rem the License.  You may obtain a copy of the License at
rem
rem http://www.apache.org/licenses/LICENSE-2.0
rem
rem Unless required by applicable law or agreed to in writing, software
rem distributed under the License is distributed on an "AS IS" BASIS,
rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
rem See the License for the specific language governing permissions and
rem limitations under the License.
rem

setlocal

if defined BREAKTEST_HOME set "JMETER_HOME=%BREAKTEST_HOME%"
if not defined JMETER_HOME for %%i in ("%~dp0..") do set "JMETER_HOME=%%~fi"

if exist "%JMETER_HOME%\bin\setenv.bat" call "%JMETER_HOME%\bin\setenv.bat"

set "JAVA_EXE="
if defined JRE_HOME set "JAVA_EXE=%JRE_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java.exe"

if not defined HEAP set "HEAP=-Xms256m -Xmx1g -XX:MaxMetaspaceSize=256m"
set "JAVA_OPTS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.text=ALL-UNNAMED"
set "AGENT_CLASSPATH=%JMETER_HOME%\bin\breaktest.jar;%JMETER_HOME%\lib\*;%JMETER_HOME%\lib\ext\*;%JMETER_HOME%\lib\junit\*"

"%JAVA_EXE%" %JAVA_OPTS% %HEAP% %JVM_ARGS% ^
  -Djava.awt.headless=true ^
  "-Dlog4j.configurationFile=%JMETER_HOME%\bin\log4j2.xml" ^
  "-Djmeter.logfile=%JMETER_HOME%\bin\breaktest-mcp.log" ^
  -cp "%AGENT_CLASSPATH%" ^
  org.apache.jmeter.ai.mcp.BreakTestAgentMcpServer "%JMETER_HOME%"

exit /b %ERRORLEVEL%
