<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to you under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# BreakTest Browser Recorder for Firefox

This local developer extension records a Firefox tab as HAR and assigns each
request to a named BreakTest transaction. It is the Firefox counterpart of the
Chrome and Edge extension, using Firefox's native web-request response stream
instead of the Chromium DevTools protocol.

## Requirements

- Firefox 128 or newer.
- A local BreakTest checkout containing `browser-extension/firefox`.

## Install temporarily

1. Open `about:debugging#/runtime/this-firefox` in Firefox.
2. Choose **Load Temporary Add-on...**.
3. Select `browser-extension/firefox/manifest.json` from the BreakTest checkout.
4. Open `about:addons`, select **BreakTest Browser Recorder**, and set **Run in
   Private Windows** to **Allow**.
5. Pin **BreakTest Browser Recorder** from Firefox's Extensions menu if desired.

Firefox removes temporary add-ons when the browser exits. Repeat these steps
after restarting Firefox. Changes to the extension source also require using
**Reload** beside the extension on the `about:debugging` page.

## Record from the first homepage request

1. Open the recorder from its toolbar icon or Firefox sidebar.
2. Enter the **Start URL**.
3. Keep `01_OpenHomepage` as the initial transaction name, or replace it.
4. Optionally select **Disable browser cache while recording**.
5. Choose **Start in blank tab**.

The extension creates a blank tab, activates capture, and then navigates to the
Start URL. **Start current tab** only captures requests that begin after the
recorder starts; it cannot recover the page's original load.

The cache option is off by default. Firefox exposes cache control as a global
browser setting, so selecting it temporarily disables the cache for all
Firefox windows and restores the previous browser-controlled state when the
recording finishes. Private windows do not by themselves guarantee a cold
HTTP cache, so use this option when that distinction matters.

## Name and export transactions

The transaction field is the name assigned to new requests. Type the next name,
then move the pointer toward the action that should start that transaction. The
name is committed after typing stops; requests already in progress keep their
original transaction.

Choose **Finish and export** to download the HAR. In BreakTest, use
**File > Import HAR...** and select that file. BreakTest turns the recorded
names into Transaction Controllers.

## Private-window recording

Private access is enabled as part of the installation steps above. Open the
recorder in a normal window, enter the start settings, and choose **Start in
private window**. On the launcher page in the new private window, choose **Open
recorder**. Capture starts before the Start URL loads.

Close all existing private windows before starting if you need a fresh private
session. Cookies created during the recording remain available for login flows
until the last private window closes.

## Capture behavior

- Captures HTTP/HTTPS request and response metadata, request bodies, and
  response bodies for the selected tab and its frames.
- Streams each response through Firefox unchanged while retaining up to 2 MiB
  per response for the HAR. Larger individual bodies are marked as truncated;
  there is no recording-wide body limit.
- Captures redirect response streams when Firefox provides them, including
  response bodies on 3xx requests.
- Keeps requests whose bodies Firefox cannot expose and marks them as
  unavailable in `_breaktest` metadata and the live list.
- Exports locally and does not upload recordings to a BreakTest service.
- Does not convert individual WebSocket frames into JMeter samplers.

## Troubleshooting

If an internal Firefox page cannot be recorded, enter an HTTP or HTTPS Start
URL and use **Start in blank tab**. If the private launcher cannot start, verify
**Run in Private Windows** is allowed, close the private window, reload the
temporary add-on, and try again.

HAR files may contain credentials, cookies, tokens, personal data, and response
content. Record only authorized systems and review files before sharing them.
