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

# BreakTest Browser Recorder

This Manifest V3 extension records the active Chrome or Chromium-based Edge
tab through the Chrome DevTools Protocol. It exports a HAR containing explicit
BreakTest transaction names that the BreakTest HAR importer converts into
Transaction Controllers.

## Install for development

1. Open `chrome://extensions` in Chrome or `edge://extensions` in Edge.
2. Enable **Developer mode**.
3. Choose **Load unpacked** and select this `browser-extension/chrome` folder.
4. Pin **BreakTest Browser Recorder** to the toolbar.

## Record a scenario

1. Click the BreakTest extension icon to open the side panel.
2. Enter the application start URL and the first transaction name.
3. Choose **Start in blank tab**. The extension creates an `about:blank` tab,
   attaches, then opens the start URL. Select **Disable browser cache while
   recording** first when a cold-cache recording is required. The initial
   document and all dependent requests are captured.
   Alternatively, choose **Start in incognito window** to create an incognito
   window and start the isolated recorder there.
4. Before each new business action, replace the transaction name. Once typing
   has stopped, moving the pointer commits the new transaction in the
   background. Requests that begin while typing remain assigned to the
   previous transaction.
5. Choose **Finish and export**.
6. In BreakTest, use **File > Import HAR...** and select the exported file.

Requests are assigned to the transaction that was active when the browser
started the request. Responses that finish after a transaction boundary remain
with their original transaction.

## Incognito recording

1. Open `chrome://extensions`, select **Details** for BreakTest Browser
   Recorder, and enable **Allow in incognito**. Chrome requires the user to
   grant this permission; the extension cannot enable it itself.
2. Close all existing incognito windows so the next one starts with a fresh
   temporary cookie and storage profile.
3. In the regular recorder panel, enter the transaction name and start URL,
   then choose **Start in incognito window**. If Chrome does not open the
   recorder panel automatically, choose **Open recorder** on the BreakTest page
   in the new window. The isolated recorder attaches before navigation and
   consumes the one-time launch instruction.
4. Close the incognito window after exporting the HAR to discard its cookies,
   history and temporary storage.

The manifest uses split incognito mode, so normal and incognito recorder state
are kept in separate extension processes. Cache use follows the recorder
option, while new cookies are intentionally allowed within the session because
login and multi-step application flows depend on them. The start URL and initial
transaction name are briefly placed in shared extension storage so the
incognito process can claim them; it deletes that instruction before starting.

## Current scope

- Captures HTTP/HTTPS requests, response metadata, request bodies and bounded
  response bodies from the selected tab and attached frame/worker targets.
- Ignores browser- and extension-internal requests such as injected password
  manager assets.
- Captures at most 2 MiB of body text per response and 24 MiB per recording.
  Oversized bodies are marked as truncated in `_breaktest` metadata.
- Requests use Chrome's durable response-body buffer when supported, with a
  compatibility fallback and short retries around `Network.getResponseBody`.
  Redirect bodies are requested immediately before Chrome reuses their request
  identifier for the next hop.
  Bodies Chrome still cannot expose are marked as unavailable in `_breaktest`
  metadata and in the live request list rather than silently appearing empty.
- Exports a HAR locally; it does not yet stream to a running BreakTest process.
- WebSocket handshakes may appear as HTTP requests, but individual WebSocket
  frames are not converted into JMeter samplers.
- Opening normal Chrome DevTools can briefly detach the extension debugger
  session. The recorder retries the connection for several seconds and keeps
  already captured requests available for export if it cannot reconnect.
- Popups that become independent tabs may need to be recorded separately.

HAR files can contain credentials, cookies, bearer tokens, personal data and
response content. Review and redact recordings before sharing them.
