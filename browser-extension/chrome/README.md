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

The BreakTest Browser Recorder records a Chrome or Microsoft Edge session as a
HAR file. During the recording you can assign meaningful transaction names.
When the HAR is imported, BreakTest converts those names into Transaction
Controllers.

This is currently a local developer extension. It is not yet available from the
Chrome Web Store or Microsoft Edge Add-ons.

## Requirements

- Google Chrome or Chromium-based Microsoft Edge version 118 or newer.
- A local BreakTest source checkout containing `browser-extension/chrome`.
- Developer mode enabled in the browser.

Firefox uses a separate native implementation in `browser-extension/firefox`.
See that directory's README for installation and browser-specific behavior.

## Install the local extension

### Chrome

1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Choose **Load unpacked**.
4. Select `<breaktest checkout>/browser-extension/chrome`. Select the `chrome`
   directory itself, not its parent.
5. Open Chrome's Extensions menu and pin **BreakTest Browser Recorder**.

### Microsoft Edge

1. Open `edge://extensions`.
2. Enable **Developer mode**.
3. Choose **Load unpacked**.
4. Select the same `<breaktest checkout>/browser-extension/chrome` directory.
5. Pin **BreakTest Browser Recorder** to the toolbar.

Chrome and Edge use the same extension files. No separate Edge build is needed.

## Record a scenario from its first request

1. Click the BreakTest extension icon to open the side panel.
2. Enter the **Start URL**, for example `https://application.example/`.
3. Keep the initial transaction name `01_OpenHomepage`, or replace it.
4. Optionally select **Disable browser cache while recording** when you need a
   forced cold-cache recording. It is off by default.
5. Choose **Start in blank tab**.

The extension creates a blank tab, attaches the DevTools recorder, and only
then opens the Start URL. This ordering captures the homepage document request
and all resources loaded by it.

**Start current tab** records only requests that start after the recorder has
attached. It cannot recover the requests that originally loaded the page.

Chrome internal pages, extension pages, and browser settings cannot be
recorded. Enter a Start URL and use **Start in blank tab** instead.

## Name transactions while recording

The transaction field always shows the transaction that will receive new
requests.

1. Complete the actions for the current transaction.
2. Type the next name, for example `02_Login` or `03_OpenDashboard`.
3. Move the pointer from the field toward the next action.
4. Perform that action.

Moving the pointer after typing commits the new name in the background. There
is no confirmation button. Requests that start while you are still typing stay
with the previous transaction. Responses that finish later remain assigned to
the transaction in which their request started.

The Transactions list displays the number of requests assigned to each name.
The Live requests list shows each completed request as status, method, and
path. A warning marker means Chrome could not return the complete response
body.

## Finish and import into BreakTest

1. Choose **Finish and export**.
2. The browser downloads a `breaktest-recording-<timestamp>.har` file.
3. Open BreakTest.
4. Choose **File > Import HAR...**.
5. Select the downloaded HAR and complete the import wizard.

BreakTest uses the recorded names instead of guessing transaction boundaries
from timing gaps.

## Record in a private browser session

Private mode provides a temporary cookie, history, and storage context. It is
useful for repeatable login flows without changing the normal browser profile.

### Enable private access once

In Chrome:

1. Open `chrome://extensions`.
2. Open **Details** for BreakTest Browser Recorder.
3. Enable **Allow in incognito**.

In Edge, use the equivalent **Allow in InPrivate** setting under
`edge://extensions`. The recorder button may still say **Start in incognito
window**; in Edge it opens an InPrivate window.

### Start a private recording

1. Close all existing incognito or InPrivate windows. This ensures the next
   window starts with a fresh temporary session.
2. Open the recorder in a normal browser window.
3. Enter the Start URL and initial transaction name.
4. Optionally select **Disable browser cache while recording**.
5. Choose **Start in incognito window**.
6. If the side panel does not open automatically, choose **Open recorder** on
   the BreakTest launcher page in the private window.
7. Record and export the scenario normally.
8. Close the private window to discard its temporary cookies and storage.

Cookies created during the recording remain available until that private
session is closed. This is intentional because multi-step authentication flows
need session cookies. For a guaranteed cold-cache run, explicitly select the
cache option before starting.

## Update the local extension

After pulling new BreakTest changes:

1. Finish any active recording first. Reloading discards an active recording.
2. Open `chrome://extensions` or `edge://extensions`.
3. Find BreakTest Browser Recorder.
4. Choose **Reload**.

The browser does not automatically update an unpacked extension.

## Troubleshooting

### “Chrome internal and extension pages cannot be recorded”

The selected tab is a New Tab, settings page, extension page, or another
protected browser page. Enter the application URL in **Start URL** and choose
**Start in blank tab**.

### The recorder is missing in a private window

Enable **Allow in incognito** or **Allow in InPrivate** in the extension's
Details page. Close existing private windows and try again.

### The private launcher says the instruction expired

Close the private window, reload the extension, and start again from the normal
recorder panel. Do not reload the extension while a recording is active.

### Recording stops after opening DevTools or another extension intervenes

Only one debugger client can control a tab at a time. Opening browser DevTools,
password managers, or other debugging extensions can detach the recorder. The
recorder attempts to reconnect, but for the most reliable capture:

- Keep browser DevTools closed on the recorded tab.
- Use a clean browser profile or private window.
- Disable unnecessary extensions in the recording profile.

### A response body is unavailable

Chrome does not expose every response body through DevTools. Redirects, cached
responses, streaming responses, and requests completed before attachment are
common examples. The recorder retries normal bodies and attempts redirect
bodies before Chrome reuses their request identifier. If Chrome still refuses
the body, the request is retained and marked as unavailable rather than being
silently presented as an empty response.

### Extension changes are not visible

Use **Reload** on the browser's Extensions page. Refreshing the recorded webpage
does not reload extension source files.

## Capture behavior and limits

- Captures HTTP/HTTPS requests, response metadata, request bodies, and bounded
  response bodies from the selected tab and attached frame/worker targets.
- Ignores browser- and extension-internal requests such as injected password
  manager assets.
- Captures at most 2 MiB of body text per response. There is no recording-wide
  body limit. Oversized individual bodies are marked as truncated in
  `_breaktest` metadata.
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

## Security and privacy

HAR files can contain credentials, cookies, bearer tokens, personal data and
response content. Record only systems you are authorized to test. Review and
redact HAR files before sharing them or committing them to source control.

The recorder processes and exports data locally. It does not upload recordings
to a BreakTest service.
