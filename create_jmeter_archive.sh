#!/bin/bash
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to you under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Prerequisites: run ./build_jmeter.sh to populate bin/ and lib/ from a Gradle build.
# Script to create a compressed tar archive of bin and lib directories
# Excludes bin/examples and bin/testfiles
# Archives files under apache-jmeter/ directory

# Generate datetime string in format YYYYMMDD_HHMMSS
DATETIME=$(date +%Y%m%d_%H%M%S)

# Archive name
ARCHIVE_NAME="jmeter_${DATETIME}.tgz"

# Create temporary directory
TEMP_DIR=$(mktemp -d)
trap "rm -rf ${TEMP_DIR}" EXIT

# Create apache-jmeter directory structure
mkdir -p "${TEMP_DIR}/apache-jmeter"

# Copy bin directory excluding examples and testfiles
# Using rsync for better exclusion control
rsync -av --exclude='examples' --exclude='testfiles' \
    bin/ "${TEMP_DIR}/apache-jmeter/bin/"

# Copy lib directory
cp -R lib "${TEMP_DIR}/apache-jmeter/"

# Create the archive from temp directory
# -C: change to directory before archiving
# -c: create archive
# -z: compress with gzip
# -f: specify filename
tar -czf "${ARCHIVE_NAME}" -C "${TEMP_DIR}" apache-jmeter

# Check if tar command succeeded
if [ $? -eq 0 ]; then
    echo "Successfully created archive: ${ARCHIVE_NAME}"
    # Show archive size
    ls -lh "${ARCHIVE_NAME}"
else
    echo "Error: Failed to create archive"
    exit 1
fi
